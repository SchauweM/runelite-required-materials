package com.requiredmaterials;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;

/**
 * Keeps a running set of tracked requirements (keyed by part name so re-tracking one just
 * replaces it), resolves item names to ids, and persists across sessions. Materials and level
 * requirements arrive already resolved from the caller - this class only handles storage.
 */
@Slf4j
@Singleton
public class MaterialsManager
{
	private static final String CONFIG_GROUP = "requiredmaterials";
	private static final String CONFIG_KEY_TRACKED = "tracked";

	private static final Type SAVE_TYPE =
		new TypeToken<LinkedHashMap<String, SavedRequirement>>()
		{
		}.getType();

	private static final int MAX_ITEM_ID_SCAN = 35000;

	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	private final Map<String, TrackedRequirement> requirements = new LinkedHashMap<>();
	private Map<String, Integer> fullItemNameIndex;

	public void track(String partName, Map<String, Integer> materialQuantities, List<String> levelRequirements)
	{
		List<RequiredMaterial> materials = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : materialQuantities.entrySet())
		{
			RequiredMaterial material = new RequiredMaterial(entry.getKey(), entry.getValue());
			material.setItemId(resolveItemId(entry.getKey()));
			materials.add(material);
		}

		TrackedRequirement requirement = new TrackedRequirement(partName, materials);
		requirement.setLevelRequirements(levelRequirements);

		// Remove first: a re-put keeps the key's original position, but re-tracking should move
		// the part to the bottom of the list.
		requirements.remove(partName);
		requirements.put(partName, requirement);
		save();
	}

	private Integer resolveItemId(String itemName)
	{
		// ItemManager#search only covers GE-tradeable items, so non-tradeable materials never
		// show up in it - the full item-definition scan below covers those.
		List<ItemPrice> results = itemManager.search(itemName);
		for (ItemPrice result : results)
		{
			if (result.getName().equalsIgnoreCase(itemName))
			{
				return result.getId();
			}
		}

		// getItemDefinition asserts it's on the client thread; callers off it skip the fallback
		// rather than crashing.
		if (client.isClientThread())
		{
			Integer fullIndexMatch = fullItemNameIndex().get(itemName.toLowerCase());
			if (fullIndexMatch != null)
			{
				return fullIndexMatch;
			}
		}

		if (!results.isEmpty())
		{
			log.debug("Required materials: no exact name match for '{}', falling back to closest search result '{}'",
				itemName, results.get(0).getName());
			return results.get(0).getId();
		}

		log.warn("Required materials: could not resolve item id for '{}' - it won't be highlightable in the bank", itemName);
		return null;
	}

	private Map<String, Integer> fullItemNameIndex()
	{
		if (fullItemNameIndex != null)
		{
			return fullItemNameIndex;
		}

		Map<String, Integer> index = new HashMap<>();
		for (int id = 0; id < MAX_ITEM_ID_SCAN; id++)
		{
			ItemComposition composition = client.getItemDefinition(id);
			String name = composition.getName();
			if (name == null || name.isEmpty() || "null".equals(name))
			{
				continue;
			}
			index.putIfAbsent(name.toLowerCase(), composition.getId());
		}

		fullItemNameIndex = index;
		return index;
	}

	public Collection<TrackedRequirement> getRequirements()
	{
		return requirements.values();
	}

	public void setSkill(String partName, String skill)
	{
		TrackedRequirement requirement = requirements.get(partName);
		if (requirement != null)
		{
			requirement.setSkill(skill);
			save();
		}
	}

	public void remove(String partName)
	{
		if (requirements.remove(partName) != null)
		{
			save();
		}
	}

	public void clear()
	{
		requirements.clear();
		save();
	}

	public boolean isEmpty()
	{
		return requirements.isEmpty();
	}

	public void save()
	{
		Map<String, SavedRequirement> toSave = new LinkedHashMap<>();
		for (TrackedRequirement requirement : requirements.values())
		{
			List<SavedMaterial> materials = requirement.getMaterials().stream()
				.map(m -> new SavedMaterial(m.getName(), m.getQuantity()))
				.collect(Collectors.toList());
			toSave.put(requirement.getPartName(), new SavedRequirement(materials, requirement.getLevelRequirements(), requirement.getSkill()));
		}
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_TRACKED, gson.toJson(toSave));
	}

	public void load()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_TRACKED);
		if (json == null || json.isEmpty())
		{
			return;
		}

		Map<String, SavedRequirement> saved;
		try
		{
			saved = gson.fromJson(json, SAVE_TYPE);
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Required materials: discarding unreadable saved config", e);
			return;
		}

		if (saved == null)
		{
			return;
		}

		requirements.clear();
		for (Map.Entry<String, SavedRequirement> entry : saved.entrySet())
		{
			List<RequiredMaterial> materials = new ArrayList<>();
			for (SavedMaterial savedMaterial : entry.getValue().materials)
			{
				RequiredMaterial material = new RequiredMaterial(savedMaterial.name, savedMaterial.quantity);
				material.setItemId(resolveItemId(savedMaterial.name));
				materials.add(material);
			}
			TrackedRequirement requirement = new TrackedRequirement(entry.getKey(), materials);
			if (entry.getValue().levelRequirements != null)
			{
				requirement.setLevelRequirements(entry.getValue().levelRequirements);
			}
			requirement.setSkill(entry.getValue().skill);
			requirements.put(entry.getKey(), requirement);
		}
	}

	private static class SavedRequirement
	{
		List<SavedMaterial> materials;
		List<String> levelRequirements;
		String skill;

		SavedRequirement(List<SavedMaterial> materials, List<String> levelRequirements, String skill)
		{
			this.materials = materials;
			this.levelRequirements = levelRequirements;
			this.skill = skill;
		}
	}

	private static class SavedMaterial
	{
		String name;
		int quantity;

		SavedMaterial(String name, int quantity)
		{
			this.name = name;
			this.quantity = quantity;
		}
	}
}
