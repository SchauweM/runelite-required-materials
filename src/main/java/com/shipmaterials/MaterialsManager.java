package com.shipmaterials;

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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;

/**
 * Parses the ship-upgrade "materials:" chat message, keeps a running set of what's
 * being tracked (keyed by part name so re-clicking a requirement just replaces it),
 * resolves item names to ids via {@link ItemManager}, and persists across sessions.
 *
 * Two different in-game sources send this kind of message, in two different item orderings:
 * - Ship upgrade requirements: "Oak mast and linen sail materials: Oak logs x5, Iron nails x 20, Bolt of linen x5."
 * - Skill info menu part clicks: "Oak cargo hold: 8 x Oak plank, 32 x Iron nails"
 *
 * Long item lists get split by the client across multiple chat lines, with the continuation
 * line carrying no "part name:" prefix - just raw items. {@link #tryParseAndTrack} reports
 * whether its message ended mid-list (trailing comma) via {@link ParseResult#isContinuationExpected()};
 * the caller then routes the next unlabelled message to {@link #tryAppendContinuation}.
 */
@Slf4j
@Singleton
public class MaterialsManager
{
	private static final Pattern REQUIREMENT_PATTERN = Pattern.compile(
		"^(.*?):\\s*(.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern NAME_THEN_QTY_PATTERN = Pattern.compile(
		"^(.*?)\\s*x\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern QTY_THEN_NAME_PATTERN = Pattern.compile(
		"^(\\d+)\\s*x\\s*(.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");
	private static final String CONFIG_KEY_TRACKED = "tracked";

	private static final Type SAVE_TYPE =
		new TypeToken<LinkedHashMap<String, SavedRequirement>>()
		{
		}.getType();

	// Config saved before level requirements existed stored each part as a bare materials
	// array - kept around purely so load() can fall back to it instead of losing old data.
	private static final Type LEGACY_SAVE_TYPE =
		new TypeToken<LinkedHashMap<String, List<SavedMaterial>>>()
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
	private final Map<String, String> pendingRawMaterialsText = new LinkedHashMap<>();
	private Map<String, Integer> fullItemNameIndex;

	@Value
	public static class ParseResult
	{
		String partName;
		boolean continuationExpected;
	}

	/**
	 * @return the parse result if the message matched, otherwise null. If the item list was
	 * split across further chat lines, nothing is tracked yet and continuationExpected is true.
	 */
	public ParseResult tryParseAndTrack(String rawMessage)
	{
		Matcher requirementMatcher = REQUIREMENT_PATTERN.matcher(rawMessage);
		if (!requirementMatcher.matches())
		{
			return null;
		}

		String partName = normalize(requirementMatcher.group(1)).trim();
		return accumulate(partName, requirementMatcher.group(2).trim());
	}

	/**
	 * @return the parse result if this completed or extended a pending item list, otherwise
	 * null (e.g. nothing is pending for this part).
	 */
	public ParseResult tryAppendContinuation(String partName, String rawMessage)
	{
		String pending = pendingRawMaterialsText.get(partName);
		if (pending == null)
		{
			return null;
		}

		return accumulate(partName, join(pending, rawMessage.trim()));
	}

	/**
	 * The client's line-wrap breaks a message at a space and drops that space rather than
	 * leaving it on either side - confirmed by capturing a real split ("...10000 x Air" /
	 * "rune, ...", both fragments landing flush against the break with no space anywhere), which
	 * without this glues into the wrong item name ("Airrune" instead of "Air rune"). Restored
	 * only between two letters, since that's the only place gluing without a space actually
	 * changes meaning - digits never have internal spaces to lose, and a dropped space next to
	 * punctuation (e.g. a comma boundary) already reads fine once entries are comma-split and
	 * trimmed.
	 */
	private String join(String pending, String continuation)
	{
		String pendingContent = TAG_PATTERN.matcher(pending).replaceAll("");
		String continuationContent = TAG_PATTERN.matcher(continuation).replaceAll("");

		char lastChar = pendingContent.isEmpty() ? ' ' : pendingContent.charAt(pendingContent.length() - 1);
		char firstChar = continuationContent.isEmpty() ? ' ' : continuationContent.charAt(0);

		boolean droppedSpace = Character.isLetter(lastChar) && Character.isLetter(firstChar);
		return droppedSpace ? pending + " " + continuation : pending + continuation;
	}

