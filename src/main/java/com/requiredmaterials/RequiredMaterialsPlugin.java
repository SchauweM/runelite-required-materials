package com.requiredmaterials;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
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
	description = "Tracks the materials and levels you still need for Sailing and Construction builds, and groups them in your bank",
	tags = {"sailing", "construction", "materials", "bank", "skilling", "shipwright"}
)
public class RequiredMaterialsPlugin extends Plugin
{
	private static final List<String> TRACKED_SKILLS = Arrays.asList("Sailing", "Construction");
	private static final Pattern PART_NAME_PATTERN = Pattern.compile("^(.*?):");
	private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");
	// "Check Materials" labels the part "Camphor hull materials"; every other trigger just says
	// "Camphor hull", which is also the wiki page name.
	private static final Pattern MATERIALS_SUFFIX = Pattern.compile("(?i)\\s+materials$");
	private static final int HOUSE_SCAN_DELAY_TICKS = 6;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private MaterialsManager materialsManager;

	@Inject
	private WikiRecipeService wikiRecipeService;

	@Inject
	private BankSnapshot bankSnapshot;

	@Inject
	private HeldItems heldItems;

	@Inject
	private HouseContents houseContents;

	@Inject
	private RequiredMaterialsConfig config;

	@Inject
	private BankButtonManager bankButtonManager;

	@Inject
	private BankGroupedView bankGroupedView;

	@Inject
	private SkillIconManager skillIconManager;

	private final ChatMaterialsParser chatMaterialsParser = new ChatMaterialsParser();
	private final GuideLevelReader guideLevelReader = new GuideLevelReader();

	private RequiredMaterialsPanel panel;
	private NavigationButton navButton;
	private boolean shipCustomisationOpen;
	private boolean furnitureCreationOpen;
	private String lastKnownGuideV2Title;
	private String lastKnownGuideV1Title;
	private String pendingContinuationPartName;
	private int ticksSinceSceneLoad = -1;

	@Provides
	RequiredMaterialsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RequiredMaterialsConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new RequiredMaterialsPanel(materialsManager, client, clientThread, skillIconManager, heldItems, houseContents);

		// load() resolves item ids, which must happen on the client thread - startUp() isn't
		// guaranteed to be on it (e.g. toggled from the config UI).
		houseContents.onResolved(() -> clientThread.invoke(() ->
		{
			if (panel != null)
			{
				panel.refresh();
			}
		}));

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

