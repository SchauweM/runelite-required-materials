package com.requiredmaterials;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** Keyed by part name in {@link MaterialsManager}, so re-tracking replaces the entry. */
@Data
public class TrackedRequirement
{
	private final String partName;
	private final List<RequiredMaterial> materials;

	private List<String> levelRequirements = new ArrayList<>();

	/** Built, not collected ("Tool store 3") - shown, but never counted against the bank. */
	private List<String> prerequisites = new ArrayList<>();

	private String skill;
}
