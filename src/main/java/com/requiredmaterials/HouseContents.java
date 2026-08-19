package com.requiredmaterials;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;

/**
 * What's been seen built in the player's house, remembered per account.
 *
 * There's no varbit per hotspot, so this reads the scene instead - late, because furniture is
 * spawned a few ticks after the scene loads, and across all four tile object kinds, because a
 * tool store is on a wall rather than the floor.
 */
@Slf4j
@Singleton
class HouseContents
{
	private static final String CONFIG_KEY = "houseObjects";

	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Inject
	private WikiRecipeService wikiRecipeService;

	private Set<Integer> seen;
	/** The most recent scene, kept so a lookup landing later can still be answered from it. */
	private final Set<Integer> lastScene = ConcurrentHashMap.newKeySet();
	/** Furniture name to the object ids it places, looked up from the wiki once per name. */
	private final Map<String, Set<Integer>> objectIds = new ConcurrentHashMap<>();
	private final Set<String> beingLookedUp = ConcurrentHashMap.newKeySet();
	private Runnable onResolved;

	/**
	 * @return true if the scene looked like a house and was recorded.
	 */
	boolean scanScene()
	{
		// Building mode is the only thing the client exposes that means "this is your own house"
		// and nowhere else. Other instances - raids, minigames - would otherwise be scanned too,
		// and there's no reliable way to tell a house from them once you're just standing in one.
		// The cost is that furniture is only recorded on visits made in building mode.
		if (client.getVarbitValue(VarbitID.POH_BUILDING_MODE) == 0)
		{
			return false;
		}

		Set<Integer> found = new HashSet<>();

		Scene scene = client.getTopLevelWorldView().getScene();
		for (Tile[][] plane : scene.getTiles())
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}

					for (TileObject object : objectsOn(tile))
					{
						if (object != null)
						{
							found.add(object.getId());
						}
					}
				}
			}
		}

		if (found.isEmpty())
		{
			return false;
		}

		load();
		// Accumulated rather than replaced: only the loaded part of the house is in the scene, so
		// a scan taken in one corner shouldn't erase what was seen in another.
		lastScene.clear();
		lastScene.addAll(found);
		return remember(found);
	}

	/**
	 * @return true once we've seen the furniture's objects in the house, null while we can't say.
	 *
	 * Never returns false. Not finding it doesn't mean it isn't there: only the loaded part of
	 * the house is visible, the player may not have gone inside yet, and an upgrade past this
	 * tier replaces the object with a different one - all of which would read as "missing".
	 * Callers get a positive confirmation or nothing.
	 */
	Boolean isBuilt(String furnitureName)
	{
		Set<Integer> ids = objectIds.get(furnitureName);
		if (ids == null)
		{
			lookUp(furnitureName);
			return null;
		}

		load();
		return !ids.isEmpty() && !Collections.disjoint(seen, ids) ? Boolean.TRUE : null;
	}

	/**
	 * The wiki knows which objects a build places - the in-game object is often named differently
	 * from the build ("Rejuvenation pool" places "Pool of Rejuvenation"), so its ids are the only
	 * dependable link between the two.
	 */
	private void lookUp(String furnitureName)
	{
		if (!beingLookedUp.add(furnitureName))
		{
			return;
		}

		wikiRecipeService.fetchObjectIds(furnitureName, ids ->
		{
			objectIds.put(furnitureName, ids);
			beingLookedUp.remove(furnitureName);

			// The scene was scanned before we knew these ids mattered, so check it again now.
			remember(lastScene);
			if (onResolved != null)
			{
				onResolved.run();
			}
		});
	}

	/**
	 * Keeps only what some prerequisite actually asks about. A scene holds hundreds of objects and
	 * almost none of them are furniture we'll ever be asked about, so storing the lot would bloat
	 * the saved config for nothing.
	 *
	 * @return true if anything worth remembering was found.
	 */
	private boolean remember(Set<Integer> found)
	{
		Set<Integer> interesting = new HashSet<>();
		for (Set<Integer> ids : objectIds.values())
		{
			interesting.addAll(ids);
		}
		interesting.retainAll(found);

		load();
		if (interesting.isEmpty() || !seen.addAll(interesting))
		{
			return false;
		}

		configManager.setRSProfileConfiguration(MaterialsManager.CONFIG_GROUP, CONFIG_KEY,
			seen.stream().map(String::valueOf).collect(Collectors.joining(",")));
		log.debug("Required materials: house now known to contain {}", seen);
		return true;
	}

	/** Set by the plugin so the panel can repaint once a lookup lands. */
	void onResolved(Runnable onResolved)
	{
		this.onResolved = onResolved;
	}

	private List<TileObject> objectsOn(Tile tile)
	{
		List<TileObject> objects = new ArrayList<>();
		Collections.addAll(objects, tile.getGameObjects());
		objects.add(tile.getWallObject());
		objects.add(tile.getDecorativeObject());
		objects.add(tile.getGroundObject());
		return objects;
	}

	private void load()
	{
		// Re-reads while empty: per-account config isn't readable until logged in, so an empty
		// first read means "not available yet" rather than "nothing stored".
		if (seen != null && !seen.isEmpty())
		{
			return;
		}

		seen = new HashSet<>();
		String stored = configManager.getRSProfileConfiguration(MaterialsManager.CONFIG_GROUP, CONFIG_KEY);
		if (stored == null || stored.isEmpty())
		{
			return;
		}

		for (String id : stored.split(","))
		{
			try
			{
				seen.add(Integer.parseInt(id.trim()));
			}
			catch (NumberFormatException ignored)
			{
				// A hand-edited value shouldn't stop the rest loading.
			}
		}
	}
}
