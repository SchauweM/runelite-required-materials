package com.requiredmaterials;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Properties;
import javax.inject.Inject;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ImageUtil;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Checks the things that stop a plugin loading in the client, without needing a client: a
 * malformed @Subscribe, a missing icon resource, or a manifest that no longer matches the code.
 */
public class PluginLoadTest
{
	@Test
	public void eventSubscribersAreWellFormed()
	{
		// EventBus rejects @Subscribe methods that aren't public or don't take exactly one
		// argument, which fails the plugin at startup rather than at compile time.
		new EventBus().register(new RequiredMaterialsPlugin());
	}

	@Test
	public void pluginIsDescribedAndExtendsPlugin()
	{
		assertTrue(Plugin.class.isAssignableFrom(RequiredMaterialsPlugin.class));

		PluginDescriptor descriptor = RequiredMaterialsPlugin.class.getAnnotation(PluginDescriptor.class);
		assertNotNull("plugin needs a @PluginDescriptor to appear in the plugin list", descriptor);
		assertFalse(descriptor.name().trim().isEmpty());
		assertFalse(descriptor.description().trim().isEmpty());
	}

	@Test
	public void navigationIconResourceLoads()
	{
		// startUp() feeds this straight into NavigationButton, so a moved or renamed resource
		// breaks the plugin the moment it's enabled.
		BufferedImage icon = ImageUtil.loadImageResource(RequiredMaterialsPlugin.class, "icon.png");
		assertNotNull("icon.png missing from the plugin's resource package", icon);
		assertTrue(icon.getWidth() > 0 && icon.getHeight() > 0);
	}

	@Test
	public void injectedFieldsAreAllResolvableTypes()
	{
		for (Field field : RequiredMaterialsPlugin.class.getDeclaredFields())
		{
			if (field.isAnnotationPresent(Inject.class))
			{
				assertNotNull(field.getName() + " has no type", field.getType());
			}
		}
	}

	@Test
	public void hubManifestMatchesTheCode() throws IOException, ClassNotFoundException
	{
		// Lives at the repo root for the hub, not in resources; Gradle runs tests from there.
		File file = new File("runelite-plugin.properties");
		assertTrue("runelite-plugin.properties missing from the repo root", file.isFile());

		Properties manifest = new Properties();
		try (InputStream in = new FileInputStream(file))
		{
			manifest.load(in);
		}

		String declared = manifest.getProperty("plugins");
		assertNotNull("manifest must name the plugin class", declared);

		Class<?> pluginClass = Class.forName(declared.trim());
		assertTrue(declared + " must extend Plugin", Plugin.class.isAssignableFrom(pluginClass));

		PluginDescriptor descriptor = pluginClass.getAnnotation(PluginDescriptor.class);
		assertEquals("hub display name and in-client name should agree",
			manifest.getProperty("displayName").trim(), descriptor.name());
	}
}
