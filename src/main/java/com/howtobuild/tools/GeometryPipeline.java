package com.howtobuild.tools;

import java.util.ArrayList;
import java.util.List;

import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.details.MaterialPattern;
import com.howtobuild.details.MaterialVariation;
import com.howtobuild.details.SmoothingPass;
import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.StairShapes;
import com.howtobuild.tools.capability.Detailable;
import com.howtobuild.tools.capability.Rotatable;

/**
 * The single path from settings to geometry, shared by every tool:
 *
 * <pre>
 * validate → generate → rotate (Rotatable) → smooth curves → pattern → variation → stair shapes → GeometryResult
 * </pre>
 *
 * Pure Java with no Minecraft access, so it can run on a background thread and in unit tests.
 */
public final class GeometryPipeline {
	/** Hard limit on generated blocks, to keep previews responsive. */
	public static final int MAX_BLOCKS = 1_000_000;

	private GeometryPipeline() {
	}

	public static GeometryResult generate(BuildTool tool, ToolSettings settings, GenerationContext context) {
		ValidationResult validation = tool.validate(settings, context);

		if (!validation.ok()) {
			List<String> messages = new ArrayList<>(validation.errors());
			messages.addAll(validation.warnings());
			return GeometryResult.empty(tool.id(), messages);
		}

		long estimate = tool.estimateBlocks(settings);

		if (estimate > MAX_BLOCKS) {
			return GeometryResult.empty(tool.id(), List.of(String.format("The shape would have about %,d blocks; the limit is %,d. Reduce the size or use a hollow style.", estimate, MAX_BLOCKS)));
		}

		GeometryBuilder builder = new GeometryBuilder();
		validation.warnings().forEach(builder::warn);

		try {
			tool.generate(settings, context, builder);
		} catch (RuntimeException e) {
			return GeometryResult.empty(tool.id(), List.of("Generation failed: " + e.getMessage()));
		}

		if (builder.size() > MAX_BLOCKS) {
			return GeometryResult.empty(tool.id(), List.of(String.format("The shape has %,d blocks; the limit is %,d. Reduce the size.", builder.size(), MAX_BLOCKS)));
		}

		DetailSettings details = context.details();

		if (tool instanceof Detailable detailable && detailable.supportedDetails().contains(DetailFeature.SMOOTH_CURVES)
				&& details.has(DetailFeature.SMOOTH_CURVES) && tool instanceof SmoothingPass.Smoothable smoothable) {
			SmoothingPass.apply(builder, context, smoothable.smoothingOpenFaces(settings));
		}

		if (tool instanceof Rotatable && context.rotation() != 0) {
			rotate(builder, context.rotation());
		}

		if (tool.verticalAnchor(settings) == VerticalAnchor.BASE) {
			long below = builder.snapshot().stream().filter(p -> p.y() < 0).count();

			// Never expected: base-anchored generators start at layer 0. Reported (not hidden) so it is caught by tests.
			if (below > 0) builder.warn("⚠ Internal error: " + below + " blocks below the base layer. Please report this.");
		}

		applyPattern(builder, details);
		applyVariation(builder, details.variation(), details.seed());
		StairShapes.resolve(builder);
		return GeometryResult.of(tool.id(), builder, tool.planeNormalAxis(settings, context));
	}

	/**
	 * Rotates every placement (and stair facing) clockwise about the vertical axis through the shape's <em>true</em>
	 * centre, which is the middle of its centre cells: a block for a 1×1 centre, a block corner for a 2×2 centre. The
	 * rotation is exact for 1×1 and 2×2 centres, so the footprint and centre never move; a mixed 1×2 centre cannot turn
	 * 90° on the grid exactly and rounds consistently (geometry and centre cells move together).
	 */
	static void rotate(GeometryBuilder builder, int quarterTurns) {
		for (int turn = 0; turn < (quarterTurns & 3); turn++) {
			int[] centre = doubledCentre(builder.centreCells());
			List<Placement> all = builder.snapshot();
			builder.clear();

			for (Placement p : all) {
				int[] r = rotateAbout(p.x(), p.z(), centre[0], centre[1]);
				builder.put(new Placement(r[0], p.y(), r[1], p.role(), p.shape().rotated(1), p.variant(), p.mirrored()));
			}

			rotateCells(builder.centreCells(), centre);
			rotateCells(builder.guides(), centre);
		}
	}

	private static void rotateCells(List<int[]> cells, int[] centre) {
		List<int[]> copy = new ArrayList<>(cells);
		cells.clear();

		for (int[] c : copy) {
			int[] r = rotateAbout(c[0], c[2], centre[0], centre[1]);
			cells.add(new int[] {r[0], c[1], r[1]});
		}
	}

	/** Doubled (x, z) of the middle of the centre cells; (1, 1), the anchor block's middle, if there are none. */
	static int[] doubledCentre(List<int[]> centreCells) {
		if (centreCells.isEmpty()) return new int[] {1, 1};

		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (int[] c : centreCells) {
			minX = Math.min(minX, c[0]);
			maxX = Math.max(maxX, c[0]);
			minZ = Math.min(minZ, c[2]);
			maxZ = Math.max(maxZ, c[2]);
		}

		return new int[] {minX + maxX + 1, minZ + maxZ + 1};
	}

	/** One clockwise quarter turn (viewed from above) of block (x, z) about the doubled centre (a, b). */
	public static int[] rotateAbout(int x, int z, int a, int b) {
		return new int[] {Math.floorDiv(a + b - (2 * z + 1) - 1, 2), Math.floorDiv(b - a + (2 * x + 1) - 1, 2)};
	}

	/** Clockwise (viewed from above) rotation of the cell (x, z) about the anchor block's centre. */
	public static int[] rotateXZ(int x, int z, int quarterTurns) {
		return switch (quarterTurns & 3) {
			case 1 -> new int[] {-z, x};
			case 2 -> new int[] {-x, -z};
			case 3 -> new int[] {z, -x};
			default -> new int[] {x, z};
		};
	}

	static void applyPattern(GeometryBuilder builder, DetailSettings details) {
		if (details.pattern() == MaterialPattern.NONE) return;

		for (Placement p : builder.snapshot()) {
			if (p.role() == MaterialRole.PRIMARY && details.pattern().secondary(p.x(), p.y(), p.z(), details.patternSize())) {
				builder.put(p.withRole(MaterialRole.TRIM));
			}
		}
	}

	static void applyVariation(GeometryBuilder builder, MaterialVariation variation, long seed) {
		if (variation == MaterialVariation.NONE) return;

		for (Placement p : builder.snapshot()) {
			int variant = variation.variant(seed, p.x(), p.y(), p.z());

			if (variant != 0) builder.put(p.withVariant(variant));
		}
	}
}
