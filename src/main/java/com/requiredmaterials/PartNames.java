package com.requiredmaterials;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sailing part naming, shared by the panel (which parts share a card) and the plugin (which wiki
 * page to fetch) - they have to agree or a part groups under a name nothing looked up.
 *
 * Skill-agnostic on purpose: "(beginner)" in a Construction name isn't a boat size, so callers
 * gate on skill first.
 */
final class PartNames
{
	private static final Pattern VARIANT_SUFFIX = Pattern.compile("\\s*\\(([^)]*)\\)$");
	private static final String BASE = "base";
	private static final String HULL = "hull";

	private PartNames()
	{
	}

	/** "Oak hull (skiff)" -> "Oak hull". */
	static String withoutVariant(String partName)
	{
		return VARIANT_SUFFIX.matcher(partName).replaceFirst("").trim();
	}

	/** "Oak hull (skiff)" -> "Skiff", null when unsuffixed. */
	static String variantOf(String partName)
	{
		Matcher matcher = VARIANT_SUFFIX.matcher(partName);
		if (!matcher.find())
		{
			return null;
		}
		String variant = matcher.group(1).trim();
		return variant.isEmpty() ? null : capitalize(variant);
	}

	/** Raft parts carry no suffix - they're just "&lt;tier&gt; base". */
	static String boatSizeOf(String partName)
	{
		String variant = variantOf(partName);
		if (variant != null)
		{
			return variant;
		}
		return isBase(partName) ? "Raft" : null;
	}

	/** A raft's "base" and a skiff's "hull" are one slot, filed under "hull" on the wiki. */
	static String sharedName(String partName)
	{
		String withoutVariant = withoutVariant(partName);
		return isBase(withoutVariant)
			? withoutVariant.substring(0, withoutVariant.length() - BASE.length()) + HULL
			: withoutVariant;
	}

	static String capitalize(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static boolean isBase(String partName)
	{
		return partName.toLowerCase(Locale.ROOT).endsWith(BASE);
	}
}
