package com.shipmaterials;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Everything needed for one upgrade part, e.g. "Oak mast and linen sail" and its
 * list of required materials. Keyed by part name in {@link MaterialsManager} so
 * clicking the same requirement again just replaces this entry.
 */
@Data
public class TrackedRequirement
{
	private final String partName;
	private final List<RequiredMaterial> materials;

	/**
	 * Skill level requirements (e.g. "Level 6 Construction") - these are never part of the
	 * chat message, only shown as widget text in the skill guide, so they're populated
	 * separately and may be empty if the guide wasn't open/searchable when this was tracked.
	 */
	private List<String> levelRequirements = new ArrayList<>();
}
