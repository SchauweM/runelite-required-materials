package com.requiredmaterials;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses item lists out of "materials:" chat messages, as a fallback for Construction activities
 * the wiki has no "{{Recipe}}" for (Mahogany Homes, Birdhouses, STASH units, eternal fires).
 *
 * Items come in two orderings - "Oak logs x5" and "8 x Oak plank" - and long lists get split
 * across chat lines wherever the text hits its length limit, not at punctuation.
 * {@link #accumulate} and {@link #appendContinuation} report whether more is still expected.
 */
@Slf4j
class ChatMaterialsParser
{
	private static final Pattern NAME_THEN_QTY_PATTERN = Pattern.compile("^(.*?)\\s*x\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern QTY_THEN_NAME_PATTERN = Pattern.compile("^(\\d+)\\s*x\\s*(.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");

	private final Map<String, String> pendingRawText = new LinkedHashMap<>();
	private final Map<String, Map<String, Integer>> completed = new LinkedHashMap<>();

	/**
	 * @return true if a continuation line is still expected for partName.
	 */
	boolean accumulate(String partName, String rawMaterialsText)
	{
		return process(partName, rawMaterialsText);
	}

	/**
	 * @return true if a continuation line is still expected for partName.
	 */
	boolean appendContinuation(String partName, String rawMessage)
	{
		String pending = pendingRawText.get(partName);
		if (pending == null)
		{
			return false;
		}
		return process(partName, join(pending, rawMessage.trim()));
	}

	Map<String, Integer> getMaterials(String partName)
	{
		return completed.get(partName);
	}

	private boolean process(String partName, String rawText)
	{
		if (!isComplete(rawText))
		{
			pendingRawText.put(partName, rawText);
			return true;
		}

		pendingRawText.remove(partName);
		completed.put(partName, parseMaterialList(stripTrailingPeriod(normalize(rawText))));
		return false;
	}

	/**
	 * A trailing period ends the plain-text shape. The tagged shape has no terminator, so
	 * completeness is "did the last opened &lt;col&gt; span get closed" - counting tags overall
	 * doesn't work, since a continuation opens a fresh span rather than closing the dangling one.
	 */
	private boolean isComplete(String rawText)
	{
		String trimmed = rawText.trim();
		if (trimmed.endsWith("."))
		{
			return true;
		}
		int lastOpenTag = trimmed.lastIndexOf("<col=");
		return lastOpenTag != -1 && trimmed.indexOf("</col>", lastOpenTag) != -1;
	}

	/**
	 * The line-wrap eats the space it breaks on, gluing words together ("Air" + "rune" ->
	 * "Airrune"). Only restored between two letters, where it actually changes meaning.
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

	private String normalize(String rawText)
	{
		return TAG_PATTERN.matcher(rawText).replaceAll("").replace('\u00A0', ' ');
	}

	private String stripTrailingPeriod(String s)
	{
		return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
	}

	private Map<String, Integer> parseMaterialList(String materialsList)
	{
		Map<String, Integer> materials = new LinkedHashMap<>();
		for (String entry : materialsList.split(","))
		{
			String trimmed = entry.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}

			Matcher nameThenQty = NAME_THEN_QTY_PATTERN.matcher(trimmed);
			Matcher qtyThenName = QTY_THEN_NAME_PATTERN.matcher(trimmed);
			if (nameThenQty.matches())
			{
				materials.put(nameThenQty.group(1).trim(), Integer.parseInt(nameThenQty.group(2)));
			}
			else if (qtyThenName.matches())
			{
				materials.put(qtyThenName.group(2).trim(), Integer.parseInt(qtyThenName.group(1)));
			}
			else
			{
				log.debug("Required materials: couldn't parse chat requirement segment '{}'", entry);
			}
		}
		return materials;
	}
}
