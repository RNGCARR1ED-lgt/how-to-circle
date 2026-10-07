package com.howtobuild.tools.impl;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailPreset;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.details.SlabMode;
import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.CentreSize;
import com.howtobuild.geometry.CircularFootprint;
import com.howtobuild.geometry.GeometryCenter;
import com.howtobuild.geometry.Facing;
import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.MaterialRole;
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
 * <h2>Footprint</h2>
 * The staircase lives in a {@link CircularFootprint}, the same object the Circle and Oval tools build from:
 * <ul>
 *     <li>with its own radius, a circle of diameter {@code 2r + 1} (1×1 centre) or {@code 2r} (2×2 centre);</li>
 *     <li>with <b>Follow Circle Dimensions</b>, the master circle or oval of exactly {@code width × length} blocks, so every
 *     block of the staircase, including all details, is one of the circle's cells.</li>
 * </ul>
 * Stair width grows inwards from the fixed outer boundary (exact erosion), or an inner radius cuts the hole. Angles and
 * stair facing are measured from the footprint's true centre, so even-sized (2×2 centre) spirals are symmetric. The
 * anchor layer is the base: nothing is generated below it.
 *
 * <h2>Steps</h2>
 * The full rotation (revolutions × 360° + extra angle) is divided into {@code N} equal angular steps, where {@code N} is the
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

	/** Shape of the master footprint when following circle dimensions. */
	public enum MasterShape {
		CIRCLE,
		OVAL
	}

	@Override
	public List<ToolParameter> parameters() {
		return List.of(
				ToolParameter.integer("outer_radius", 1, 128, 5).visibleWhen(s -> !s.getBool("follow_circle")),
				ToolParameter.integer("stair_width", 1, 64, 3),
				ToolParameter.integer("height", 2, 384, 16),
				ToolParameter.integer("revolutions", 0, 50, 2),
				ToolParameter.choice("direction", Direction.COUNTER_CLOCKWISE),
				ToolParameter.integer("extra_angle", 0, 359, 0).advanced(),
				ToolParameter.integer("step_rise", 1, 4, 1).advanced(),
				ToolParameter.integer("start_angle", 0, 359, 0).advanced(),
				ToolParameter.integer("start_height", 0, 128, 0).advanced(),
				ToolParameter.bool("follow_circle", false).section("circle"),
				ToolParameter.choice("master_shape", MasterShape.CIRCLE).section("circle").visibleWhen(s -> s.getBool("follow_circle")),
				ToolParameter.integer("circle_width", 1, 1024, 33).section("circle").visibleWhen(s -> s.getBool("follow_circle")),
				ToolParameter.integer("circle_length", 1, 1024, 33).section("circle")
						.visibleWhen(s -> s.getBool("follow_circle") && s.getEnum("master_shape", MasterShape.class) == MasterShape.OVAL),
				ToolParameter.integer("inner_radius", 0, 512, 0).section("circle"),
				ToolParameter.bool("allow_outside", false).section("circle"),
				ToolParameter.choice("centre_size", CentreSize.AUTO).section("centre"),
				ToolParameter.choice("main_step", PartType.AUTO).section("staircase"),
				ToolParameter.choice("support", PartType.AUTO).section("staircase"),
				ToolParameter.choice("trim", PartType.AUTO).section("staircase"),
				ToolParameter.integer("support_depth", 1, 8, 1).section("staircase").advanced());
	}

	/** Width (X) and length (Z) of the outer footprint for these settings. */
	public static int[] footprintSize(ToolSettings s) {
		if (s.getBool("follow_circle")) {
			int w = s.getInt("circle_width");
			int l = s.getEnum("master_shape", MasterShape.class) == MasterShape.CIRCLE ? w : s.getInt("circle_length");
			return new int[] {w, l};
		}

		int r = s.getInt("outer_radius");
		int d = s.getEnum("centre_size", CentreSize.class) == CentreSize.TWO_BY_TWO ? 2 * r : 2 * r + 1;
		return new int[] {d, d};
	}

	/**
	 * The master footprint: the exact cells of the circle (or oval) the staircase must stay inside. With Follow Circle
	 * Dimensions these are the very cells a Circle tool of the same size and centre builds.
	 */
	public static CircularFootprint footprint(ToolSettings s, GenerationContext ctx) {
		int[] size = footprintSize(s);
		return CircularFootprint.of(size[0], size[1], ctx.alignX(), ctx.alignZ());
	}

	/** The cells holding steps: a ring of the stair width inside the (possibly inset) outer boundary, or minus the inner hole. */
	static CircularFootprint stepArea(ToolSettings s, CircularFootprint outer) {
		int innerRadius = s.getInt("inner_radius");

		if (innerRadius <= 0) return outer.ring(s.getInt("stair_width"));

		int holeW = Math.max(0, 2 * innerRadius - (outer.width() % 2));
		int holeL = Math.max(0, 2 * innerRadius - (outer.length() % 2));

		if (holeW <= 0 || holeL <= 0) return outer;

		return outer.minus(CircularFootprint.of(holeW, holeL, outer.centre().alignX(), outer.centre().alignZ()));
	}

	/** Whether wall-type details take the footprint's outer ring (pushing the steps one block inwards). */
	static boolean wallInside(ToolSettings s, DetailSettings d) {
		return (d.has(DetailFeature.WALL_ATTACHMENT) || d.has(DetailFeature.RING_SUPPORT)) && !s.getBool("allow_outside");
	}

	@Override
	public long estimateBlocks(ToolSettings s) {
		int[] size = footprintSize(s);
		return (long) size[0] * size[1] * Math.max(1, s.getInt("height") + s.getInt("start_height") + 2);
	}

	/** Total rotation in degrees. */
	public static int totalAngle(ToolSettings s) {
		return s.getInt("revolutions") * 360 + s.getInt("extra_angle");
	}

	/** Nominal inner radius: the explicit inner radius, or the outer radius minus the stair width. */
	public static double innerRadius(ToolSettings s) {
		if (s.getInt("inner_radius") > 0) return s.getInt("inner_radius");

		int[] size = footprintSize(s);
		return Math.max(0, Math.min(size[0], size[1]) / 2.0 - s.getInt("stair_width"));
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

		if (s.getBool("follow_circle")) {
			int[] size = footprintSize(s);
			String incompatible = GeometryCenter.incompatibility(s.getEnum("centre_size", CentreSize.class), size[0], size[1]);

			if (incompatible != null) r.error(incompatible);
		}

		if (!r.ok()) return r;

		int[] size = footprintSize(s);
		double half = Math.min(size[0], size[1]) / 2.0;
		r.warnIf(s.getInt("inner_radius") <= 0 && width >= half, "⚠ Stair width (" + width + ") is larger than the outer radius ("
				+ (s.getBool("follow_circle") ? half : outer) + "); the steps meet in the middle.");
		r.warnIf(s.getInt("inner_radius") > 0 && s.getInt("inner_radius") >= half,
				"⚠ The inner radius (" + s.getInt("inner_radius") + ") leaves no room for steps inside the outer boundary (radius " + half + ").");

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
		boolean allowOutside = s.getBool("allow_outside");

		// One footprint is the source of truth; every part below is derived from it.
		CircularFootprint master = footprint(s, ctx);
		GeometryCenter centre = master.centre();
		boolean wallInside = wallInside(s, d);
		CircularFootprint stepsOuter = wallInside ? master.inset(1) : master;
		CircularFootprint area = stepArea(s, stepsOuter);
		CircularFootprint interior = stepsOuter.without(area);
		CircularFootprint outerEdge = stepsOuter.boundary();
		double radius = Math.max(1, Math.max(master.width(), master.length()) / 2.0);
		int baseY = startUnits / 2;
		int[] intersections = {0};

		area.forEach((x, z) -> {
			double phi = centre.angleDegrees(x, z);
			double relative = Math.floorMod((long) Math.floor((dir * (phi - startAngle)) * 1000), 360_000L) / 1000.0;
			boolean outerCell = outerEdge.contains(x, z);
			boolean innerCell = interior.contains(x - 1, z) || interior.contains(x + 1, z) || interior.contains(x, z - 1) || interior.contains(x, z + 1);
			Facing ascent = ascent(centre, x, z, dir);

			for (double angle = relative; angle < totalAngle; angle += 360) {
				int step = (int) Math.floor(angle / stepAngle);

				if (step >= steps) break;

				int top = startUnits + (step + 1) * rise; // top of the walking surface, in half blocks
				MaterialRole stepRole = d.has(DetailFeature.ALTERNATE_STEPS) && (step & 1) == 1 ? MaterialRole.ACCENT : MaterialRole.STEP;
				PartType type = trimEnabled && outerCell ? trimType : a.main();
				MaterialRole role = trimEnabled && outerCell ? MaterialRole.TRIM : stepRole;
				int y = mainY(type, top, ctx.slabMode());

				if (out.has(x, y, z)) intersections[0]++;

				out.set(x, y, z, role, mainShape(type, top, ascent, ctx.slabMode()));
				placeSupport(out, a.support(), x, y, z, ascent, supportDepth, baseY);

				if (d.has(DetailFeature.OUTER_RAIL) && outerCell) out.setIfAbsent(x, y + 1, z, MaterialRole.RAIL, BlockShape.FULL);
				if (d.has(DetailFeature.INNER_RAIL) && innerCell) out.setIfAbsent(x, y + 1, z, MaterialRole.RAIL, BlockShape.FULL);

				if (d.has(DetailFeature.SUPPORT_PILLARS) && outerCell && step % Math.max(2, d.interval()) == 0
						&& Math.abs(angle - (step + 0.5) * stepAngle) < 360.0 / (2 * Math.PI * radius) * 0.75) {
					for (int py = y - 1; py >= baseY; py--) {
						out.setIfAbsent(x, py, z, MaterialRole.SUPPORT, BlockShape.FULL);
					}
				}
			}
		});

		int topY = (startUnits + steps * rise + 1) / 2;

		if (d.has(DetailFeature.CENTRAL_COLUMN)) {
			interior.forEach((x, z) -> {
				for (int y = baseY; y < topY; y++) {
					out.setIfAbsent(x, y, z, MaterialRole.SUPPORT, BlockShape.FULL);
				}
			});
		}

		if (d.has(DetailFeature.WALL_ATTACHMENT) || d.has(DetailFeature.RING_SUPPORT)) {
			// Inside the boundary by default: the wall is the master circle's own outer ring.
			CircularFootprint wall = wallInside ? master.boundary()
					: CircularFootprint.of(master.width() + 2, master.length() + 2, centre.alignX(), centre.alignZ()).boundary();

			wall.forEach((x, z) -> {
				for (int y = baseY; y <= topY; y++) {
					boolean band = d.has(DetailFeature.RING_SUPPORT) && (y - baseY) % Math.max(2, d.interval()) == 0;

					if (d.has(DetailFeature.WALL_ATTACHMENT) || band) {
						out.setIfAbsent(x, y, z, band ? MaterialRole.SUPPORT : MaterialRole.PRIMARY, BlockShape.FULL);
					}
				}
			});
		}

		if (d.has(DetailFeature.LANDING)) {
			// The bottom landing is the base layer itself (never below it); the top landing is level with the last step.
			landing(out, area, centre, startAngle - dir * 90, startAngle, baseY);
			double end = startAngle + dir * totalAngle;
			landing(out, area, centre, end, end + dir * 90, topY - 1);
		}

		if (intersections[0] > 0) out.warn("⚠ This configuration causes geometry overlap: " + intersections[0] + " step blocks intersect a lower turn.");

		// Containment check against the master footprint (never clips silently).
		long outside = out.snapshot().stream().filter(p -> !master.contains(p.x(), p.z())).count();

		if (outside > 0) {
			out.warn(allowOutside
					? "⚠ " + outside + " detail blocks extend outside the master circle (Allow details outside boundary is on)."
					: "⚠ Spiral exceeds master circle boundary by " + outside + " blocks. Please report this.");
		}

		if (s.getBool("follow_circle")) {
			master.boundary().forEach((x, z) -> out.guide(x, 0, z));
		}

		centre.addCentreCells(out, 0);
		out.value("radius", radius);
		out.value("inner_radius", innerRadius(s));
		out.value("stair_width", s.getInt("stair_width"));
		out.value("footprint_width", master.width());
		out.value("footprint_length", master.length());
		out.value("height", s.getInt("height"));
		out.value("revolutions", totalAngle / 360.0);
		out.value("rise_per_revolution", s.getInt("height") * 360.0 / totalAngle);
		out.value("steps", steps);
		out.value("step_angle", stepAngle);
		out.value("end_angle", Math.floorMod(Math.round(startAngle + dir * totalAngle), 360));
	}

	/** Direction a player walks to go up at this column: the spiral's tangent, rounded to a cardinal direction. */
	public static Facing ascent(int x, int z, int dir) {
		return ascent(GeometryCenter.of(1, 1, true, true), x, z, dir);
	}

	/** The tangent at block (x, z) around the footprint's true centre (exact for 1×1 and 2×2 centres). */
	public static Facing ascent(GeometryCenter centre, int x, int z, int dir) {
		double phi = Math.atan2(centre.dz(z), centre.dx(x));
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

	private static void landing(GeometryBuilder out, CircularFootprint area, GeometryCenter centre, double fromAngle, double toAngle, int y) {
		double lo = Math.min(fromAngle, toAngle);
		double hi = Math.max(fromAngle, toAngle);

		area.forEach((x, z) -> {
			double phi = centre.angleDegrees(x, z);

			for (int k = -2; k <= 2; k++) {
				double angle = phi + 360 * k;

				if (angle >= lo && angle < hi) {
					if (out.get(x, y, z) == null) out.set(x, y, z, MaterialRole.FLOOR, BlockShape.FULL);
					break;
				}
			}
		});
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
		return List.of("radius", "inner_radius", "stair_width", "footprint_width", "footprint_length", "height", "revolutions",
				"rise_per_revolution", "steps");
	}
}
