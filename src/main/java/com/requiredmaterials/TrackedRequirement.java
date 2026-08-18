package com.requiredmaterials;

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
	 * Skill level requirements (e.g. "Level 6 Construction") - populated separately from
	 * materials, and may be empty if they weren't discoverable when this was tracked.
	 */
	private List<String> levelRequirements = new ArrayList<>();

	/**
	 * Which skill this was tracked from (e.g. "Sailing", "Construction") - drives which
	 * side-panel accordion this shows up under.
	 */
	private String skill;
}
