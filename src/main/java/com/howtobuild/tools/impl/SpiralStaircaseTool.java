package com.howtobuild.tools.impl;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailPreset;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.details.SlabMode;
import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.Facing;
import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.Mask2D;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.ToolParameter;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.ValidationResult;
import com.howtobuild.tools.capability.CommandBuildable;
import com.howtobuild.tools.capability.Detailable;
import com.howtobuild.tools.capability.Dimensionable;
import com.howtobuild.tools.capability.MaterialAssignable;
import com.howtobuild.tools.capability.Mirrorable;
import com.howtobuild.tools.capability.Rotatable;

/**
 * Spiral (helical) staircase around the anchor column.
 *
 * <h2>Geometry</h2>
 * The steps occupy the exact block-grid annulus between the inner and outer radius (the circle generator's cells). The
 * full rotation (revolutions × 360° + extra angle) is divided into {@code N} equal angular steps, where {@code N} is the
 * total height divided by the rise per step (½ block for slab steps). A column belongs to the step whose angular range
 * contains the column's angle, once per revolution, so every revolution stacks above the previous one.
 *
 * <h2>Materials</h2>
 * The selected material types (Blocks / Slabs / Stairs, any combination) decide what each part is made of:
 * <ul>
 *     <li><b>Main step</b>: stairs (facing the local direction of ascent, i.e. the spiral's tangent rounded to the
 *     nearest cardinal direction), slabs (alternating bottom/top for half-block steps) or full blocks.</li>
 *     <li><b>Support</b> under each step: full blocks, a top slab, or an upside-down stair (smooth underside).</li>
 *     <li><b>Trim</b> along the outer edge: a different type in the trim material.</li>
 * </ul>
 * {@code AUTO} picks a sensible assignment from the selected types; each part can also be set explicitly.
 * Stair shapes (straight, inner/outer corners) are derived afterwards from neighbouring stairs by the shared pass.
 */
public final class SpiralStaircaseTool implements BuildTool, Mirrorable, Rotatable, Detailable, MaterialAssignable, Dimensionable, CommandBuildable {
	public enum Direction {
		CLOCKWISE,
		COUNTER_CLOCKWISE
	}

	/** What a part of the staircase is built from. */
	public enum PartType {
		AUTO,
		BLOCKS,
		SLABS,
		STAIRS,
		NONE
	}

	/** Resolved assignment of material types to staircase parts. */
	public record Assignment(PartType main, PartType support, PartType trim) {
	}

	@Override
	public String id() {
		return "spiral";
	}

	@Override
	public boolean usesMaterialTypes() {
		return true;
	}

	@Override
	public List<ToolParameter> parameters() {
		return List.of(
				ToolParameter.integer("outer_radius", 1, 128, 5),
				ToolParameter.integer("stair_width", 1, 64, 3),
				ToolParameter.integer("height", 2, 384, 16),
				ToolParameter.integer("revolutions", 0, 50, 2),
				ToolParameter.integer("extra_angle", 0, 359, 0).advanced(),
				ToolParameter.integer("step_rise", 1, 4, 1).advanced(),
				ToolParameter.choice("direction", Direction.COUNTER_CLOCKWISE),
				ToolParameter.integer("start_angle", 0, 359, 0).advanced(),
				ToolParameter.integer("start_height", 0, 128, 0).advanced(),
				ToolParameter.choice("main_step", PartType.AUTO).section("staircase"),
				ToolParameter.choice("support", PartType.AUTO).section("staircase"),
				ToolParameter.choice("trim", PartType.AUTO).section("staircase"),
				ToolParameter.integer("support_depth", 1, 8, 1).section("staircase").advanced());
	}

	/** Total rotation in degrees. */
	public static int totalAngle(ToolSettings s) {
		return s.getInt("revolutions") * 360 + s.getInt("extra_angle");
	}

	public static int innerRadius(ToolSettings s) {
		return Math.max(0, s.getInt("outer_radius") - s.getInt("stair_width"));
	}