	/**
	 * Long item lists get split across chat lines by the client wherever the raw text happens
	 * to reach its length limit - mid-word, mid-number, mid-tag - not at any punctuation
	 * boundary, so the split point itself carries no marker. Raw text is buffered here across
	 * calls (tags and all) and only parsed once {@link #isComplete} says the accumulated text
	 * isn't missing anything, which avoids ever having to patch up an item that turns out to
	 * have been split mid-name.
	 */
	private ParseResult accumulate(String partName, String rawMaterialsText)
	{
		if (!isComplete(rawMaterialsText))
		{
			pendingRawMaterialsText.put(partName, rawMaterialsText);
			return new ParseResult(partName, true);
		}

		pendingRawMaterialsText.remove(partName);
		String materialsList = stripTrailingPeriod(normalize(rawMaterialsText));
		List<RequiredMaterial> materials = parseMaterialList(materialsList, materialsList);
		if (materials.isEmpty())
		{
			return null;
		}

		// A plain LinkedHashMap keeps a re-put key's original position rather than moving it to
		// the end, so re-tracking an already-tracked part (e.g. re-clicking it) wouldn't bump it
		// to the bottom without explicitly removing it first.
		requirements.remove(partName);
		requirements.put(partName, new TrackedRequirement(partName, materials));
		save();
		return new ParseResult(partName, false);
	}

	/**
	 * Item lists come in two shapes: plain text terminated by a period (ship upgrade
	 * requirements), or a run of "&lt;col=...&gt;item&lt;/col&gt;" spans with no terminating
	 * punctuation at all (skill guide part clicks). For the second shape the only reliable
	 * truncation marker is whether the split landed inside the last opened span - a
	 * continuation always resumes with a fresh "&lt;col=...&gt;" of its own rather than closing
	 * off the previous message's dangling one, so checking for balanced tag counts overall
	 * doesn't work, only whether the last-opened one specifically got closed.
	 */
	private boolean isComplete(String rawMaterialsText)
	{
		String trimmed = rawMaterialsText.trim();
		if (trimmed.endsWith("."))
		{
			return true;
		}

		int lastOpenTag = trimmed.lastIndexOf("<col=");
		if (lastOpenTag == -1)
		{
			return false;
		}
		return trimmed.indexOf("</col>", lastOpenTag) != -1;
	}

	private String normalize(String rawMessage)
	{
		// The client renders some of these messages with non-breaking spaces (U+00A0) around
		// "x" instead of regular spaces, which \s doesn't match - normalize before parsing.
		return TAG_PATTERN.matcher(rawMessage).replaceAll("").replace('\u00A0', ' ');
	}

	private String stripTrailingPeriod(String s)
	{
		return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
	}

	private List<RequiredMaterial> parseMaterialList(String materialsList, String messageForLogging)
	{
		List<RequiredMaterial> materials = new ArrayList<>();
		for (String entry : materialsList.split(","))
		{
			String trimmedEntry = entry.trim();
			if (trimmedEntry.isEmpty())
			{
				continue;
			}

			Matcher nameThenQty = NAME_THEN_QTY_PATTERN.matcher(trimmedEntry);
			Matcher qtyThenName = QTY_THEN_NAME_PATTERN.matcher(trimmedEntry);

			String itemName;
			int quantity;
			if (nameThenQty.matches())
			{
				itemName = nameThenQty.group(1).trim();
				quantity = Integer.parseInt(nameThenQty.group(2));
			}
			else if (qtyThenName.matches())
			{
				quantity = Integer.parseInt(qtyThenName.group(1));
				itemName = qtyThenName.group(2).trim();
			}
			else
			{
				log.debug("Ship materials: couldn't parse requirement segment '{}' from message '{}'", entry, messageForLogging);
				continue;
			}

			RequiredMaterial material = new RequiredMaterial(itemName, quantity);
			material.setItemId(resolveItemId(itemName));
			materials.add(material);
		}
		return materials;
	}

