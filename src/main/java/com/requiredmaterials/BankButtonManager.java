package com.requiredmaterials;

import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

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
	private static final int BUTTON_X = 378;
	private static final int BUTTON_Y = 5;
	private static final Color INACTIVE_COLOR = new Color(60, 60, 60);
	private static final Color ACTIVE_COLOR = new Color(40, 110, 40);

	@Inject
	private Client client;

	@Inject
	private BankGroupedView bankGroupedView;

	private Widget buttonRect;

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

	public void onBankWidgetClosed()
	{
		buttonRect = null;
		bankGroupedView.reset();
	}

	private void injectButton(Widget parent)
	{
		if (buttonRect != null)
		{
			return;
		}

		Widget rect = parent.createChild(-1, WidgetType.RECTANGLE);
		rect.setOriginalX(BUTTON_X);
		rect.setOriginalY(BUTTON_Y);
		rect.setOriginalWidth(BUTTON_SIZE);
		rect.setOriginalHeight(BUTTON_SIZE);
		rect.setFilled(true);
		rect.setOpacity(60);
		rect.setName("Required materials filter");
		rect.setHasListener(true);
		rect.setOnClickListener((JavaScriptCallback) ev -> toggleFilter());

		Widget label = parent.createChild(-1, WidgetType.TEXT);
		label.setOriginalX(BUTTON_X);
		label.setOriginalY(BUTTON_Y);
		label.setOriginalWidth(BUTTON_SIZE);
		label.setOriginalHeight(BUTTON_SIZE);
		label.setText("F");
		label.setFontId(FontID.PLAIN_11);
		label.setTextColor(0xFFFFFF);
		label.setXTextAlignment(1);
		label.setYTextAlignment(1);

		applyButtonColor(rect);
		rect.revalidate();
		label.revalidate();

		this.buttonRect = rect;
	}

	private void toggleFilter()
	{
		bankGroupedView.setActive(!bankGroupedView.isActive());
		if (buttonRect != null)
		{
			applyButtonColor(buttonRect);
		}
	}

	private void applyButtonColor(Widget rect)
	{
		rect.setTextColor((bankGroupedView.isActive() ? ACTIVE_COLOR : INACTIVE_COLOR).getRGB() & 0xFFFFFF);
	}
}
