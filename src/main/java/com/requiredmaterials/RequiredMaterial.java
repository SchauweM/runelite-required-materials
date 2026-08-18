package com.requiredmaterials;

import lombok.Data;

/**
 * A single material requirement, e.g. name="Oak logs", quantity=5. itemId is resolved
 * separately (via {@link net.runelite.client.game.ItemManager}) since the source only ever
 * gives us the item's display name.
 */
@Data
public class RequiredMaterial
{
	private final String name;
	private final int quantity;
	private Integer itemId;
}
