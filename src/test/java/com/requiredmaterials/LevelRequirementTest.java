package com.requiredmaterials;

import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LevelRequirementTest
{
	@Test
	public void readsLevelAndSkill()
	{
		assertEquals(20, LevelRequirement.levelIn("Level 20 Sailing"));
		assertEquals(Skill.SAILING, LevelRequirement.skillIn("Level 20 Sailing"));

		assertEquals(8, LevelRequirement.levelIn("Level 8 Construction"));
		assertEquals(Skill.CONSTRUCTION, LevelRequirement.skillIn("Level 8 Construction"));
	}

	@Test
	public void unparseableRequirementsReportNothing()
	{
		// isMet() treats these as met - a requirement we can't read shouldn't look like one the
		// player has failed.
		assertEquals(-1, LevelRequirement.levelIn("Requires a quest"));
		assertNull(LevelRequirement.skillIn("Requires a quest"));
		assertNull(LevelRequirement.skillIn("Level 20 Basketweaving"));
	}

	@Test
	public void looksUpSkillsByBareName()
	{
		assertEquals(Skill.SAILING, LevelRequirement.named("Sailing"));
		assertEquals(Skill.CONSTRUCTION, LevelRequirement.named("construction"));
		// The bucket for requirements tracked before the source skill was recorded.
		assertNull(LevelRequirement.named("Other"));
	}
}
