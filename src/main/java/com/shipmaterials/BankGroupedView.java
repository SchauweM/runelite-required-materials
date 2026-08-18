package com.shipmaterials;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.bank.BankSearch;
import net.runelite.client.util.QuantityFormatter;

/**
 * Reorganises the bank's item grid to show tracked ship-material requirements grouped by
 * part, at the top of the bank, exactly like Quest Helper's per-quest bank tab - just grouped
 * by tracked requirement (part) instead of by quest step. Adapted from Quest Helper's
 * QuestBankTab/QuestBankTabInterface (Zoinkwiz/quest-helper, BSD-2-Clause), which reuses the
 * bank's own real item-slot widgets as generic positionable slots rather than drawing a
 * separate overlay: the trick is hooking net.runelite.api.ScriptID#BANKMAIN_FINISHBUILDING,
 * the same script the game itself uses to build the bank grid every time it changes, and
 * overwriting its output afterward when the grouped view is active. When it's not active we
 * simply don't touch anything post-build, so the real bank view is untouched.
 */
@Slf4j
@Singleton
public class BankGroupedView
{
	private static final int ITEMS_PER_ROW = 8;
	private static final int ITEM_VERTICAL_SPACING = 36;
	private static final int ITEM_HORIZONTAL_SPACING = 48;
	private static final int ITEM_ROW_START = 51;
	private static final int LINE_VERTICAL_SPACING = 5;
	private static final int LINE_HEIGHT = 2;
	private static final int TEXT_HEIGHT = 15;
	private static final int BANK_ITEM_WIDTH = 36;
	private static final int BANK_ITEM_HEIGHT = 32;
	private static final int SECTION_DIVIDER_SPRITE = 897;
	private static final int TICK_SPRITE_ID = 1217;
	private static final int CROSS_SPRITE_ID = 1216;
	private static final String OTHER_ITEMS_SECTION = "Other items";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private BankSearch bankSearch;

	@Inject
	private MaterialsManager materialsManager;

	private final List<Widget> addedWidgets = new ArrayList<>();
	private int originalContainerChildren = -1;
	private int currentWidgetToUse = 0;
	private boolean active;

	public boolean isActive()
	{
		return active;
	}

	public void setActive(boolean active)
	{
		this.active = active;
		bankSearch.reset(true);
	}

	/**
	 * Called on bank close so stale widget/child-count bookkeeping doesn't leak into the
	 * next bank session.
	 */
	public void reset()
	{
		addedWidgets.clear();
		originalContainerChildren = -1;
		currentWidgetToUse = 0;
	}

	public void onScriptPreFired(int scriptId)
	{
		if (scriptId == ScriptID.BANKMAIN_FINISHBUILDING)
		{
			resetWidgetSizes();
		}
	}

	public void onScriptPostFired(int scriptId)
	{
		if (scriptId == ScriptID.BANKMAIN_SEARCHING)
		{
			if (active)
			{
				client.getIntStack()[client.getIntStackSize() - 1] = 1;
			}
			return;
		}

		if (scriptId != ScriptID.BANKMAIN_FINISHBUILDING)
		{
			return;
		}

		removeAddedWidgets();

		if (!active)
		{
			return;
		}

		Widget itemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (itemContainer == null)
		{
			return;
		}

		Widget[] children = itemContainer.getChildren();
		if (children != null && originalContainerChildren == -1)
		{
			originalContainerChildren = children.length;
		}

		Widget[] containerChildren = itemContainer.getDynamicChildren();
		clientThread.invokeAtTickEnd(() -> layoutGroupedView(itemContainer, containerChildren));
	}

