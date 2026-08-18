package com.shipmaterials;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel listing what's currently tracked. Click a part's "x" to stop tracking just
 * that requirement, or "Clear all" to reset everything (also clears what the bank filter
 * highlights).
 */
public class ShipMaterialsPanel extends PluginPanel
{
	private final MaterialsManager materialsManager;
	private final JPanel listContainer = new JPanel();

	ShipMaterialsPanel(MaterialsManager materialsManager)
	{
		this.materialsManager = materialsManager;

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

		listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));

		add(header, BorderLayout.NORTH);
		add(listContainer, BorderLayout.CENTER);
	}

	void refresh()
	{
		listContainer.removeAll();

		if (materialsManager.isEmpty())
		{
			JLabel empty = new JLabel("<html>Nothing tracked yet. Click a ship upgrade's requirements in-game to start tracking.</html>");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			listContainer.add(empty);
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

		JPanel materialsGrid = new JPanel(new GridLayout(0, 1));
		materialsGrid.setOpaque(false);
		for (RequiredMaterial material : requirement.getMaterials())
		{
			String suffix = material.getItemId() == null ? "  (item not found - check spelling)" : "";
			JLabel line = new JLabel(material.getQuantity() + "x " + material.getName() + suffix);
			line.setForeground(material.getItemId() == null ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
			materialsGrid.add(line);
		}

		card.add(cardHeader, BorderLayout.NORTH);
		card.add(materialsGrid, BorderLayout.CENTER);
		return card;
	}
}
