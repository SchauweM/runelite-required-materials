package com.shipmaterials;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel listing what's currently tracked. Click a part's "x" to stop tracking just
 * that requirement, or "Clear all" to reset everything (also clears what the bank filter
 * highlights).
 */
public class ShipMaterialsPanel extends PluginPanel
{
	private static final Color COLOR_HAVE_ENOUGH = new Color(96, 220, 96);
	private static final Color COLOR_HAVE_SOME = new Color(255, 165, 0);

	private final MaterialsManager materialsManager;
	private final Client client;
	private final JPanel listContainer = new JPanel();

	ShipMaterialsPanel(MaterialsManager materialsManager, Client client)
	{
		this.materialsManager = materialsManager;
		this.client = client;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel title = new JLabel("Tracked ship materials");
		title.setForeground(ColorScheme.BRAND_ORANGE);

		JButton clearAll = new JButton("Clear all");
		clearAll.addActionListener(e ->
		{
			materialsManager.clear();
			refresh();
		});

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
			for (TrackedRequirement requirement : materialsManager.getRequirements())
			{
				listContainer.add(buildRequirementCard(requirement));
				listContainer.add(Box.createVerticalStrut(6));
			}
		}

		listContainer.revalidate();
		listContainer.repaint();
	}

	private JPanel buildRequirementCard(TrackedRequirement requirement)
	{
		JPanel card = new JPanel();
		card.setLayout(new BorderLayout(0, 4));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		JLabel name = new JLabel(requirement.getPartName());
		name.setForeground(ColorScheme.BRAND_ORANGE);

		JButton remove = new JButton("x");
		remove.setMargin(new java.awt.Insets(0, 4, 0, 4));
		remove.addActionListener(e ->
		{
			materialsManager.remove(requirement.getPartName());
			refresh();
		});

		JPanel cardHeader = new JPanel(new BorderLayout());
		cardHeader.setOpaque(false);
		cardHeader.add(name, BorderLayout.WEST);
		cardHeader.add(remove, BorderLayout.EAST);

		ItemContainer bank = client.getItemContainer(InventoryID.BANK);

		JPanel materialsGrid = new JPanel();
		materialsGrid.setLayout(new BoxLayout(materialsGrid, BoxLayout.Y_AXIS));
		materialsGrid.setOpaque(false);
		for (RequiredMaterial material : requirement.getMaterials())
		{
			materialsGrid.add(buildMaterialLine(material, bank));
		}

		card.add(cardHeader, BorderLayout.NORTH);
		card.add(materialsGrid, BorderLayout.CENTER);
		return card;
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
