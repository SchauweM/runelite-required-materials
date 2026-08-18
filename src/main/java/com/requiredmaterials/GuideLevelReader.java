package com.requiredmaterials;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Reads level requirements from the skill guide's widget text, as a fallback when a Construction
 * item has no wiki recipe.
 *
 * A row's primary skill level is a bare number in its own widget, with any additional skills in a
 * separate "Requires: Level N Skill" widget. The two are siblings related only by sharing a Y
 * position, hence the row-matching below.
 */
class GuideLevelReader
{
	private static final int TOP_LEVEL_SLOT_SCAN = 40;
	private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");
	private static final Pattern BOAT_TYPE_SUFFIX = Pattern.compile("\\s*\\([^)]*\\)$");

	List<String> findLevelRequirements(Client client, String partName, String primarySkill)
	{
		for (int groupId : new int[] {InterfaceID.SKILL_GUIDE, InterfaceID.SKILL_GUIDE_V2})
		{
			for (int childId = 0; childId <= TOP_LEVEL_SLOT_SCAN; childId++)
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

	private void collectRowWidgets(Widget widget, List<Widget> numberWidgets, List<Widget> nameWidgets)
	{
		if (widget == null)
		{
			return;
		}

		String text = widget.getText();
		if (text != null && !text.isEmpty())
		{
			(DIGITS_ONLY.matcher(text.trim()).matches() ? numberWidgets : nameWidgets).add(widget);
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
			String stripped = text.substring(requiresIdx + "Requires:".length()).replaceAll("<[^>]*>", "").trim();
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

	private String stripBoatTypeSuffix(String partName)
	{
		return BOAT_TYPE_SUFFIX.matcher(partName).replaceFirst("").trim();
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
}