	/**
	 * Resolves AUTO choices from the selected material types: stairs are preferred for the steps, then slabs, then
	 * blocks; the support uses blocks (or the remaining type); the trim uses whichever selected type is still unused.
	 */
	public static Assignment assignment(ToolSettings s, Set<ShapeKind> types) {
		boolean blocks = types.contains(ShapeKind.BLOCKS);
		boolean slabs = types.contains(ShapeKind.SLABS);
		boolean stairs = types.contains(ShapeKind.STAIRS);

		PartType main = s.getEnum("main_step", PartType.class);

		if (main == PartType.AUTO || main == PartType.NONE || !allowed(main, types)) {
			main = stairs ? PartType.STAIRS : slabs ? PartType.SLABS : PartType.BLOCKS;
		}

		PartType support = s.getEnum("support", PartType.class);

		if (support == PartType.AUTO || (support != PartType.NONE && !allowed(support, types))) {
			if (blocks && main != PartType.BLOCKS) support = PartType.BLOCKS;
			else if (main == PartType.STAIRS && slabs) support = PartType.SLABS;
			else if (main == PartType.STAIRS) support = PartType.STAIRS;
			else support = PartType.NONE;
		}

		PartType trim = s.getEnum("trim", PartType.class);

		if (trim == PartType.AUTO || (trim != PartType.NONE && !allowed(trim, types))) {
			trim = PartType.NONE;

			for (PartType candidate : new PartType[] {PartType.SLABS, PartType.BLOCKS, PartType.STAIRS}) {
				if (allowed(candidate, types) && candidate != main && candidate != support) {
					trim = candidate;
					break;
				}
			}
		}

		return new Assignment(main, support, trim);
	}

	private static boolean allowed(PartType type, Set<ShapeKind> types) {
		return switch (type) {
			case BLOCKS -> types.contains(ShapeKind.BLOCKS);
			case SLABS -> types.contains(ShapeKind.SLABS);
			case STAIRS -> types.contains(ShapeKind.STAIRS);
			case AUTO, NONE -> true;
		};
	}

	/** Rise of one step in half blocks. */
	public static int riseHalfBlocks(ToolSettings s, Assignment a) {
		return a.main() == PartType.SLABS ? 1 : 2 * s.getInt("step_rise");
	}

	public static int stepCount(ToolSettings s, Assignment a) {
		return (2 * s.getInt("height")) / riseHalfBlocks(s, a);
	}

	@Override
	public ValidationResult validate(ToolSettings s, GenerationContext ctx) {
		ValidationResult r = new ValidationResult();
		Assignment a = assignment(s, ctx.materialTypes());
		int outer = s.getInt("outer_radius");
		int width = s.getInt("stair_width");
		int total = totalAngle(s);
		int steps = stepCount(s, a);

		r.errorIf(total <= 0, "The staircase must turn: set at least 1 revolution or an extra angle.");
		r.errorIf(steps < 1, "The height is too small for a single step.");

		if (!r.ok()) return r;

		r.warnIf(width > outer, "⚠ Stair width (" + width + ") is larger than the outer radius (" + outer + "); the steps meet in the middle.");

		double stepAngle = (double) total / steps;
		r.warnIf(stepAngle > 90, String.format("⚠ Each step turns %.0f°; steps will look like separate platforms. Increase the height or reduce the rotation.", stepAngle));

		double risePerRevolution = s.getInt("height") * 360.0 / total;
		int bodyThickness = 1 + (a.support() == PartType.NONE ? 0 : a.support() == PartType.BLOCKS ? s.getInt("support_depth") : 1);

		if (total > 360) {
			r.warnIf(risePerRevolution < bodyThickness + 2, String.format(
					"⚠ Only %.1f blocks of rise per turn: the staircase has no headroom (needs %d) and may overlap itself.", risePerRevolution, bodyThickness + 2));
		}

		r.warnIf(a.main() != PartType.SLABS && s.getInt("step_rise") > 1, "⚠ Steps higher than 1 block cannot be walked up without jumping.");

		PartType requested = s.getEnum("main_step", PartType.class);
		r.warnIf(requested != PartType.AUTO && requested != a.main(),
				"⚠ " + requested + " are not selected in Material Types; using " + a.main() + " for the steps.");
		return r;
	}

