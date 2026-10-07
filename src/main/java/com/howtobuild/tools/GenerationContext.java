package com.howtobuild.tools;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import com.howtobuild.details.DetailSettings;
import com.howtobuild.details.SlabMode;
import com.howtobuild.geometry.ShapeKind;

/**
 * Settings shared by every tool: which material types may be used, detailing, slab placement, centre alignment for
 * even extents (per world axis) and grid rotation.
 *
 * @param materialTypes the Blocks / Slabs / Stairs multi-select (never empty; defaults to Blocks)
 * @param details       detailing settings
 * @param slabMode      slab placement mode
 * @param alignX        for an even extent along X, the centre extends towards +X (east) instead of −X
 * @param alignY        for an even extent along Y, the centre extends upwards
 * @param alignZ        for an even extent along Z, the centre extends towards +Z (south)
 * @param rotation      clockwise quarter turns about the vertical axis (for {@link com.howtobuild.tools.capability.Rotatable} tools)
 */
public record GenerationContext(Set<ShapeKind> materialTypes, DetailSettings details, SlabMode slabMode,
		boolean alignX, boolean alignY, boolean alignZ, int rotation) {
	public static final GenerationContext DEFAULT = new GenerationContext(EnumSet.of(ShapeKind.BLOCKS), DetailSettings.NONE,
			SlabMode.AUTOMATIC, true, true, true, 0);

	public GenerationContext {
		materialTypes = Collections.unmodifiableSet(materialTypes.isEmpty() ? EnumSet.of(ShapeKind.BLOCKS) : EnumSet.copyOf(materialTypes));
		rotation = rotation & 3;
	}

	public boolean allows(ShapeKind kind) {
		return materialTypes.contains(kind);
	}

	public GenerationContext withMaterialTypes(Set<ShapeKind> types) {
		return new GenerationContext(types, details, slabMode, alignX, alignY, alignZ, rotation);
	}

	public GenerationContext withDetails(DetailSettings newDetails) {
		return new GenerationContext(materialTypes, newDetails, slabMode, alignX, alignY, alignZ, rotation);
	}

	public GenerationContext withSlabMode(SlabMode mode) {
		return new GenerationContext(materialTypes, details, mode, alignX, alignY, alignZ, rotation);
	}

	public GenerationContext withAlignment(boolean x, boolean y, boolean z) {
		return new GenerationContext(materialTypes, details, slabMode, x, y, z, rotation);
	}

	public GenerationContext withRotation(int quarterTurns) {
		return new GenerationContext(materialTypes, details, slabMode, alignX, alignY, alignZ, quarterTurns);
	}
}
