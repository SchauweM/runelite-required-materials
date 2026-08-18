package com.shipmaterials;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ShipMaterialsConfig.GROUP)
public interface ShipMaterialsConfig extends Config
{
	String GROUP = "shipmaterials";

	@ConfigItem(
		keyName = "highlightColor",
		name = "Highlight color",
		description = "Border color drawn around bank items you still need.",
		position = 1
	)
	default Color highlightColor()
	{
		return Color.GREEN;
	}

	@ConfigItem(
		keyName = "dimOthers",
		name = "Dim other items",
		description = "Darken bank items that aren't part of your tracked requirements while the filter is active.",
		position = 2
	)
	default boolean dimOthers()
	{
		return true;
	}
}
