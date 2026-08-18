package com.requiredmaterials;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Required Materials",
	description = "Tracks materials needed for Sailing ship upgrades and Construction furniture, and highlights them in your bank",
	tags = {"sailing", "construction", "ship", "bank", "materials"}
)
public class RequiredMaterialsPlugin extends Plugin
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

	@Inject
	private SkillIconManager skillIconManager;

	private RequiredMaterialsPanel panel;
	private NavigationButton navButton;
	private boolean shipCustomisationOpen;
	private boolean furnitureCreationOpen;
	private String lastKnownGuideV2Title;
	private String lastKnownGuideV1Title;
	private String pendingContinuationPartName;

	@Provides
	RequiredMaterialsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RequiredMaterialsConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new RequiredMaterialsPanel(materialsManager, client, clientThread, skillIconManager);

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
			.tooltip("Required Materials")
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

		if (!shipCustomisationOpen && !isTrackedSkillGuideOpen())
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
		log.debug("Required materials: now tracking requirements for '{}'", result.getPartName());

		materialsManager.setSkill(result.getPartName(), currentSkillSource());

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
	private static final List<String> TRACKED_SKILLS = Arrays.asList("Sailing", "Construction");

	private String stripBoatTypeSuffix(String partName)
	{
		return BOAT_TYPE_SUFFIX.matcher(partName).replaceFirst("").trim();
	}

	/**
	 * Skill level requirements are never in the chat message. Each guide row shows the
	 * required primary skill level as a separate plain-number widget to the left of the entry
	 * (the only requirement for some rows, e.g. "Wooden cargo hold" has no other skill req at
	 * all), and optionally a "<Part name><br>Requires: <col=...>Level N Skill</col>, ..." widget
	 * for any additional skills (e.g. Construction alongside Sailing). The two are siblings
	 * correlated by row (same Y position), not by any parent/child or array-adjacency
	 * relationship.
	 */
	private List<String> findLevelRequirements(String partName)
	{
		for (int groupId : new int[] {InterfaceID.SKILL_GUIDE, InterfaceID.SKILL_GUIDE_V2})
		{
			String primarySkill = groupId == InterfaceID.SKILL_GUIDE_V2
				? matchingTrackedSkill(lastKnownGuideV2Title, true)
				: matchingTrackedSkill(lastKnownGuideV1Title, false);
			if (primarySkill == null)
			{
				continue;
			}

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
					List<String> result = extractRequirements(nameWidget, partName, numberWidgets, primarySkill);
					if (result != null)
					{
						return result;
					}
				}
			}
		}
		return new ArrayList<>();
	}

	private static final Pattern LEVEL_TEXT_PATTERN = Pattern.compile("^Level (\\d+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern MATERIAL_LINE_PATTERN = Pattern.compile("^(.+?):\\s*(\\d+)$");
	private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");

	/**
	 * Unlike ship upgrades, Furniture Creation gives everything needed for a click in one shot -
	 * the clicked widget's own text (item name, required level, and a "Mat: Qty<br>..." materials
	 * block, up to 3 slots padded with empty or flavour-text ones when unused) - so there's no
	 * chat message to parse or multi-line list to reassemble.
	 */
	private void trackFurnitureItem(MenuOptionClicked event)
	{
		Widget itemWidget = event.getWidget();
		if (itemWidget == null)
		{
			return;
		}

		String partName = TAG_PATTERN.matcher(event.getMenuTarget()).replaceAll("").trim();

		List<Widget> texts = new ArrayList<>();
		collectAllText(itemWidget, texts);

		String levelText = null;
		String materialsText = null;
		for (Widget w : texts)
		{
			String t = w.getText().trim();
			if (LEVEL_TEXT_PATTERN.matcher(t).matches())
			{
				levelText = t;
			}
			else if (t.contains("<br>"))
			{
				materialsText = t;
			}
		}

		if (materialsText == null)
		{
			return;
		}

		Map<String, Integer> materials = new LinkedHashMap<>();
		for (String segment : materialsText.split("<br>"))
		{
			// Unused material slots are padded with either nothing or flavour text describing
			// the built item - neither matches "Name: Qty", so they're naturally skipped here.
			Matcher m = MATERIAL_LINE_PATTERN.matcher(segment.trim());
			if (m.matches())
			{
				materials.put(m.group(1).trim(), Integer.parseInt(m.group(2)));
			}
		}

		if (materials.isEmpty())
		{
			return;
		}

		List<String> levelRequirements = new ArrayList<>();
		if (levelText != null)
		{
			levelRequirements.add(levelText + " Construction");
		}

		materialsManager.trackDirect(partName, materials, levelRequirements);
		materialsManager.setSkill(partName, "Construction");
		if (panel != null)
		{
			panel.refresh();
		}
	}

	private void collectAllText(Widget widget, List<Widget> out)
	{
		if (widget == null)
		{
			return;
		}

		if (widget.getText() != null && !widget.getText().isEmpty())
		{
			out.add(widget);
		}

		for (Widget[] childArray : new Widget[][] {widget.getChildren(), widget.getDynamicChildren(), widget.getStaticChildren()})
		{
			if (childArray == null)
			{
				continue;
			}
			for (Widget child : childArray)
			{
				if (child != null && child != widget)
				{
					collectAllText(child, out);
				}
			}
		}
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
	private List<String> extractRequirements(Widget nameWidget, String partName, List<Widget> numberWidgets, String primarySkill)
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
			result.add("Level " + sameRowNumber.getText().trim() + " " + primarySkill);
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
	private boolean isTrackedSkillGuideOpen()
	{
		refreshCachedGuideTitles();
		return matchingTrackedSkill(lastKnownGuideV2Title, true) != null
			|| matchingTrackedSkill(lastKnownGuideV1Title, false) != null;
	}

	private String matchingTrackedSkill(String title, boolean prefixMatch)
	{
		if (title == null)
		{
			return null;
		}
		for (String skill : TRACKED_SKILLS)
		{
			if (prefixMatch ? title.startsWith(skill) : skill.equals(title))
			{
				return skill;
			}
		}
		return null;
	}

	/**
	 * Which skill a just-tracked chat message came from: whichever guide is currently open, if
	 * any (guide titles are refreshed as a side effect of {@link #isTrackedSkillGuideOpen()},
	 * already called earlier in the same event) - otherwise SAILING_CUSTOMISATION must be what
	 * triggered it, since that's a Sailing-only interface.
	 */
	private String currentSkillSource()
	{
		String skill = matchingTrackedSkill(lastKnownGuideV2Title, true);
		if (skill == null)
		{
			skill = matchingTrackedSkill(lastKnownGuideV1Title, false);
		}
		return skill != null ? skill : "Sailing";
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
		else if (event.getGroupId() == InterfaceID.POH_FURNITURE_CREATION)
		{
			furnitureCreationOpen = true;
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
		else if (event.getGroupId() == InterfaceID.POH_FURNITURE_CREATION)
		{
			furnitureCreationOpen = false;
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

		if (furnitureCreationOpen && "Build".equals(event.getMenuOption()))
		{
			trackFurnitureItem(event);
		}
	}
}
