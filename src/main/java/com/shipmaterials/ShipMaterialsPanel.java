package com.shipmaterials;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel listing what's currently tracked. Click a part's "x" to stop tracking just
 * that requirement, or "Clear all" to reset everything (also clears what the bank filter
 * highlights).
 *
 * Parts that come in several boat-size variants - "Wooden mast and linen sails (raft)",
 * "(skiff)", "(sloop)", or a raft's "base" vs a skiff/sloop's "hull" - are tracked as separate
 * requirements but grouped under one card with a picker, rather than shown as unrelated-looking
 * duplicate entries.
 */
public class ShipMaterialsPanel extends PluginPanel
{
	private static final Color COLOR_HAVE_ENOUGH = new Color(96, 220, 96);
	private static final Color COLOR_HAVE_SOME = new Color(255, 165, 0);
	private static final Pattern LEVEL_REQUIREMENT_PATTERN = Pattern.compile("^Level (\\d+) (.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern VARIANT_SUFFIX = Pattern.compile("^(.*?)\\s*\\(([^)]*)\\)$");

	private final MaterialsManager materialsManager;
	private final Client client;
	private final ClientThread clientThread;
	private final JPanel listContainer = new JPanel();
	private final Map<String, Integer> selectedVariantIndex = new HashMap<>();

