package com.requiredmaterials;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel listing what's currently tracked, in a collapsible section per skill.
 *
 * Boat-size variants of the same part - "(raft)"/"(skiff)"/"(sloop)", or a raft's "base" vs a
 * skiff/sloop's "hull" - are tracked separately but share one card with a picker.
 */
public class RequiredMaterialsPanel extends PluginPanel
{
	private static final Color COLOR_HAVE_ENOUGH = new Color(96, 220, 96);
	private static final Color COLOR_HAVE_SOME = new Color(255, 165, 0);
	private static final Pattern LEVEL_REQUIREMENT_PATTERN = Pattern.compile("^Level (\\d+) (.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern VARIANT_SUFFIX = Pattern.compile("^(.*?)\\s*\\(([^)]*)\\)$");
	private static final String UNKNOWN_SKILL = "Other";

	private final MaterialsManager materialsManager;
	private final Client client;
	private final ClientThread clientThread;
	private final SkillIconManager skillIconManager;
	private final HeldItems heldItems;
	private final HouseContents houseContents;
	private final JPanel listContainer = new JPanel();
	private final Map<String, Integer> selectedVariantIndex = new HashMap<>();
	private final Map<String, Boolean> skillExpanded = new HashMap<>();

	RequiredMaterialsPanel(MaterialsManager materialsManager, Client client, ClientThread clientThread, SkillIconManager skillIconManager, HeldItems heldItems, HouseContents houseContents)
	{
		this.materialsManager = materialsManager;
		this.client = client;
		this.clientThread = clientThread;
		this.skillIconManager = skillIconManager;
		this.heldItems = heldItems;
		this.houseContents = houseContents;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel title = new JLabel("Required Materials");
		title.setForeground(ColorScheme.BRAND_ORANGE);

		JButton clearAll = new JButton("Clear all");
		clearAll.addActionListener(e -> clientThread.invoke(() ->
		{
			materialsManager.clear();
			skillExpanded.clear();
			refresh();
		}));

		JPanel header = new JPanel(new BorderLayout());
		header.add(title, BorderLayout.WEST);
		header.add(clearAll, BorderLayout.EAST);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.add(header);

		listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));

		add(top, BorderLayout.NORTH);
		add(listContainer, BorderLayout.CENTER);
	}

	/**
	 * Opens the tracked skill's section. A skill appearing for the first time collapses the
	 * others, so a switch of skill leaves only the new one open; re-tracking a skill already on
	 * the list just reopens it and leaves the rest alone.
	 */
	void onTracked(String skill)
	{
		if (!skillExpanded.containsKey(skill))
		{
			skillExpanded.replaceAll((s, expanded) -> false);
		}
		skillExpanded.put(skill, true);
		refresh();
	}

	void refresh()
	{
		listContainer.removeAll();

		if (materialsManager.isEmpty())
		{
			listContainer.add(wrappedText(
				"Nothing tracked yet. Open the Construction or Sailing skill guide and click on a piece of furniture or ship facility to start tracking the required materials. You can also start tracking required materials from the Furniture Creation or the Boat Customisation menus.",
				ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			if (!heldItems.bankKnown())
			{
				listContainer.add(wrappedText(
					"Open your bank to update the tracked required materials.",
					ColorScheme.LIGHT_GRAY_COLOR));
				listContainer.add(Box.createVerticalStrut(6));
			}

			if (hasUnconfirmedPrerequisite())
			{
				listContainer.add(wrappedText(
					"Visit your POH in building mode to check what's already built.",
					ColorScheme.LIGHT_GRAY_COLOR));
				listContainer.add(Box.createVerticalStrut(6));
			}

			// getRequirements() is in tracking order, so a skill's highest position is the last
			// time anything was tracked under it.
			Map<String, List<TrackedRequirement>> byPart = new LinkedHashMap<>();
			Map<String, Integer> lastTrackedAt = new HashMap<>();
			int position = 0;
			for (TrackedRequirement requirement : materialsManager.getRequirements())
			{
				byPart.computeIfAbsent(PartNames.sharedName(requirement.getPartName()), k -> new ArrayList<>())
					.add(requirement);
				lastTrackedAt.put(skillOf(requirement), position++);
			}

			Map<String, List<Map.Entry<String, List<TrackedRequirement>>>> bySkill = new LinkedHashMap<>();
			for (Map.Entry<String, List<TrackedRequirement>> entry : byPart.entrySet())
			{
				bySkill.computeIfAbsent(skillOf(entry.getValue().get(0)), k -> new ArrayList<>()).add(entry);
			}

			List<String> orderedSkills = new ArrayList<>(bySkill.keySet());
			orderedSkills.sort(Comparator.comparingInt((String skill) -> lastTrackedAt.getOrDefault(skill, -1)).reversed());

			boolean firstShown = true;
			for (String skill : orderedSkills)
			{
				listContainer.add(buildAccordion(skill, bySkill.get(skill), firstShown));
				listContainer.add(Box.createVerticalStrut(6));
				firstShown = false;
			}
		}

		listContainer.revalidate();
		listContainer.repaint();
	}

	/**
	 * Whether anything is waiting on a look inside the house - the hint is pointless once every
	 * prerequisite is confirmed, and misleading when nothing has any.
	 */
	private boolean hasUnconfirmedPrerequisite()
	{
		for (TrackedRequirement requirement : materialsManager.getRequirements())
		{
			for (String prerequisite : requirement.getPrerequisites())
			{
				if (!Boolean.TRUE.equals(houseContents.isBuilt(prerequisite)))
				{
					return true;
				}
			}
		}
		return false;
	}

	private JPanel buildAccordion(String skill, List<Map.Entry<String, List<TrackedRequirement>>> groups, boolean defaultExpanded)
	{
		boolean expanded = skillExpanded.computeIfAbsent(skill, k -> defaultExpanded);

		JLabel arrow = new JLabel(expanded ? "-" : "+");
		arrow.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		arrow.setFont(arrow.getFont().deriveFont(Font.BOLD));
		arrow.setPreferredSize(new Dimension(12, arrow.getPreferredSize().height));

		JLabel skillLabel = new JLabel(skill);
		skillLabel.setForeground(ColorScheme.BRAND_ORANGE);
		skillLabel.setFont(skillLabel.getFont().deriveFont(Font.BOLD));

		Skill skillEnum = LevelRequirement.named(skill);
		JLabel skillIcon = skillEnum != null
			? new JLabel(new ImageIcon(skillIconManager.getSkillImage(skillEnum, true)))
			: null;

		JPanel headerLabels = new JPanel();
		headerLabels.setLayout(new BoxLayout(headerLabels, BoxLayout.X_AXIS));
		headerLabels.setOpaque(false);
		headerLabels.add(arrow);
		headerLabels.add(Box.createHorizontalStrut(6));
		if (skillIcon != null)
		{
			headerLabels.add(skillIcon);
			headerLabels.add(Box.createHorizontalStrut(4));
		}
		headerLabels.add(skillLabel);

		JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(true);
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		header.add(headerLabels, BorderLayout.WEST);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

		MouseAdapter toggleListener = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				clientThread.invoke(() ->
				{
					skillExpanded.put(skill, !skillExpanded.getOrDefault(skill, defaultExpanded));
					refresh();
				});
			}
		};
		for (Component c : new Component[] {header, headerLabels, arrow, skillLabel, skillIcon})
		{
			if (c != null)
			{
				c.addMouseListener(toggleListener);
			}
		}

		JPanel wrapper = new JPanel(new GridBagLayout());
		wrapper.setOpaque(false);
		wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		wrapper.add(header, gbc);

		if (expanded)
		{
			for (Map.Entry<String, List<TrackedRequirement>> entry : groups)
			{
				List<TrackedRequirement> variants = entry.getValue();
				gbc.gridy++;
				gbc.insets = new Insets(6, 0, 0, 0);
				wrapper.add(variants.size() > 1
					? buildOuterCard(PartNames.capitalize(entry.getKey()), variants, buildVariantSection(entry.getKey(), variants))
					: buildOuterCard(variants.get(0).getPartName(), variants, buildContent(variants.get(0))), gbc);
			}
		}

		return wrapper;
	}

	private String skillOf(TrackedRequirement requirement)
	{
		return requirement.getSkill() != null ? requirement.getSkill() : UNKNOWN_SKILL;
	}

	private JPanel buildOuterCard(String title, List<TrackedRequirement> allInside, JPanel content)
	{
		JPanel card = new JPanel(new BorderLayout(0, 4));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		// Wraps rather than truncating - a scrollbar appearing is enough to cut a title short.
		JTextArea name = wrappedText(title, ColorScheme.BRAND_ORANGE);

		JButton remove = new JButton("x");
		remove.setMargin(new java.awt.Insets(0, 4, 0, 4));
		remove.addActionListener(e -> clientThread.invoke(() ->
		{
			for (TrackedRequirement requirement : allInside)
			{
				materialsManager.remove(requirement.getPartName());
			}
			refresh();
		}));

		JPanel cardHeader = new JPanel(new BorderLayout());
		cardHeader.setOpaque(false);
		cardHeader.add(name, BorderLayout.CENTER);
		cardHeader.add(remove, BorderLayout.EAST);

		card.add(cardHeader, BorderLayout.NORTH);
		card.add(content, BorderLayout.CENTER);
		return card;
	}

	private JPanel buildVariantSection(String selectionKey, List<TrackedRequirement> variants)
	{
		JComboBox<String> variantPicker = new JComboBox<>();
		for (TrackedRequirement variant : variants)
		{
			variantPicker.addItem(boatSize(variant.getPartName()));
		}
		int savedIndex = selectedVariantIndex.getOrDefault(selectionKey, 0);
		if (savedIndex >= variants.size())
		{
			savedIndex = 0;
		}
		variantPicker.setSelectedIndex(savedIndex);

		JPanel pickerRow = new JPanel(new BorderLayout());
		pickerRow.setOpaque(false);
		pickerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		pickerRow.add(variantPicker, BorderLayout.WEST);

		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.add(pickerRow);
		section.add(Box.createVerticalStrut(4));
		section.add(buildContent(variants.get(savedIndex)));

		// refresh() reads bank/skill state, which asserts it's on the client thread - Swing
		// listeners fire on the AWT event thread.
		variantPicker.addActionListener(e -> clientThread.invoke(() ->
		{
			selectedVariantIndex.put(selectionKey, variantPicker.getSelectedIndex());
			refresh();
		}));

		return section;
	}

	private String boatSize(String partName)
	{
		String size = PartNames.boatSizeOf(partName);
		return size != null ? size : partName;
	}

	private JPanel buildContent(TrackedRequirement requirement)
	{
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(Component.LEFT_ALIGNMENT);

		if (!requirement.getLevelRequirements().isEmpty() || !requirement.getPrerequisites().isEmpty())
		{
			for (String levelRequirement : requirement.getLevelRequirements())
			{
				content.add(buildLevelRequirementLine(levelRequirement));
			}
			for (String prerequisite : requirement.getPrerequisites())
			{
				// Green once we've seen it in the house; grey while we can't say, which covers
				// both "not built" and "haven't been inside yet".
				Color color = Boolean.TRUE.equals(houseContents.isBuilt(prerequisite))
					? COLOR_HAVE_ENOUGH
					: ColorScheme.LIGHT_GRAY_COLOR;
				content.add(wrappedText("Requires: " + prerequisite + " built", color));
			}
			content.add(Box.createVerticalStrut(6));
			content.add(divider());
			content.add(Box.createVerticalStrut(6));
		}

		if (requirement.getMaterials().isEmpty())
		{
			content.add(wrappedText("No materials required.", ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			for (RequiredMaterial material : requirement.getMaterials())
			{
				content.add(buildMaterialLine(material));
			}
		}

		return content;
	}

	private JTextArea buildLevelRequirementLine(String levelRequirement)
	{
		Color color = LevelRequirement.isMet(client, levelRequirement)
			? COLOR_HAVE_ENOUGH
			: ColorScheme.LIGHT_GRAY_COLOR;
		return wrappedText("Requires: " + levelRequirement, color);
	}

	private JTextArea buildMaterialLine(RequiredMaterial material)
	{
		if (material.getItemId() == null)
		{
			String text = material.getQuantity() + "x " + material.getName() + " (couldn't find a matching item)";
			return wrappedText(text, ColorScheme.PROGRESS_ERROR_COLOR);
		}

		int have = heldItems.count(material.getItemId());
		int need = material.getQuantity();

		// have == 0 could mean "own none" or "bank not opened yet", so it stays neutral gray
		// rather than flagging items you may already have.
		Color color;
		if (have >= need)
		{
			color = COLOR_HAVE_ENOUGH;
		}
		else if (have > 0)
		{
			color = COLOR_HAVE_SOME;
		}
		else
		{
			color = ColorScheme.LIGHT_GRAY_COLOR;
		}

		String text = material.getName() + " (" + have + "/" + need + ")";
		return wrappedText(text, color);
	}

	private JPanel divider()
	{
		JPanel line = new JPanel();
		line.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		line.setPreferredSize(new Dimension(Integer.MAX_VALUE, 1));
		line.setAlignmentX(Component.LEFT_ALIGNMENT);
		return line;
	}

	/**
	 * A JTextArea rather than a JLabel: JLabel's HTML "width:Npx" wrapping clips instead of
	 * reflowing when the sidebar's real width doesn't match the hardcoded pixel value.
	 */
	private JTextArea wrappedText(String text, Color color)
	{
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setFocusable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setForeground(color);
		area.setFont(new JLabel().getFont());
		area.setBorder(null);
		area.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		// Mixed alignments in a BoxLayout shrink the rows to half the panel.
		area.setAlignmentX(Component.LEFT_ALIGNMENT);
		return area;
	}
}
