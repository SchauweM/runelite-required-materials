package com.requiredmaterials;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the wikitext parsing only - no network. Samples are trimmed from the real pages that
 * caused each bug.
 */
public class WikiRecipeServiceTest
{
	private final WikiRecipeService service = new WikiRecipeService();

	@Test
	public void readsMaterialsAndBothSkillLevels()
	{
		List<WikiRecipeService.Recipe> recipes = service.parseRecipes(
			"{{Recipe\n"
				+ "|skill1 = Sailing\n|skill1lvl = 16\n"
				+ "|skill2 = Construction\n|skill2lvl = 6\n"
				+ "|mat1 = Steel bar\n|mat1quantity = 4\n"
				+ "|mat2 = Charcoal\n|mat2quantity = 2\n"
				+ "|output1 = Range\n}}");

		assertEquals(1, recipes.size());
		WikiRecipeService.Recipe recipe = recipes.get(0);
		assertEquals(Integer.valueOf(4), recipe.getMaterials().get("Steel bar"));
		assertEquals(Integer.valueOf(2), recipe.getMaterials().get("Charcoal"));
		assertEquals(List.of("Level 16 Sailing", "Level 6 Construction"), recipe.getLevelRequirements());
	}

	@Test
	public void omittedQuantityMeansOne()
	{
		// Cat blanket lists "mat1 = Bolt of cloth" with no mat1quantity at all.
		List<WikiRecipeService.Recipe> recipes = service.parseRecipes(
			"{{Recipe\n|skill1 = Construction\n|skill1lvl = 5\n|mat1 = Bolt of cloth\n|output1 = Cat blanket\n}}");

		assertEquals(Integer.valueOf(1), recipes.get(0).getMaterials().get("Bolt of cloth"));
	}

	@Test
	public void readsOneRecipePerBoatSizeVariant()
	{
		List<WikiRecipeService.Recipe> recipes = service.parseRecipes(
			"{{Recipe\n|mat1 = Oak logs\n|mat1quantity = 10\n|output1subtxt = Raft\n}}\n"
				+ "{{Recipe\n|mat1 = Oak hull parts\n|mat1quantity = 10\n|output1subtxt = Skiff\n}}\n"
				+ "{{Recipe\n|mat1 = Large oak hull parts\n|mat1quantity = 16\n|output1subtxt = Sloop\n}}");

		assertEquals(3, recipes.size());
		assertEquals("Raft", recipes.get(0).getOutputSubtext());
		assertEquals("Sloop", recipes.get(2).getOutputSubtext());
		assertEquals(Integer.valueOf(16), recipes.get(2).getMaterials().get("Large oak hull parts"));
	}

	@Test
	public void skillWithoutALevelIsNotARequirement()
	{
		List<WikiRecipeService.Recipe> recipes = service.parseRecipes(
			"{{Recipe\n|skill1 = Sailing\n|skill1lvl = 20\n|skill2 = \n|skill2lvl = \n|mat1 = Rope\n}}");

		assertEquals(List.of("Level 20 Sailing"), recipes.get(0).getLevelRequirements());
	}

	@Test
	public void pageWithNoRecipeYieldsNothing()
	{
		assertTrue(service.parseRecipes("{{Infobox Item\n|name = Red crab\n}}").isEmpty());
	}

	@Test
	public void followsTemplateStyleDisambiguationLinks()
	{
		// Keg lists the facility via {{plink}}, with an unrelated [[wikilink]] further down.
		String wikitext = "'''Keg''' may refer to one of the following:\n\n"
			+ "* {{plink|Keg (facility)|pic=Keg icon|txt=Keg (Sailing Facility)}}\n"
			+ "* [[Keg (Warriors' Guild)]]\n{{disambig}}";

		assertEquals("Keg (facility)", service.firstDisambiguationLink(wikitext));
	}

	@Test
	public void followsPlainWikilinkDisambiguationLinks()
	{
		String wikitext = "'''Waterpump''' may refer to:\n\n"
			+ "* [[Water pump (amenity)]] - built with Construction\n"
			+ "* [[Waterpump (Falador)]]\n{{disambig}}";

		assertEquals("Water pump (amenity)", service.firstDisambiguationLink(wikitext));
	}

	@Test
	public void nonDisambiguationPageHasNoLinkToFollow()
	{
		// Range is a real scenery article, so there is nothing to redirect to.
		assertNull(service.firstDisambiguationLink("{{Infobox Scenery\n|name = Range\n}}\nA range for cooking."));
	}
}