	@Override
	public void generate(ToolSettings s, GenerationContext ctx, GeometryBuilder out) {
		Assignment a = assignment(s, ctx.materialTypes());
		DetailSettings d = ctx.details();
		int outer = s.getInt("outer_radius");
		int inner = innerRadius(s);
		int totalAngle = totalAngle(s);
		int rise = riseHalfBlocks(s, a);
		int steps = stepCount(s, a);
		int startUnits = 2 * s.getInt("start_height");
		double stepAngle = (double) totalAngle / steps;
		double startAngle = s.getInt("start_angle");
		int dir = s.getEnum("direction", Direction.class) == Direction.CLOCKWISE ? 1 : -1;
		int supportDepth = s.getInt("support_depth");
		boolean trimEnabled = a.trim() != PartType.NONE || d.has(DetailFeature.STEP_TRIM);
		PartType trimType = a.trim() != PartType.NONE ? a.trim() : a.main();

		// Exact grid annulus: outer disc minus inner hole (odd diameters keep the axis on the anchor column).
		Mask2D outerDisc = Mask2D.ellipse(2 * outer + 1, 2 * outer + 1);
		Mask2D hole = inner > 0 ? Mask2D.ellipse(2 * inner - 1, 2 * inner - 1) : null;
		Mask2D annulus = hole == null ? outerDisc : outerDisc.minus(hole, outer - inner + 1, outer - inner + 1);
		Mask2D outerEdge = outerDisc.outline();
		Mask2D innerEdge = hole == null ? null : Mask2D.ellipse(2 * inner + 1, 2 * inner + 1).outline();
		int intersections = 0;

		for (int v = 0; v < annulus.height(); v++) {
			for (int u = 0; u < annulus.width(); u++) {
				if (!annulus.contains(u, v)) continue;

				int x = u - outer;
				int z = v - outer;
				double phi = Math.toDegrees(Math.atan2(z, x));
				double relative = Math.floorMod((long) Math.floor((dir * (phi - startAngle)) * 1000), 360_000L) / 1000.0;
				boolean outerCell = outerEdge.contains(u, v);
				boolean innerCell = innerEdge != null && innerEdge.contains(u - outer + inner, v - outer + inner);
				Facing ascent = ascent(x, z, dir);

				for (double angle = relative; angle < totalAngle; angle += 360) {
					int step = (int) Math.floor(angle / stepAngle);

					if (step >= steps) break;

					int top = startUnits + (step + 1) * rise; // top of the walking surface, in half blocks
					MaterialRole stepRole = d.has(DetailFeature.ALTERNATE_STEPS) && (step & 1) == 1 ? MaterialRole.ACCENT : MaterialRole.STEP;
					PartType type = trimEnabled && outerCell ? trimType : a.main();
					MaterialRole role = trimEnabled && outerCell ? MaterialRole.TRIM : stepRole;
					int y = mainY(type, top, ctx.slabMode());

					if (out.has(x, y, z)) intersections++;

					out.set(x, y, z, role, mainShape(type, top, ascent, ctx.slabMode()));
					placeSupport(out, a.support(), x, y, z, ascent, supportDepth, startUnits / 2);

					if (d.has(DetailFeature.OUTER_RAIL) && outerCell) out.setIfAbsent(x, y + 1, z, MaterialRole.RAIL, BlockShape.FULL);
					if (d.has(DetailFeature.INNER_RAIL) && innerCell) out.setIfAbsent(x, y + 1, z, MaterialRole.RAIL, BlockShape.FULL);

					if (d.has(DetailFeature.SUPPORT_PILLARS) && outerCell && step % Math.max(2, d.interval()) == 0
							&& Math.abs(angle - (step + 0.5) * stepAngle) < 360.0 / (2 * Math.PI * outer) * 0.75) {
						for (int py = y - 1; py >= startUnits / 2; py--) {
							out.setIfAbsent(x, py, z, MaterialRole.SUPPORT, BlockShape.FULL);
						}
					}
				}
			}
		}

		int topY = (startUnits + steps * rise + 1) / 2;
		int baseY = startUnits / 2;

		if (d.has(DetailFeature.CENTRAL_COLUMN)) {
			int columnRadius = Math.max(0, inner - 1);
			Mask2D column = Mask2D.ellipse(2 * columnRadius + 1, 2 * columnRadius + 1);

			for (int v = 0; v < column.height(); v++) {
				for (int u = 0; u < column.width(); u++) {
					if (!column.contains(u, v)) continue;

					for (int y = baseY; y < topY; y++) {
						out.setIfAbsent(u - columnRadius, y, v - columnRadius, MaterialRole.SUPPORT, BlockShape.FULL);
					}
				}
			}
		}

		if (d.has(DetailFeature.WALL_ATTACHMENT) || d.has(DetailFeature.RING_SUPPORT)) {
			Mask2D ring = Mask2D.ellipse(2 * outer + 3, 2 * outer + 3).outline();

			for (int v = 0; v < ring.height(); v++) {
				for (int u = 0; u < ring.width(); u++) {
					if (!ring.contains(u, v)) continue;

					for (int y = baseY; y <= topY; y++) {
						boolean band = d.has(DetailFeature.RING_SUPPORT) && (y - baseY) % Math.max(2, d.interval()) == 0;

						if (d.has(DetailFeature.WALL_ATTACHMENT) || band) {
							out.setIfAbsent(u - outer - 1, y, v - outer - 1, band ? MaterialRole.SUPPORT : MaterialRole.PRIMARY, BlockShape.FULL);
						}
					}
				}
			}
		}

		if (d.has(DetailFeature.LANDING)) {
			landing(out, annulus, outer, startAngle - dir * 90, startAngle, dir, baseY - 1);
			double end = startAngle + dir * totalAngle;
			landing(out, annulus, outer, end, end + dir * 90, dir, topY - 1);
		}

		if (intersections > 0) out.warn("⚠ This configuration causes geometry overlap: " + intersections + " step blocks intersect a lower turn.");

		out.centreCell(0, baseY, 0);
		out.value("radius", outer);
		out.value("inner_radius", inner);
		out.value("width", s.getInt("stair_width"));
		out.value("height", s.getInt("height"));
		out.value("revolutions", totalAngle / 360.0);
		out.value("rise_per_revolution", s.getInt("height") * 360.0 / totalAngle);
		out.value("steps", steps);
		out.value("step_angle", stepAngle);
		out.value("end_angle", Math.floorMod(Math.round(startAngle + dir * totalAngle), 360));
	}

