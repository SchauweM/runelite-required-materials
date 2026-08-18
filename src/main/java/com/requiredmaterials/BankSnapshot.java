package com.requiredmaterials;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.config.ConfigManager;

/**
 * Remembers what was in the bank the last time it was open.
 *
 * The client only exposes the bank's ItemContainer once the bank has been opened in the current
 * session, so after a restart every tracked material reads as zero until you visit a bank. This
 * keeps a copy so the panel can still show counts. Stored per account, since two accounts on the
 * same RuneLite profile have different banks.
 */
@Singleton
class BankSnapshot
{
	private static final String CONFIG_KEY = "bankSnapshot";

	@Inject
	private ConfigManager configManager;

	private Map<Integer, Integer> counts;

	void update(ItemContainer bank)
	{
		if (bank == null)
		{
			return;
		}

		Map<Integer, Integer> updated = new HashMap<>();
		for (Item item : bank.getItems())
		{
			// Placeholders sit in the bank at quantity 0 - they aren't items you have.
			if (item.getId() >= 0 && item.getQuantity() > 0)
			{
				updated.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}

		counts = updated;
		configManager.setRSProfileConfiguration(MaterialsManager.CONFIG_GROUP, CONFIG_KEY, encode(updated));
	}

	int count(int itemId)
	{
		ensureLoaded();
		return counts.getOrDefault(itemId, 0);
	}

	private void ensureLoaded()
	{
		if (counts == null)
		{
			counts = decode(configManager.getRSProfileConfiguration(MaterialsManager.CONFIG_GROUP, CONFIG_KEY));
		}
	}

	/**
	 * "id:qty,id:qty" rather than JSON - a full bank is a few hundred entries and this is stored
	 * on every bank change.
	 */
	static String encode(Map<Integer, Integer> counts)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Integer> entry : counts.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(entry.getKey()).append(':').append(entry.getValue());
		}
		return sb.toString();
	}

	static Map<Integer, Integer> decode(String encoded)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		if (encoded == null || encoded.isEmpty())
		{
			return counts;
		}

		for (String entry : encoded.split(","))
		{
			int separator = entry.indexOf(':');
			if (separator <= 0)
			{
				continue;
			}
			try
			{
				counts.put(Integer.parseInt(entry.substring(0, separator).trim()),
					Integer.parseInt(entry.substring(separator + 1).trim()));
			}
			catch (NumberFormatException ignored)
			{
				// A hand-edited or truncated value shouldn't stop the rest loading.
			}
		}
		return counts;
	}
}
