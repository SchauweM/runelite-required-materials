package com.shipmaterials;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ShipMaterialsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ShipMaterialsPlugin.class);
		RuneLite.main(args);
	}
}