	/** Direction a player walks to go up at this column: the spiral's tangent, rounded to a cardinal direction. */
	public static Facing ascent(int x, int z, int dir) {
		double phi = Math.atan2(z, x);
		return Facing.nearest(-Math.sin(phi) * dir, Math.cos(phi) * dir);
	}

	/** Block y of a main step whose walking surface is at {@code top} half blocks. */
	static int mainY(PartType type, int top, SlabMode slabMode) {
		if (type == PartType.SLABS) {
			return switch (slabMode) {
				case AUTOMATIC -> (top - 1) / 2;
				case BOTTOM -> (top - 1) / 2;
				case TOP, DOUBLE -> (top + 1) / 2 - 1;
			};
		}

		return (top + 1) / 2 - 1;
	}

	static BlockShape mainShape(PartType type, int top, Facing ascent, SlabMode slabMode) {
		return switch (type) {
			case STAIRS -> BlockShape.stairs(ascent, BlockShape.Half.BOTTOM);
			case SLABS -> switch (slabMode) {
				case AUTOMATIC -> (top & 1) == 1 ? BlockShape.BOTTOM_SLAB : BlockShape.TOP_SLAB;
				case BOTTOM -> BlockShape.BOTTOM_SLAB;
				case TOP -> BlockShape.TOP_SLAB;
				case DOUBLE -> BlockShape.DOUBLE_SLAB;
			};
			default -> BlockShape.FULL;
		};
	}

