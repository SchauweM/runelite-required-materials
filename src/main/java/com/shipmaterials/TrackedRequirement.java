package com.shipmaterials;

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
}
