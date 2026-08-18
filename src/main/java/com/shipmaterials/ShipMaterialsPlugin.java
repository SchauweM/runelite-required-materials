package com.shipmaterials;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Ship Materials",
	description = "Tracks materials needed for sailing ship upgrades and highlights them in your bank",
	tags = {"sailing", "ship", "bank", "materials"}
)
public class ShipMaterialsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private MaterialsManager materialsManager;

	@Inject
	private BankButtonManager bankButtonManager;

	@Inject
	private BankGroupedView bankGroupedView;

	@Inject
	private ConfigManager configManager;

	private ShipMaterialsPanel panel;
	private NavigationButton navButton;
	private boolean shipCustomisationOpen;
	private String lastKnownGuideV2Title;
	private String lastKnownGuideV1Title;
	private String pendingContinuationPartName;

	@Provides
	ShipMaterialsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ShipMaterialsConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new ShipMaterialsPanel(materialsManager, client, clientThread);

		// Loading resolves item names to ids via the client's item definitions, which can
		// only be read on the client thread - startUp() itself isn't guaranteed to be on it
		// (e.g. when the plugin is toggled on from the config UI), so defer via ClientThread
		// rather than crashing; invoke() runs synchronously if we're already on it.
		clientThread.invoke(() ->
		{
			materialsManager.load();
			panel.refresh();
		});

		navButton = NavigationButton.builder()
			.tooltip("Ship Materials")
			.icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		if (!shipCustomisationOpen && !isSailingSkillGuideOpen())
		{
			return;
		}

		String message = event.getMessage();
		if (message == null)
		{
			return;
		}

		MaterialsManager.ParseResult result;
		if (message.contains(":"))
		{
			result = materialsManager.tryParseAndTrack(message);
		}
		else if (pendingContinuationPartName != null)
		{
			result = materialsManager.tryAppendContinuation(pendingContinuationPartName, message);
		}
		else
		{
			return;
		}

		if (result == null)
		{
			pendingContinuationPartName = null;
			return;
		}

		pendingContinuationPartName = result.isContinuationExpected() ? result.getPartName() : null;
		log.debug("Ship materials: now tracking requirements for '{}'", result.getPartName());

		List<String> levelRequirements = findLevelRequirements(result.getPartName());
		if (!levelRequirements.isEmpty())
		{
			materialsManager.setLevelRequirements(result.getPartName(), levelRequirements);
		}

		if (panel != null)
		{
			panel.refresh();
		}
	}

	private static final int GUIDE_TOP_LEVEL_SLOT_SCAN = 40;
	private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");
	private static final Pattern BOAT_TYPE_SUFFIX = Pattern.compile("\\s*\\([^)]*\\)$");

	private String stripBoatTypeSuffix(String partName)
	{
		return BOAT_TYPE_SUFFIX.matcher(partName).replaceFirst("").trim();
	}

	/**
	 * Skill level requirements are never in the chat message. Each guide row shows the
	 * required Sailing level as a separate plain-number widget to the left of the entry (the
	 * only requirement for some rows, e.g. "Wooden cargo hold" has no other skill req at all),
	 * and optionally a "<Part name><br>Requires: <col=...>Level N Skill</col>, ..." widget for
	 * any additional skills (e.g. Construction). The two are siblings correlated by row (same
	 * Y position), not by any parent/child or array-adjacency relationship.
	 */
	private List<String> findLevelRequirements(String partName)
	{
		for (int groupId : new int[] {InterfaceID.SKILL_GUIDE, InterfaceID.SKILL_GUIDE_V2})
		{
			for (int childId = 0; childId <= GUIDE_TOP_LEVEL_SLOT_SCAN; childId++)
			{
				Widget w = client.getWidget(groupId, childId);
				if (w == null)
				{
					continue;
				}

				List<Widget> numberWidgets = new ArrayList<>();
				List<Widget> nameWidgets = new ArrayList<>();
				collectRowWidgets(w, numberWidgets, nameWidgets);

				for (Widget nameWidget : nameWidgets)
				{
					List<String> result = extractRequirements(nameWidget, partName, numberWidgets);
					if (result != null)
					{
						return result;
					}
				}
			}
		}
		return new ArrayList<>();
	}

	private void collectRowWidgets(Widget widget, List<Widget> numberWidgets, List<Widget> nameWidgets)
	{
		if (widget == null)
		{
			return;
		}

		String text = widget.getText();
		if (text != null && !text.isEmpty())
		{
			if (DIGITS_ONLY.matcher(text.trim()).matches())
			{
				numberWidgets.add(widget);
			}
			else
			{
				nameWidgets.add(widget);
			}
		}

		for (Widget[] childArray : new Widget[][] {widget.getChildren(), widget.getDynamicChildren()})
		{
			if (childArray == null)
			{
				continue;
			}
			for (Widget child : childArray)
			{
				if (child != null && child != widget)
				{
					collectRowWidgets(child, numberWidgets, nameWidgets);
				}
			}
		}
	}

	/**
	 * @return the requirement list if nameWidget is the row for partName, else null.
	 */
	private List<String> extractRequirements(Widget nameWidget, String partName, List<Widget> numberWidgets)
	{
		String text = nameWidget.getText();
		int brIdx = text.indexOf("<br>");
		String bareName = (brIdx != -1 ? text.substring(0, brIdx) : text).trim();

		// Some parts (e.g. "Wooden mast and linen sails") come as several boat-type-specific
		// chat messages - "Wooden mast and linen sails (raft)", "(skiff)", "(sloop)" - but the
		// guide only has one row for all of them, labelled without the suffix.
		if (!bareName.equalsIgnoreCase(partName) && !bareName.equalsIgnoreCase(stripBoatTypeSuffix(partName)))
		{
			return null;
		}

		List<String> result = new ArrayList<>();

		Widget sameRowNumber = findWidgetOnSameRow(nameWidget, numberWidgets);
		if (sameRowNumber != null)
		{
			result.add("Level " + sameRowNumber.getText().trim() + " Sailing");
		}

		int requiresIdx = text.indexOf("Requires:");
		if (requiresIdx != -1)
		{
			String rawRequirements = text.substring(requiresIdx + "Requires:".length());
			String stripped = rawRequirements.replaceAll("<[^>]*>", "").trim();
			for (String piece : stripped.split(","))
			{
				String p = piece.trim();
				if (!p.isEmpty())
				{
					result.add(p);
				}
			}
		}

		return result;
	}

	private Widget findWidgetOnSameRow(Widget target, List<Widget> candidates)
	{
		int targetY = target.getOriginalY();
		Widget best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (Widget candidate : candidates)
		{
			int distance = Math.abs(candidate.getOriginalY() - targetY);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = candidate;
			}
		}
		// A generous tolerance since the number sits a little higher/lower than the label text.
		return bestDistance <= 12 ? best : null;
	}

	/**
	 * Both skill guide interfaces are shared by every skill, dynamically populated with
	 * whichever skill is currently selected - so unlike SAILING_CUSTOMISATION we can't gate on
	 * either just being open, and check their title widgets instead. We check live rather than
	 * caching an "open" flag, since the user can switch skills within an already-open guide.
	 */
	private boolean isSailingSkillGuideOpen()
	{
		refreshCachedGuideTitles();
		return (lastKnownGuideV2Title != null && lastKnownGuideV2Title.startsWith("Sailing"))
			|| "Sailing".equals(lastKnownGuideV1Title);
	}

	private void refreshCachedGuideTitles()
	{
		// SkillGuideV2's FRAME is a static draggable/resizable header container whose own
		// text is always empty - the actual title is a dynamically-created child widget
		// added at runtime, only reachable by walking its dynamic children, not via a direct
		// groupId/childId lookup (which is why a direct read here always came back blank).
		String dynamicV2Text = findNonEmptyDynamicChildText(
			client.getWidget(InterfaceID.SKILL_GUIDE_V2, InterfaceID.SkillGuideV2.FRAME & 0xFFFF));
		if (dynamicV2Text != null)
		{
			lastKnownGuideV2Title = dynamicV2Text;
		}

		Widget titleV1 = client.getWidget(InterfaceID.SKILL_GUIDE, InterfaceID.SkillGuide.TITLE & 0xFFFF);
		if (titleV1 != null && titleV1.getText() != null && !titleV1.getText().isEmpty())
		{
			lastKnownGuideV1Title = titleV1.getText();
		}
	}

	private String findNonEmptyDynamicChildText(Widget parent)
	{
		if (parent == null)
		{
			return null;
		}
		Widget[] children = parent.getDynamicChildren();
		if (children == null)
		{
			return null;
		}
		for (Widget child : children)
		{
			if (child != null && child.getText() != null && !child.getText().isEmpty())
			{
				return child.getText();
			}
		}
		return null;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankButtonManager.onBankWidgetLoaded();
		}
		else if (event.getGroupId() == InterfaceID.SAILING_CUSTOMISATION)
		{
			shipCustomisationOpen = true;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankButtonManager.onBankWidgetClosed();
		}
		else if (event.getGroupId() == InterfaceID.SAILING_CUSTOMISATION)
		{
			shipCustomisationOpen = false;
		}
		else if (event.getGroupId() == InterfaceID.SKILL_GUIDE_V2)
		{
			lastKnownGuideV2Title = null;
		}
		else if (event.getGroupId() == InterfaceID.SKILL_GUIDE)
		{
			lastKnownGuideV1Title = null;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK && panel != null)
		{
			panel.refresh();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Real skill levels aren't populated yet when startUp()'s initial refresh() runs (they
		// arrive via a separate packet shortly after login) - confirmed by logging: sailing level
		// read as 0 at the LOGGED_IN transition itself, only becoming correct ~20s later once
		// something else happened to refresh the panel. Refreshing on every stat sync/change
		// catches both that initial sync and any later real level-up while tracking something.
		if (panel != null)
		{
			panel.refresh();
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		bankGroupedView.onScriptPreFired(event.getScriptId());
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		bankGroupedView.onScriptPostFired(event.getScriptId());
	}

	@Subscribe(priority = -1)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		bankGroupedView.onMenuOptionClicked(event);
	}
}
