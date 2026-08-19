package com.requiredmaterials;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PartNamesTest
{
	@Test
	public void stripsTheBoatSize()
	{
		assertEquals("Oak hull", PartNames.withoutVariant("Oak hull (skiff)"));
		assertEquals("Wooden mast and linen sails", PartNames.withoutVariant("Wooden mast and linen sails (raft)"));
		assertEquals("Iron keel", PartNames.withoutVariant("Iron keel"));
	}

	@Test
	public void readsTheBoatSize()
	{
		assertEquals("Skiff", PartNames.boatSizeOf("Oak hull (skiff)"));
		assertEquals("Sloop", PartNames.boatSizeOf("Oak hull (sloop)"));
		assertEquals("Raft", PartNames.boatSizeOf("Oak base (raft)"));
	}

	@Test
	public void anUnsuffixedBaseIsARaft()
	{
		assertEquals("Raft", PartNames.boatSizeOf("Oak base"));
		assertEquals("Raft", PartNames.boatSizeOf("Teak base"));
	}

	@Test
	public void partsWithoutABoatSizeHaveNone()
	{
		assertNull(PartNames.boatSizeOf("Iron keel"));
		assertNull(PartNames.boatSizeOf("Wind catcher"));
	}

	@Test
	public void baseAndHullShareOneName()
	{
		// The panel groups on this and the plugin fetches on it, so all four have to agree.
		assertEquals("Oak hull", PartNames.sharedName("Oak base"));
		assertEquals("Oak hull", PartNames.sharedName("Oak base (raft)"));
		assertEquals("Oak hull", PartNames.sharedName("Oak hull (skiff)"));
		assertEquals("Oak hull", PartNames.sharedName("Oak hull (sloop)"));
	}

	@Test
	public void otherPartsKeepTheirName()
	{
		assertEquals("Iron keel", PartNames.sharedName("Iron keel (skiff)"));
		assertEquals("Wind catcher", PartNames.sharedName("Wind catcher"));
	}

	@Test
	public void aConstructionParentheticalIsPartOfTheName()
	{
		// Callers gate on skill; these only show what the rules do when asked, and why asking
		// about a Construction name would be wrong.
		assertEquals("STASH units", PartNames.withoutVariant("STASH units (beginner)"));
		assertEquals("Beginner", PartNames.boatSizeOf("STASH units (beginner)"));
	}

	@Test
	public void handlesEmptyAndOddSuffixes()
	{
		assertNull(PartNames.boatSizeOf("Oak hull ()"));
		assertEquals("Oak hull", PartNames.withoutVariant("Oak hull ()"));
		assertEquals("", PartNames.capitalize(""));
	}
}
