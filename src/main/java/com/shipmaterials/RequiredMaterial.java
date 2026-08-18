package com.shipmaterials;

import lombok.Data;

/**
 * A single material line parsed out of a "materials:" game message, e.g.
 * "Oak logs x5" -> name="Oak logs", quantity=5.
 *
 * itemId is resolved separately (via {@link net.runelite.client.game.ItemManager})
 * since the chat message only ever gives us the item's display name.
 */
@Data
public class RequiredMaterial
{
	private final String name;
	private final int quantity;
	private Integer itemId;
}
