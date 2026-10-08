package com.howtobuild.tools;

import java.util.ArrayList;
import java.util.List;

import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.details.MaterialPattern;
import com.howtobuild.details.MaterialVariation;
import com.howtobuild.details.SmoothingPass;
import com.howtobuild.geometry.Annotation;
import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.GeometryValidator;
import com.howtobuild.geometry.MaterialRef;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.StairShapes;
import com.howtobuild.geometry.StateTransform;
import com.howtobuild.geometry.Voxels;
import com.howtobuild.palette.Randomiser;
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

		applyPattern(builder, details);
		applyVariation(builder, details.variation(), details.seed());
		int normal = tool.planeNormalAxis(settings, context);
		// Randomisation only chooses materials; positions and shapes are never changed.
		Randomiser.apply(builder, tool.randomisation(settings, context), normal);
		StairShapes.resolve(builder);
		GeometryResult result = GeometryResult.of(tool.id(), builder, normal);
		List<String> issues = GeometryValidator.validate(result, tool.verticalAnchor(settings) == VerticalAnchor.BASE);

		if (issues.isEmpty()) return result;

		// Never expected: reported (not hidden) so tests and the GUI catch generator bugs, and building is blocked.
		issues.forEach(builder::warn);
		return GeometryResult.of(tool.id(), builder, normal);
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
				builder.put(new Placement(r[0], p.y(), r[1], p.role(), p.shape().rotated(1), p.variant(), p.mirrored(), p.material()));
			}

			rotateCells(builder.centreCells(), centre);
			rotateCells(builder.guides(), centre);
			rotateGroups(builder, centre);

			// Exact saved states carry their own orientation: turn them with the geometry.
			List<MaterialRef> refs = new ArrayList<>(builder.materials());

			for (int i = 0; i < refs.size(); i++) {
				MaterialRef ref = refs.get(i);

				if (ref.exact()) refs.set(i, MaterialRef.exactState(StateTransform.rotateY(ref.value(), 1)));
			}

			builder.replaceMaterials(refs);
			List<Annotation> notes = new ArrayList<>(builder.annotations());
			builder.annotations().clear();

			for (Annotation a : notes) {
				int[] r = rotateAbout(a.x(), a.z(), centre[0], centre[1]);
				builder.annotate(r[0], a.y(), r[1], a.text());
			}
		}
	}

	private static void rotateGroups(GeometryBuilder builder, int[] centre) {
		if (builder.groups().isEmpty()) return;

		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap copy = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(builder.groups());
		builder.groups().clear();

		for (var e : copy.long2IntEntrySet()) {
			int x = Voxels.x(e.getLongKey());
			int y = Voxels.y(e.getLongKey());
			int z = Voxels.z(e.getLongKey());
			int[] r = rotateAbout(x, z, centre[0], centre[1]);
			builder.group(r[0], y, r[1], e.getIntValue());
		}
	}

	private static void rotateCells(List<int[]> cells, int[] centre) {
		List<int[]> copy = new ArrayList<>(cells);
		cells.clear();

		for (int[] c : copy) {
			int[] r = rotateAbout(c[0], c[2], centre[0], centre[1]);
			int[] moved = c.clone();
			moved[0] = r[0];
			moved[2] = r[1];
			cells.add(moved);
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
