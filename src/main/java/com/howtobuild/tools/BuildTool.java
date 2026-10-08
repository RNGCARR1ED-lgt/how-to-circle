package com.howtobuild.tools;

import java.util.List;

import com.howtobuild.geometry.GeometryBuilder;

/**
 * A geometry tool. Implementations only describe their parameters and write placements into a
 * {@link GeometryBuilder}; validation output, rotation, patterns, variation, stair shapes, mirroring, materials, labels,
 * rendering and command building are all shared (see {@link GeometryPipeline}).
 */
public interface BuildTool {
	/** Stable id, used for config, presets and translation keys ({@code tool.howtobuild.<id>}). */
	String id();

	List<ToolParameter> parameters();

	/** Checks the settings. Errors prevent generation; warnings are shown alongside the preview. */
	default ValidationResult validate(ToolSettings settings, GenerationContext context) {
		return new ValidationResult();
	}

	/** Writes the geometry, relative to the anchor block at (0, 0, 0). Must be pure and deterministic. */
	void generate(ToolSettings settings, GenerationContext context, GeometryBuilder out);

	/** For flat tools, the axis perpendicular to the shape (0 = X, 1 = Y, 2 = Z) for the given settings; otherwise -1. */
	default int planeNormalAxis(ToolSettings settings, GenerationContext context) {
		return -1;
	}

	/**
	 * A cheap upper estimate of the number of blocks the settings produce, checked before generating so oversized
	 * shapes are rejected without allocating anything. 0 means "small / unknown".
	 */
	default long estimateBlocks(ToolSettings settings) {
		return 0;
	}

	/**
	 * What the anchor's Y layer means for these settings. {@link VerticalAnchor#BASE} tools must not generate anything
	 * below layer 0; the pipeline checks this.
	 */
	default VerticalAnchor verticalAnchor(ToolSettings settings) {
		return VerticalAnchor.BASE;
	}

	/**
	 * The randomisation applied to this tool's result. By default the player's global randomisation (Randomise tab);
	 * the Randomisation tool always randomises.
	 */
	default com.howtobuild.palette.RandomSettings randomisation(ToolSettings settings, GenerationContext context) {
		return context.palettes().random();
	}

	/** Whether the Blocks / Slabs / Stairs material types change what this tool generates. */
	default boolean usesMaterialTypes() {
		return false;
	}

	default String translationKey() {
		return "tool.howtobuild." + id();
	}

	default ToolSettings defaults() {
		return ToolSettings.defaults(parameters());
	}
}
