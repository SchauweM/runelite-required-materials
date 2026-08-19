package com.requiredmaterials;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(MaterialsManager.CONFIG_GROUP)
public interface RequiredMaterialsConfig extends Config
{
	@ConfigItem(
		keyName = "skipBuildable",
		name = "Don't track builds you can already make",
		description = "Clicking Build on something you already have the materials and levels for won't add it to your list.",
		position = 1
	)
	default boolean skipBuildable()
	{
		return true;
	}

	@ConfigItem(
		keyName = "clearWhenBuilt",
		name = "Clear builds when you make them",
		description = "Clicking Build on something already on your list removes it, as long as you had everything it needed.",
		position = 2
	)
	default boolean clearWhenBuilt()
	{
		return false;
	}
}
