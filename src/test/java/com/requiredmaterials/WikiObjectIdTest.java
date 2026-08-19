package com.requiredmaterials;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WikiObjectIdTest
{
	@Test
	public void readsASingleObjectId()
	{
		// Tool store 3, whose object the game calls "Tools".
		assertEquals(Set.of(6788), WikiRecipeService.parseObjectIds("|name = Tool store 3\n|id = 6788\n"));
	}

	@Test
	public void readsVersionedAndCommaSeparatedIds()
	{
		// A page covering several variants numbers its fields, and one build can place several
		// objects - the jewellery box being the extreme case.
		Set<Integer> ids = WikiRecipeService.parseObjectIds("|id1 = 29239\n|id2 = 29240\n");
		assertEquals(Set.of(29239, 29240), ids);

		assertEquals(Set.of(37492, 37493, 37494),
			WikiRecipeService.parseObjectIds("|id = 37492,37493,37494\n"));
	}

	@Test
	public void ignoresFieldsThatArentIds()
	{
		Set<Integer> ids = WikiRecipeService.parseObjectIds("|id = N/A\n|idlike = 5\n|level = 80\n");
		assertTrue(ids.isEmpty());
	}
}
