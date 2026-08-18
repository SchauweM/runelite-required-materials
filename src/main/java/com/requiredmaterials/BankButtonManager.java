package com.requiredmaterials;

import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.ScriptID;
import net.runelite.api.SpriteID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.util.ImageUtil;

/**
 * Injects a small toggle button into the bank window that switches {@link BankGroupedView}'s
 * grouped layout on and off. Attaches directly to {@link InterfaceID.Bankmain#UNIVERSE} (the
 * bank's background/frame widget) at a hardcoded pixel offset near the top-right corner, close
 * to the close button, rather than anchoring to any specific widget there.
 */
@Slf4j
@Singleton
public class BankButtonManager
{
	private static final int BUTTON_SIZE = 22;
	private static final int ICON_SIZE = 16;
	// Sprite overrides are keyed by sprite id; a negative one can't collide with a real game sprite.
	private static final int ICON_SPRITE_ID = -14501;
	// Used until the bank finishes building and the scrollbar has a real position.
	private static final int BUTTON_X = 384;
	private static final int BUTTON_Y = 32;
	private static final int SCROLLBAR_GAP = 4;
	private static final String ACTION = "View required materials";

	@Inject
	private Client client;

	@Inject
	private BankGroupedView bankGroupedView;

	private Widget buttonRect;
	private Widget buttonIcon;

	public void onBankWidgetLoaded()
	{
		Widget parent = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (parent == null)
		{
			log.warn("Required materials: bank loaded but the background widget ({}) was null - "
				+ "can't inject the filter button", InterfaceID.Bankmain.UNIVERSE);
			return;
		}

		injectButton(parent);
	}

	/**
	 * The scrollbar has no usable position when the bank widget first loads - only once the bank
	 * has finished building - so the button starts at a fixed spot and is moved next to it here.
	 */
	public void onScriptPostFired(int scriptId)
	{
		if (scriptId != ScriptID.BANKMAIN_FINISHBUILDING || buttonRect == null || buttonIcon == null)
		{
			return;
		}

		Widget scrollbar = client.getWidget(InterfaceID.Bankmain.SCROLLBAR);
		Widget parent = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (scrollbar == null || parent == null)
		{
			return;
		}

		Point scrollbarAt = scrollbar.getCanvasLocation();
		Point parentAt = parent.getCanvasLocation();
		int x = scrollbarAt.getX() - parentAt.getX() - BUTTON_SIZE - SCROLLBAR_GAP;
		int y = scrollbarAt.getY() - parentAt.getY();

		buttonRect.setOriginalX(x);
		buttonRect.setOriginalY(y);
		buttonIcon.setOriginalX(x + (BUTTON_SIZE - ICON_SIZE) / 2);
		buttonIcon.setOriginalY(y + (BUTTON_SIZE - ICON_SIZE) / 2);
		buttonRect.revalidate();
		buttonIcon.revalidate();
	}

	public void onBankWidgetClosed()
	{
		buttonRect = null;
		buttonIcon = null;
		bankGroupedView.reset();
	}

	private void injectButton(Widget parent)
	{
		if (buttonRect != null)
		{
			return;
		}

		registerIconSprite();

		// The game's own small square button sprite, so it reads as a button rather than a
		// coloured rectangle - it has a pressed-looking variant used while the filter is on.
		Widget background = parent.createChild(-1, WidgetType.GRAPHIC);
		background.setOriginalX(BUTTON_X);
		background.setOriginalY(BUTTON_Y);
		background.setOriginalWidth(BUTTON_SIZE);
		background.setOriginalHeight(BUTTON_SIZE);
		background.setName(ACTION);
		background.setAction(0, ACTION);
		background.setHasListener(true);
		background.setOnOpListener((JavaScriptCallback) ev -> toggleFilter());

		Widget icon = parent.createChild(-1, WidgetType.GRAPHIC);
		icon.setSpriteId(ICON_SPRITE_ID);
		icon.setOriginalX(BUTTON_X + (BUTTON_SIZE - ICON_SIZE) / 2);
		icon.setOriginalY(BUTTON_Y + (BUTTON_SIZE - ICON_SIZE) / 2);
		icon.setOriginalWidth(ICON_SIZE);
		icon.setOriginalHeight(ICON_SIZE);
		// Without this the icon swallows the hover, so the action text never appears.
		icon.setNoClickThrough(false);

		applyButtonSprite(background);
		background.revalidate();
		icon.revalidate();

		this.buttonRect = background;
		this.buttonIcon = icon;
	}

	/**
	 * The bank draws sprites, not Swing images, so the plugin's icon is registered as a sprite
	 * override under an id the game itself will never use. Scaled from the same icon.png the
	 * sidebar uses, so there's one asset to keep in sync.
	 */
	private void registerIconSprite()
	{
		if (client.getSpriteOverrides().containsKey(ICON_SPRITE_ID))
		{
			return;
		}

		BufferedImage icon = ImageUtil.loadImageResource(RequiredMaterialsPlugin.class, "icon.png");
		if (icon == null)
		{
			log.warn("Required materials: icon.png missing - the bank button will have no icon");
			return;
		}

		client.getSpriteOverrides().put(ICON_SPRITE_ID,
			ImageUtil.getImageSpritePixels(ImageUtil.resizeImage(icon, ICON_SIZE, ICON_SIZE), client));
	}

	private void toggleFilter()
	{
		bankGroupedView.setActive(!bankGroupedView.isActive());
		if (buttonRect != null)
		{
			applyButtonSprite(buttonRect);
		}
	}

	private void applyButtonSprite(Widget background)
	{
		background.setSpriteId(bankGroupedView.isActive()
			? SpriteID.UNKNOWN_BUTTON_SQUARE_SMALL_SELECTED
			: SpriteID.UNKNOWN_BUTTON_SQUARE_SMALL);
	}
}