	ShipMaterialsPanel(MaterialsManager materialsManager, Client client, ClientThread clientThread)
	{
		this.materialsManager = materialsManager;
		this.client = client;
		this.clientThread = clientThread;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel title = new JLabel("Tracked ship materials");
		title.setForeground(ColorScheme.BRAND_ORANGE);

		JButton clearAll = new JButton("Clear all");
		clearAll.addActionListener(e -> clientThread.invoke(() ->
		{
			materialsManager.clear();
			refresh();
		}));

		JPanel header = new JPanel(new BorderLayout());
		header.add(title, BorderLayout.WEST);
		header.add(clearAll, BorderLayout.EAST);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.add(header);
		top.add(Box.createVerticalStrut(4));
		top.add(wrappedText("Open your bank to update quantities below.", ColorScheme.LIGHT_GRAY_COLOR));

		listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));

		add(top, BorderLayout.NORTH);
		add(listContainer, BorderLayout.CENTER);
	}

	void refresh()
	{
		listContainer.removeAll();

		if (materialsManager.isEmpty())
		{
			listContainer.add(wrappedText(
				"Nothing tracked yet. Click a ship upgrade's requirements in-game to start tracking.",
				ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			Map<String, List<TrackedRequirement>> byPart = new LinkedHashMap<>();
			for (TrackedRequirement requirement : materialsManager.getRequirements())
			{
				byPart.computeIfAbsent(canonicalGroupKey(requirement.getPartName()), k -> new ArrayList<>())
					.add(requirement);
			}

			for (Map.Entry<String, List<TrackedRequirement>> entry : byPart.entrySet())
			{
				List<TrackedRequirement> variants = entry.getValue();
				listContainer.add(variants.size() > 1
					? buildOuterCard(capitalize(entry.getKey()), variants, buildVariantSection(entry.getKey(), variants))
					: buildOuterCard(variants.get(0).getPartName(), variants,
						buildContent(variants.get(0))));
				listContainer.add(Box.createVerticalStrut(6));
			}
		}

		listContainer.revalidate();
		listContainer.repaint();
	}

	private String variantBaseName(String partName)
	{
		Matcher m = VARIANT_SUFFIX.matcher(partName);
		return m.matches() ? m.group(1).trim() : partName;
	}

	private String variantLabel(String partName)
	{
		Matcher m = VARIANT_SUFFIX.matcher(partName);
		if (m.matches())
		{
			String suffix = m.group(2).trim();
			return suffix.isEmpty() ? suffix : capitalize(suffix);
		}
		// A raft's structural part carries no boat-size suffix at all - it's just "<tier> base",
		// since raft is the only size that calls it that (skiff/sloop call it "hull (skiff/sloop)").
		// No suffix on a base part means it implies "Raft".
		if (partName.toLowerCase().endsWith("base"))
		{
			return "Raft";
		}
		return partName;
	}

	/**
	 * A raft's structural part is called "base", while a skiff/sloop's equivalent is called
	 * "hull" - not separate upgrades, just different names for the same part slot depending on
	 * which boat size is being built, so they're grouped under one canonical key ("<name> hull")
	 * to share a single card and boat-size picker (Raft/Skiff/Sloop) instead of two.
	 */
	private String canonicalGroupKey(String partName)
	{
		String base = variantBaseName(partName);
		return base.toLowerCase().endsWith("base")
			? base.substring(0, base.length() - "base".length()) + "hull"
			: base;
	}

	private String capitalize(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/**
	 * The outer card chrome (name, remove-everything-inside button) wrapping whatever content
	 * panel is passed in - used for both plain single/grouped-by-boat-size cards and tier
	 * cards, so all three levels share one consistent look.
	 */
	private JPanel buildOuterCard(String title, List<TrackedRequirement> allInside, JPanel content)
	{
		JPanel card = new JPanel(new BorderLayout(0, 4));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		JLabel name = new JLabel(title);
		name.setForeground(ColorScheme.BRAND_ORANGE);

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
		cardHeader.add(name, BorderLayout.WEST);
		cardHeader.add(remove, BorderLayout.EAST);

		card.add(cardHeader, BorderLayout.NORTH);
		card.add(content, BorderLayout.CENTER);
		return card;
	}

	/**
	 * A boat-size picker plus the currently-selected variant's content, for one part type that
	 * has more than one boat-size variant tracked.
	 */
	private JPanel buildVariantSection(String selectionKey, List<TrackedRequirement> variants)
	{
		JComboBox<String> variantPicker = new JComboBox<>();
		for (TrackedRequirement variant : variants)
		{
			variantPicker.addItem(variantLabel(variant.getPartName()));
		}
		int savedIndex = selectedVariantIndex.getOrDefault(selectionKey, 0);
		if (savedIndex >= variants.size())
		{
			savedIndex = 0;
		}
		variantPicker.setSelectedIndex(savedIndex);

		JPanel pickerRow = new JPanel(new BorderLayout());
		pickerRow.setOpaque(false);
		pickerRow.add(variantPicker, BorderLayout.WEST);

		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.add(pickerRow);
		section.add(Box.createVerticalStrut(4));
		section.add(buildContent(variants.get(savedIndex)));

		// buildContent() reads live client state (bank contents, skill levels), which - like
		// every RuneLite Client API call - asserts it's running on the client thread, not
		// whatever thread fired this Swing action event (the AWT event thread).
		variantPicker.addActionListener(e -> clientThread.invoke(() ->
		{
			selectedVariantIndex.put(selectionKey, variantPicker.getSelectedIndex());
			refresh();
		}));

		return section;
	}

	private JPanel buildContent(TrackedRequirement requirement)
	{
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);

		if (!requirement.getLevelRequirements().isEmpty())
		{
			for (String levelRequirement : requirement.getLevelRequirements())
			{
				content.add(buildLevelRequirementLine(levelRequirement));
			}
			content.add(Box.createVerticalStrut(6));
			content.add(divider());
			content.add(Box.createVerticalStrut(6));
		}

		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		for (RequiredMaterial material : requirement.getMaterials())
		{
			content.add(buildMaterialLine(material, bank));
		}

		return content;
	}

	private JTextArea buildLevelRequirementLine(String levelRequirement)
	{
		Color color = ColorScheme.LIGHT_GRAY_COLOR;
		Matcher matcher = LEVEL_REQUIREMENT_PATTERN.matcher(levelRequirement);
		if (matcher.matches())
		{
			int requiredLevel = Integer.parseInt(matcher.group(1));
			Skill skill = findSkillByName(matcher.group(2).trim());
			if (skill != null && client.getRealSkillLevel(skill) >= requiredLevel)
			{
				color = COLOR_HAVE_ENOUGH;
			}
		}
		return wrappedText("Requires: " + levelRequirement, color);
	}

	private Skill findSkillByName(String name)
	{
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(name))
			{
				return skill;
			}
		}
		return null;
	}

	private JTextArea buildMaterialLine(RequiredMaterial material, ItemContainer bank)
	{
		if (material.getItemId() == null)
		{
			String text = material.getQuantity() + "x " + material.getName() + " (couldn't find a matching item)";
			return wrappedText(text, ColorScheme.PROGRESS_ERROR_COLOR);
		}

		int have = bank == null ? 0 : bank.count(material.getItemId());
		int need = material.getQuantity();

		// have == 0 covers two cases we can't tell apart - genuinely own none, or the bank
		// just hasn't been opened this session yet - so it stays neutral gray either way
		// rather than falsely flagging items you might actually already have.
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
		return line;
	}

	/**
	 * JLabel's HTML "width:Npx" wrapping trick clips instead of reflowing once the sidebar's
	 * actual available width doesn't match the guessed pixel value - a JTextArea with real
	 * line-wrap reflows against whatever width it's actually laid out at.
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
		return area;
	}
}
