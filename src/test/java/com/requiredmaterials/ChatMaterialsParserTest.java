package com.requiredmaterials;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatMaterialsParserTest
{
	private final ChatMaterialsParser parser = new ChatMaterialsParser();

	@Test
	public void parsesNameThenQuantity()
	{
		assertFalse(parser.accumulate("Oak mast", "Oak logs x5, Iron nails x 20, Bolt of linen x5."));

		Map<String, Integer> materials = parser.getMaterials("Oak mast");
		assertEquals(3, materials.size());
		assertEquals(Integer.valueOf(5), materials.get("Oak logs"));
		assertEquals(Integer.valueOf(20), materials.get("Iron nails"));
		assertEquals(Integer.valueOf(5), materials.get("Bolt of linen"));
	}

	@Test
	public void parsesQuantityThenName()
	{
		assertFalse(parser.accumulate("Oak cargo hold", "8 x Oak plank, 32 x Iron nails."));

		Map<String, Integer> materials = parser.getMaterials("Oak cargo hold");
		assertEquals(Integer.valueOf(8), materials.get("Oak plank"));
		assertEquals(Integer.valueOf(32), materials.get("Iron nails"));
	}

	@Test
	public void stripsColourTags()
	{
		assertFalse(parser.accumulate("Part",
			"<col=ef1020>4 x Teak plank</col>, <col=ef1020>16 x Steel nails</col>."));

		Map<String, Integer> materials = parser.getMaterials("Part");
		assertEquals(Integer.valueOf(4), materials.get("Teak plank"));
		assertEquals(Integer.valueOf(16), materials.get("Steel nails"));
	}

	@Test
	public void treatsNonBreakingSpacesAsSpaces()
	{
		assertFalse(parser.accumulate("Part", "Oak logs\u00A0x\u00A05."));
		assertEquals(Integer.valueOf(5), parser.getMaterials("Part").get("Oak logs"));
	}

	@Test
	public void unclosedColourSpanExpectsAContinuation()
	{
		// The client cuts long lists mid-tag, leaving the last span open.
		assertTrue(parser.accumulate("Wind catcher",
			"<col=ef1020>8 x Steel bar</col>, <col=ef1020>10000 x Air"));
	}

	@Test
	public void continuationRestoresTheSpaceEatenByTheLineWrap()
	{
		// "...10000 x Air" + "rune, ..." must not glue into "Airrune".
		assertTrue(parser.accumulate("Wind catcher",
			"<col=ef1020>8 x Steel bar</col>, <col=ef1020>10000 x Air"));
		assertFalse(parser.appendContinuation("Wind catcher",
			"rune</col>, <col=ef1020>1 x Captured wind mote</col>"));

		Map<String, Integer> materials = parser.getMaterials("Wind catcher");
		assertEquals(Integer.valueOf(8), materials.get("Steel bar"));
		assertEquals(Integer.valueOf(10000), materials.get("Air rune"));
		assertEquals(Integer.valueOf(1), materials.get("Captured wind mote"));
	}

	@Test
	public void continuationJoiningDigitsDoesNotInsertASpace()
	{
		assertTrue(parser.accumulate("Part", "<col=ef1020>1"));
		assertFalse(parser.appendContinuation("Part", "0 x Oak plank</col>"));
		assertEquals(Integer.valueOf(10), parser.getMaterials("Part").get("Oak plank"));
	}

	@Test
	public void continuationForAnUnknownPartIsIgnored()
	{
		assertFalse(parser.appendContinuation("Never tracked", "rune</col>"));
	}

	@Test
	public void unparseableSegmentsAreSkippedRatherThanFailing()
	{
		assertFalse(parser.accumulate("Part", "Oak logs x5, some prose with no quantity."));

		Map<String, Integer> materials = parser.getMaterials("Part");
		assertEquals(1, materials.size());
		assertEquals(Integer.valueOf(5), materials.get("Oak logs"));
	}
}