	/**
	 * Fixes up the withdraw menu's slot index: our relocated widgets keep whatever slot
	 * index the bank originally built them with, which almost never matches the slot the
	 * item we drew into them actually lives in.
	 */
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!active || event.getParam1() != InterfaceID.Bankmain.ITEMS)
		{
			return;
		}

		MenuEntry menu = event.getMenuEntry();
		Widget w = menu.getWidget();
		if (w == null || w.getItemId() <= -1)
		{
			return;
		}

		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return;
		}

		int idx = bank.find(w.getItemId());
		if (idx > -1 && menu.getParam0() != idx)
		{
			menu.setParam0(idx);
		}
	}

	private void resetWidgetSizes()
	{
		Widget w = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (w == null || w.getChildren() == null)
		{
			return;
		}

		for (Widget c : w.getChildren())
		{
			if (c.getOriginalHeight() < BANK_ITEM_HEIGHT)
			{
				break;
			}

			if (c.getOriginalWidth() != BANK_ITEM_WIDTH || c.getOriginalHeight() != BANK_ITEM_HEIGHT)
			{
				c.setOriginalWidth(BANK_ITEM_WIDTH);
				c.setOriginalHeight(BANK_ITEM_HEIGHT);
				c.revalidate();
			}
		}
	}

	private void removeAddedWidgets()
	{
		if (originalContainerChildren == -1 || addedWidgets.isEmpty())
		{
			return;
		}

		Widget parent = addedWidgets.get(0).getParent();
		if (parent == null || parent.getChildren() == null)
		{
			return;
		}

		parent.setChildren(Arrays.copyOf(parent.getChildren(), originalContainerChildren));
		parent.revalidate();
		addedWidgets.clear();
	}

	private void layoutGroupedView(Widget itemContainer, Widget[] containerChildren)
	{
		currentWidgetToUse = 0;
		hideBankWidgets(itemContainer, containerChildren);

		List<TrackedRequirement> sections = new ArrayList<>(materialsManager.getRequirements());
		sections.add(buildOtherItemsSection(sections));

		int totalSectionsHeight = 0;
		List<BankText> bankItemTexts = new ArrayList<>();
		for (TrackedRequirement section : sections)
		{
			totalSectionsHeight = addSection(itemContainer, section, totalSectionsHeight, bankItemTexts);
		}

		for (BankText bankText : bankItemTexts)
		{
			addedWidgets.add(createText(itemContainer, bankText.text, Color.WHITE.getRGB(),
				ITEM_HORIZONTAL_SPACING, TEXT_HEIGHT - 3, bankText.x, bankText.y));

			if (bankText.spriteId != -1)
			{
				addedWidgets.add(createIcon(itemContainer, bankText.spriteId, bankText.spriteX, bankText.spriteY));
			}
		}

		Widget bankItemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (bankItemContainer == null)
		{
			return;
		}

		int itemContainerHeight = bankItemContainer.getHeight();
		bankItemContainer.setScrollHeight(Math.max(totalSectionsHeight, itemContainerHeight));
		int scroll = bankItemContainer.getScrollY();
		clientThread.invokeLater(() -> client.runScript(ScriptID.UPDATE_SCROLLBAR,
			InterfaceID.Bankmain.SCROLLBAR, InterfaceID.Bankmain.ITEMS, scroll));
	}

	private TrackedRequirement buildOtherItemsSection(List<TrackedRequirement> trackedSections)
	{
		Set<Integer> usedIds = new LinkedHashSet<>();
		for (TrackedRequirement section : trackedSections)
		{
			for (RequiredMaterial material : section.getMaterials())
			{
				if (material.getItemId() != null)
				{
					usedIds.add(material.getItemId());
				}
			}
		}

		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		List<RequiredMaterial> otherItems = new ArrayList<>();
		if (bank != null)
		{
			Set<Integer> seen = new LinkedHashSet<>();
			for (Item item : bank.getItems())
			{
				if (item.getId() < 0 || !seen.add(item.getId()) || usedIds.contains(item.getId()))
				{
					continue;
				}
				ItemComposition def = client.getItemDefinition(item.getId());
				RequiredMaterial other = new RequiredMaterial(def.getName(), -1);
				other.setItemId(item.getId());
				otherItems.add(other);
			}
		}

		return new TrackedRequirement(OTHER_ITEMS_SECTION, otherItems);
	}

	private void hideBankWidgets(Widget itemContainer, Widget[] containerChildren)
	{
		for (int i = 0; i < containerChildren.length; ++i)
		{
			Widget widget = itemContainer.getChild(i);
			if (widget == null)
			{
				continue;
			}

			if (!widget.isSelfHidden() && widget.getItemId() > -1
				|| widget.getSpriteId() == SECTION_DIVIDER_SPRITE
				|| widget.getText().contains("Tab"))
			{
				widget.setHidden(true);
			}
		}
	}

	private int addSection(Widget itemContainer, TrackedRequirement section, int totalSectionsHeight, List<BankText> bankItemTexts)
	{
		if (section == null || section.getMaterials().isEmpty())
		{
			return totalSectionsHeight;
		}

		int newHeight = addSectionHeader(itemContainer, section.getPartName(), totalSectionsHeight);
		return createItemsGrid(section.getMaterials(), newHeight, bankItemTexts);
	}

	private int createItemsGrid(List<RequiredMaterial> materials, int totalSectionsHeight, List<BankText> bankItemTexts)
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return totalSectionsHeight;
		}

		Widget bankItemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (bankItemContainer == null)
		{
			return totalSectionsHeight;
		}

		int totalItemsAdded = 0;
		for (RequiredMaterial material : materials)
		{
			Integer itemId = material.getItemId();
			if (itemId == null)
			{
				continue;
			}

			Widget c = bankItemContainer.getChild(currentWidgetToUse);
			if (c == null)
			{
				return totalSectionsHeight;
			}

			drawItem(c, itemId, bank, material);
			placeItem(c, totalItemsAdded, totalSectionsHeight);
			if (material.getQuantity() > 0)
			{
				makeBankText(c.getItemQuantity(), material.getQuantity(), c.getOriginalX(), c.getOriginalY(), bankItemTexts);
			}

			currentWidgetToUse++;
			totalItemsAdded++;
		}

		int newHeight = totalSectionsHeight + (totalItemsAdded / ITEMS_PER_ROW) * ITEM_VERTICAL_SPACING;
		return totalItemsAdded % ITEMS_PER_ROW != 0 ? newHeight + ITEM_VERTICAL_SPACING : newHeight;
	}

	private void drawItem(Widget c, int itemId, ItemContainer bank, RequiredMaterial material)
	{
		int qty = bank.count(itemId);
		ItemComposition def = client.getItemDefinition(itemId);

		c.setItemId(itemId);
		c.setItemQuantity(qty);
		c.setItemQuantityMode(ItemQuantityMode.ALWAYS);
		c.setDragDeadTime(1000);
		c.setName("<col=ff9040>" + def.getName() + "</col>");
		c.clearActions();

		if (qty == 0)
		{
			c.setOpacity(120);
			c.setItemQuantity(0);
		}
		else
		{
			int quantityType = client.getVarbitValue(VarbitID.BANK_QUANTITY_TYPE);
			int requestQty = client.getVarbitValue(VarbitID.BANK_REQUESTEDQUANTITY);
			String suffix;
			switch (quantityType)
			{
				case 1:
					suffix = "5";
					break;
				case 2:
					suffix = "10";
					break;
				case 3:
					suffix = Integer.toString(Math.max(1, requestQty));
					break;
				case 4:
					suffix = "All";
					break;
				default:
					suffix = "1";
					break;
			}
			c.setAction(0, "Withdraw-" + suffix);
			if (quantityType != 0)
			{
				c.setAction(1, "Withdraw-1");
			}
			c.setAction(2, "Withdraw-5");
			c.setAction(3, "Withdraw-10");
			if (requestQty > 0)
			{
				c.setAction(4, "Withdraw-" + requestQty);
			}
			c.setAction(5, "Withdraw-X");
			c.setAction(6, "Withdraw-All");
			c.setAction(7, "Withdraw-All-but-1");
			c.setAction(9, "Examine");
			c.setOpacity(0);
		}

		c.setOnDragListener((JavaScriptCallback) ev -> {});
		c.setOnDragCompleteListener((JavaScriptCallback) ev -> {});
		c.setHidden(false);
		c.revalidate();
	}

	private void makeBankText(int currentQuantity, int goalQuantity, int baseX, int baseY, List<BankText> bankItemTexts)
	{
		String quantityString = QuantityFormatter.quantityToStackSize(goalQuantity);
		int requirementLength = (int) Math.round(quantityString.length() * 5.5);
		int extraLength = QuantityFormatter.quantityToStackSize(currentQuantity).length() * 6;

		int xPos = baseX + 2 + extraLength;
		int yPos = baseY - 1;
		if (extraLength + requirementLength > 20)
		{
			xPos = baseX;
			yPos = baseY + 9;
		}

		boolean hasEnough = currentQuantity >= goalQuantity;
		int spritePosX = xPos + requirementLength + 10;
		int spritePosY = yPos;
		if (yPos != baseY - 1)
		{
			spritePosX = baseX + 2 + extraLength;
			spritePosY = baseY - 1;
		}

		bankItemTexts.add(new BankText("/ " + quantityString, xPos, yPos,
			hasEnough ? TICK_SPRITE_ID : CROSS_SPRITE_ID, spritePosX, spritePosY));
	}

	private int addSectionHeader(Widget itemContainer, String title, int totalSectionsHeight)
	{
		addedWidgets.add(createGraphic(itemContainer, SECTION_DIVIDER_SPRITE, ITEM_ROW_START, totalSectionsHeight));
		addedWidgets.add(createText(itemContainer, title, new Color(228, 216, 162).getRGB(),
			(ITEMS_PER_ROW * ITEM_HORIZONTAL_SPACING) + ITEM_ROW_START, TEXT_HEIGHT,
			ITEM_ROW_START, totalSectionsHeight + LINE_VERTICAL_SPACING));
		return totalSectionsHeight + LINE_VERTICAL_SPACING + TEXT_HEIGHT;
	}

	private void placeItem(Widget widget, int totalItemsAdded, int totalSectionsHeight)
	{
		int adjYOffset = totalSectionsHeight + (totalItemsAdded / ITEMS_PER_ROW) * ITEM_VERTICAL_SPACING;
		int adjXOffset = (totalItemsAdded % ITEMS_PER_ROW) * ITEM_HORIZONTAL_SPACING + ITEM_ROW_START;

		if (widget.getOriginalY() != adjYOffset)
		{
			widget.setOriginalY(adjYOffset);
			widget.revalidate();
		}
		if (widget.getOriginalX() != adjXOffset)
		{
			widget.setOriginalX(adjXOffset);
			widget.revalidate();
		}
	}

	private Widget createGraphic(Widget container, int spriteId, int x, int y)
	{
		Widget widget = container.createChild(-1, WidgetType.GRAPHIC);
		widget.setOriginalWidth(ITEMS_PER_ROW * ITEM_HORIZONTAL_SPACING);
		widget.setOriginalHeight(LINE_HEIGHT);
		widget.setOriginalX(x);
		widget.setOriginalY(y);
		widget.setSpriteId(spriteId);
		widget.revalidate();
		return widget;
	}

	private Widget createText(Widget container, String text, int color, int width, int height, int x, int y)
	{
		Widget widget = container.createChild(-1, WidgetType.TEXT);
		widget.setOriginalWidth(width);
		widget.setOriginalHeight(height);
		widget.setOriginalX(x);
		widget.setOriginalY(y);
		widget.setText(text);
		widget.setFontId(FontID.PLAIN_11);
		widget.setTextColor(color);
		widget.setTextShadowed(true);
		widget.revalidate();
		return widget;
	}

	private Widget createIcon(Widget container, int spriteId, int x, int y)
	{
		Widget widget = container.createChild(-1, WidgetType.GRAPHIC);
		widget.setOriginalWidth(10);
		widget.setOriginalHeight(10);
		widget.setOriginalX(x);
		widget.setOriginalY(y);
		widget.setSpriteId(spriteId);
		widget.revalidate();
		return widget;
	}
}
