package com.requiredmaterials;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BankSnapshotTest
{
	@Test
	public void roundTripsCounts()
	{
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		counts.put(2353, 183);
		counts.put(973, 4);
		counts.put(590, 2);

		assertEquals(counts, BankSnapshot.decode(BankSnapshot.encode(counts)));
	}

	@Test
	public void handlesLargeQuantities()
	{
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		counts.put(995, Integer.MAX_VALUE);

		assertEquals(counts, BankSnapshot.decode(BankSnapshot.encode(counts)));
	}

	@Test
	public void emptyAndMissingValuesDecodeToNothing()
	{
		assertTrue(BankSnapshot.decode(null).isEmpty());
		assertTrue(BankSnapshot.decode("").isEmpty());
		assertTrue(BankSnapshot.encode(new LinkedHashMap<>()).isEmpty());
	}

	@Test
	public void skipsMalformedEntriesRatherThanLosingTheRest()
	{
		// A truncated or hand-edited value shouldn't throw away the whole bank.
		Map<Integer, Integer> counts = BankSnapshot.decode("2353:183,rubbish,:5,7:,995:100");

		assertEquals(2, counts.size());
		assertEquals(Integer.valueOf(183), counts.get(2353));
		assertEquals(Integer.valueOf(100), counts.get(995));
	}
}