		Matcher matcher = PART_NAME_PATTERN.matcher(message);
		if (matcher.find())
		{
			String partName = MATERIALS_SUFFIX.matcher(
				TAG_PATTERN.matcher(matcher.group(1)).replaceAll("").replace('\u00A0', ' ').trim()
			).replaceFirst("");
			if (partName.isEmpty())
			{
				return;
			}

			// Accumulated only as a fallback for Construction activities the wiki has no
			// {{Recipe}} for (Mahogany Homes, Birdhouses, STASH units); the wiki stays primary.
			String materialsText = message.substring(matcher.end()).trim();
			pendingContinuationPartName = chatMaterialsParser.accumulate(partName, materialsText) ? partName : null;

			trackFromWiki(partName, currentSkillSource(), false);
		}
		else if (pendingContinuationPartName != null)
		{
			pendingContinuationPartName = chatMaterialsParser.appendContinuation(pendingContinuationPartName, message)
				? pendingContinuationPartName : null;
		}
	}

	@Subscribe(priority = -1)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		bankGroupedView.onMenuOptionClicked(event);

		if (!"Build".equals(event.getMenuOption()))
		{
			return;
		}

		String partName = TAG_PATTERN.matcher(event.getMenuTarget()).replaceAll("").trim();
		if (furnitureCreationOpen)
		{
			trackFromWiki(partName, "Construction", true);
		}
		else if (shipCustomisationOpen)
		{
			trackFromWiki(partName, "Sailing", true);
		}
	}

	/**
	 * Looks the part up on the wiki, falling back to chat/guide-widget data for Construction
	 * items with no recipe there. The wiki callback runs off the client thread, so tracking
	 * hops back onto it.
	 */
	private void trackFromWiki(String partName, String skill, boolean buildClick)
	{
		List<String> pageNames = wikiPageCandidates(partName, skill);
		String variant = wikiVariant(partName, skill);

		wikiRecipeService.fetchRecipes(pageNames, recipes ->
		{
			WikiRecipeService.Recipe recipe = selectRecipe(recipes, variant);

			clientThread.invoke(() ->
			{
				if (buildClick && recipe != null
					&& materialsManager.canBuild(recipe.getMaterials(), recipe.getLevelRequirements())
					&& handleBuiltWhatTheyHad(partName))
				{
					return;
				}

				boolean tracked;
				if (recipe != null)
				{
					materialsManager.track(partName, recipe.getMaterials(), recipe.getLevelRequirements(), recipe.getPrerequisites(), !buildClick);
					tracked = true;
				}
				else
				{
					tracked = "Construction".equals(skill) && trackFromChatFallback(partName, !buildClick);
				}

				if (!tracked)
				{
					log.warn("Required materials: could not resolve requirements for '{}' (tried {})", partName, pageNames);
					return;
				}

				materialsManager.setSkill(partName, skill);
				if (panel != null)
				{
					panel.onTracked(skill);
				}
			});
		});
	}

	/**
	 * Clicking Build with everything already to hand means it's built, so there's nothing left to
	 * collect for it. Clicking Build isn't proof the build finished - it can still be cancelled -
	 * but having every material and level is the closest signal the client gives.
	 *
	 * Clearing wins over tracking when both settings are on: removing it and then adding it
	 * straight back would leave it on the list anyway, just reordered.
	 *
	 * @return true if this build shouldn't be tracked.
	 */
	private boolean handleBuiltWhatTheyHad(String partName)
	{
		if (config.clearWhenBuilt())
		{
			materialsManager.remove(partName);
			if (panel != null)
			{
				panel.refresh();
			}
			return true;
		}

		return config.skipBuildable();
	}

	private boolean trackFromChatFallback(String partName, boolean moveToBottom)
	{
		Map<String, Integer> materials = chatMaterialsParser.getMaterials(partName);
		if (materials == null || materials.isEmpty())
		{
			return false;
		}

		materialsManager.track(partName, materials, guideLevelReader.findLevelRequirements(client, partName, "Construction"), moveToBottom);
		return true;
	}

	private WikiRecipeService.Recipe selectRecipe(List<WikiRecipeService.Recipe> recipes, String variant)
	{
		if (recipes.isEmpty())
		{
			return null;
		}
		if (variant != null)
		{
			for (WikiRecipeService.Recipe recipe : recipes)
			{
				if (variant.equalsIgnoreCase(recipe.getOutputSubtext()))
				{
					return recipe;
				}
			}
		}
		return recipes.get(0);
	}

	/**
	 * Only Sailing names use a trailing "(raft/skiff/sloop)" as a boat-size marker; a
	 * Construction parenthetical like "STASH units (beginner)" is part of the name itself.
	 */
	private String wikiVariant(String partName, String skill)
	{
		if (!"Sailing".equals(skill))
		{
			return null;
		}
		return PartNames.boatSizeOf(partName);
	}

	/**
	 * Ship facilities (Range, Keg, ...) share their name with unrelated pages and live under a
	 * "(facility)" suffix - "Range" is a cooking range, "Keg" is a disambiguation page. Boat
	 * parts resolve on the first candidate, so the second is only ever fetched on a miss.
	 */
	private List<String> wikiPageCandidates(String partName, String skill)
	{
		String pageName = wikiPageName(partName, skill);
		return "Sailing".equals(skill)
			? Arrays.asList(pageName, pageName + " (facility)")
			: Collections.singletonList(pageName);
	}

	private String wikiPageName(String partName, String skill)
	{
		if (!"Sailing".equals(skill))
		{
			return partName;
		}
		// The wiki files the raft variant under "<tier> hull" too.
		return PartNames.sharedName(partName);
	}

	/**
	 * One guide interface is shared by every skill, so being open isn't enough - its title says
	 * which skill it's showing. Read live, since the user can switch skills without reopening.
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
	 * Whichever guide is open, else Sailing - the only other chat source is
	 * SAILING_CUSTOMISATION. Relies on {@link #isTrackedSkillGuideOpen()} having refreshed the
	 * cached titles earlier in the same event.
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
		// V2's FRAME is a static container whose own text is always empty - the title lives on a
		// dynamically-created child, so a direct getText() on it always comes back blank.
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
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			ticksSinceSceneLoad = 0;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (ticksSinceSceneLoad < 0)
		{
			return;
		}

		// Built furniture is spawned a few ticks after the scene itself; scanning immediately
		// finds only the empty hotspots.
		if (++ticksSinceSceneLoad >= HOUSE_SCAN_DELAY_TICKS)
		{
			ticksSinceSceneLoad = -1;
			if (houseContents.scanScene() && panel != null)
			{
				panel.refresh();
			}
		}
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
		if (event.getContainerId() == InventoryID.BANK)
		{
			bankSnapshot.update(event.getItemContainer());
		}

		if (panel != null)
		{
			panel.refresh();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Levels still read as 0 at the LOGGED_IN transition - they arrive in a later packet,
		// which this catches (along with any subsequent level-up).
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
		bankButtonManager.onScriptPostFired(event.getScriptId());
	}
}