	private Integer resolveItemId(String itemName)
	{
		// ItemManager#search only covers GE-tradeable items, so non-tradeable materials (e.g.
		// ship furniture) never show up in it regardless of how new or old they are. Fall back
		// to a full scan of the client's own item definitions, which covers every item.
		List<ItemPrice> results = itemManager.search(itemName);
		for (ItemPrice result : results)
		{
			if (result.getName().equalsIgnoreCase(itemName))
			{
				return result.getId();
			}
		}

		// getItemDefinition (used to build the full index) can only be called on the client
		// thread - callers off that thread (e.g. plugin startUp() triggered by a config UI
		// toggle) just skip this fallback rather than crashing.
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
			log.debug("Ship materials: no exact name match for '{}', falling back to closest search result '{}'",
				itemName, results.get(0).getName());
			return results.get(0).getId();
		}

		log.warn("Ship materials: could not resolve item id for '{}' - it won't be highlightable in the bank", itemName);
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

	public void setLevelRequirements(String partName, List<String> levelRequirements)
	{
		TrackedRequirement requirement = requirements.get(partName);
		if (requirement != null)
		{
			requirement.setLevelRequirements(levelRequirements);
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

	/**
	 * All resolved item ids across every tracked requirement, for the bank overlay to highlight.
	 */
	public Set<Integer> getRequiredItemIds()
	{
		return requirements.values().stream()
			.flatMap(r -> r.getMaterials().stream())
			.map(RequiredMaterial::getItemId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
	}

	public void save()
	{
		Map<String, SavedRequirement> toSave = new LinkedHashMap<>();
		for (TrackedRequirement requirement : requirements.values())
		{
			List<SavedMaterial> materials = requirement.getMaterials().stream()
				.map(m -> new SavedMaterial(m.getName(), m.getQuantity()))
				.collect(Collectors.toList());
			toSave.put(requirement.getPartName(), new SavedRequirement(materials, requirement.getLevelRequirements()));
		}
		configManager.setConfiguration(ShipMaterialsConfig.GROUP, CONFIG_KEY_TRACKED, gson.toJson(toSave));
	}

	public void load()
	{
		String json = configManager.getConfiguration(ShipMaterialsConfig.GROUP, CONFIG_KEY_TRACKED);
		if (json == null || json.isEmpty())
		{
			return;
		}

		Map<String, SavedRequirement> saved = parseSaved(json);
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
			requirements.put(entry.getKey(), requirement);
		}
	}

	/**
	 * Config saved before level requirements were persisted stored each part as a bare
	 * materials array instead of a {@link SavedRequirement} object, which fails to parse
	 * against the current shape - falls back to the old shape rather than losing everything
	 * that was already tracked.
	 */
	private Map<String, SavedRequirement> parseSaved(String json)
	{
		try
		{
			return gson.fromJson(json, SAVE_TYPE);
		}
		catch (JsonSyntaxException e)
		{
			Map<String, List<SavedMaterial>> legacy = gson.fromJson(json, LEGACY_SAVE_TYPE);
			if (legacy == null)
			{
				return null;
			}
			Map<String, SavedRequirement> migrated = new LinkedHashMap<>();
			for (Map.Entry<String, List<SavedMaterial>> entry : legacy.entrySet())
			{
				migrated.put(entry.getKey(), new SavedRequirement(entry.getValue(), new ArrayList<>()));
			}
			return migrated;
		}
	}

	private static class SavedRequirement
	{
		List<SavedMaterial> materials;
		List<String> levelRequirements;

		SavedRequirement(List<SavedMaterial> materials, List<String> levelRequirements)
		{
			this.materials = materials;
			this.levelRequirements = levelRequirements;
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
