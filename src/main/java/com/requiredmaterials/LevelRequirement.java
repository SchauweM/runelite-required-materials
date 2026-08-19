package com.requiredmaterials;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.Skill;

/** Level requirements are carried as text ("Level 20 Sailing"); this reads one back. */
final class LevelRequirement
{
	private static final Pattern PATTERN = Pattern.compile("^Level (\\d+) (.+)$", Pattern.CASE_INSENSITIVE);

	private LevelRequirement()
	{
	}

	/** @return the level, or -1 if unparseable. */
	static int levelIn(String requirement)
	{
		Matcher matcher = PATTERN.matcher(requirement);
		return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
	}

	/**
	 * @return the skill named, or null if it isn't parseable or isn't a real skill.
	 */
	static Skill skillIn(String requirement)
	{
		Matcher matcher = PATTERN.matcher(requirement);
		if (!matcher.matches())
		{
			return null;
		}

		return named(matcher.group(2).trim());
	}

	/**
	 * @return the skill with this exact name, or null - e.g. "Other", the bucket used for
	 * requirements tracked before the source skill was recorded.
	 */
	static Skill named(String name)
	{
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(name))
			{
				return skill;
			}
		}
		return null;
	}

	/**
	 * @return true if the player meets it, or if it isn't a recognisable requirement - something
	 * unparseable shouldn't read as one the player has failed.
	 */
	static boolean isMet(Client client, String requirement)
	{
		Skill skill = skillIn(requirement);
		return skill == null || client.getRealSkillLevel(skill) >= levelIn(requirement);
	}
}