	private static void placeSupport(GeometryBuilder out, PartType support, int x, int y, int z, Facing ascent, int depth, int baseY) {
		if (y - 1 < baseY) return;

		switch (support) {
			case BLOCKS -> {
				for (int i = 1; i <= depth && y - i >= baseY; i++) {
					out.setIfAbsent(x, y - i, z, MaterialRole.SUPPORT, BlockShape.FULL);
				}
			}
			case SLABS -> out.setIfAbsent(x, y - 1, z, MaterialRole.SUPPORT, BlockShape.TOP_SLAB);
			case STAIRS -> out.setIfAbsent(x, y - 1, z, MaterialRole.SUPPORT, BlockShape.stairs(ascent.opposite(), BlockShape.Half.TOP));
			default -> {
			}
		}
	}

	private static void landing(GeometryBuilder out, Mask2D annulus, int outer, double fromAngle, double toAngle, int dir, int y) {
		double lo = Math.min(fromAngle, toAngle);
		double hi = Math.max(fromAngle, toAngle);

		for (int v = 0; v < annulus.height(); v++) {
			for (int u = 0; u < annulus.width(); u++) {
				if (!annulus.contains(u, v)) continue;

				int x = u - outer;
				int z = v - outer;
				double phi = Math.toDegrees(Math.atan2(z, x));

				for (int k = -2; k <= 2; k++) {
					double a = phi + 360 * k;

					if (a >= lo && a < hi) {
						Placement existing = out.get(x, y, z);

						if (existing == null) out.set(x, y, z, MaterialRole.FLOOR, BlockShape.FULL);
						break;
					}
				}
			}
		}
	}

	@Override
	public Set<DetailFeature> supportedDetails() {
		return EnumSet.of(DetailFeature.OUTER_RAIL, DetailFeature.INNER_RAIL, DetailFeature.SUPPORT_PILLARS,
				DetailFeature.CENTRAL_COLUMN, DetailFeature.STEP_TRIM, DetailFeature.ALTERNATE_STEPS, DetailFeature.LANDING,
				DetailFeature.RING_SUPPORT, DetailFeature.WALL_ATTACHMENT);
	}

	@Override
	public Set<DetailFeature> presetDetails(DetailPreset preset) {
		return switch (preset) {
			case NONE, CUSTOM -> EnumSet.noneOf(DetailFeature.class);
			case SIMPLE -> EnumSet.of(DetailFeature.CENTRAL_COLUMN);
			case DETAILED -> EnumSet.of(DetailFeature.CENTRAL_COLUMN, DetailFeature.OUTER_RAIL, DetailFeature.LANDING);
			case ARCHITECTURAL -> EnumSet.of(DetailFeature.CENTRAL_COLUMN, DetailFeature.SUPPORT_PILLARS, DetailFeature.OUTER_RAIL,
					DetailFeature.LANDING, DetailFeature.STEP_TRIM);
			case DECORATIVE -> EnumSet.of(DetailFeature.CENTRAL_COLUMN, DetailFeature.ALTERNATE_STEPS, DetailFeature.OUTER_RAIL,
					DetailFeature.INNER_RAIL);
		};
	}

	@Override
	public Set<MaterialRole> roles() {
		return EnumSet.of(MaterialRole.STEP, MaterialRole.SUPPORT, MaterialRole.TRIM, MaterialRole.RAIL, MaterialRole.ACCENT,
				MaterialRole.FLOOR, MaterialRole.PRIMARY);
	}

	@Override
	public List<String> dimensionKeys() {
		return List.of("radius", "inner_radius", "width", "height", "revolutions", "rise_per_revolution", "steps");
	}
}
