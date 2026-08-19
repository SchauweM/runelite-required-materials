package com.requiredmaterials;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;

/**
 * How many of an item the player has to hand, across bank and inventory. The bank container only
 * exists once a bank has been opened this session, so {@link BankSnapshot} stands in until then.
 */
@Singleton
class HeldItems
{
	@Inject
	private Client client;

	@Inject
	private BankSnapshot bankSnapshot;

	boolean bankKnown()
	{
		return client.getItemContainer(InventoryID.BANK) != null || bankSnapshot.isKnown();
	}

	int count(int itemId)
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		int inBank = bank != null ? bank.count(itemId) : bankSnapshot.count(itemId);

		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		return inBank + (inventory != null ? inventory.count(itemId) : 0);
	}
}
