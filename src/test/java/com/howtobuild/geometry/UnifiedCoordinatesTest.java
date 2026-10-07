package com.howtobuild.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.howtobuild.TestShapes;
import com.howtobuild.commands.CommandPlan;
import com.howtobuild.commands.CommandPlanner;
import com.howtobuild.commands.StatePlacement;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailPreset;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.GeometryPipeline;
import com.howtobuild.tools.ToolRegistry;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.VerticalAnchor;
import com.howtobuild.tools.capability.Detailable;

/**
 * The single coordinate, centre and dimension system: circle and spiral share footprints and centres, offsets move
 * everything identically, nothing goes below the base layer, rotation keeps 2×2 centres, and the command build covers
 * exactly the preview's blocks.
 */
class UnifiedCoordinatesTest {
	/** (x, z) columns of a filled circle or oval of the given size, exactly as the Circle / Oval tool builds them. */
	private static Set<Long> circleColumns(GenerationContext ctx, int width, int length) {
		GeometryResult circle = width == length
				? TestShapes.generate("circle", ctx, "size", width, "fill", "FILLED")
				: TestShapes.generate("oval", ctx, "width", width, "length", length, "fill", "FILLED");
		Set<Long> columns = new HashSet<>();

		for (Placement p : circle.placements()) {
			columns.add(Voxels.pack(p.x(), 0, p.z()));
		}

		return columns;
	}

	static Stream<Arguments> masters() {
		return Stream.of(Arguments.of(33, 33), Arguments.of(32, 32), Arguments.of(15, 15), Arguments.of(16, 16), Arguments.of(21, 13),
				Arguments.of(20, 12), Arguments.of(7, 7));
	}

	@ParameterizedTest(name = "master {0} × {1}")
	@MethodSource("masters")
	void spiralFollowingACircleNeverLeavesTheCircle(int width, int length) {
		for (EnumSet<ShapeKind> types : List.of(EnumSet.of(ShapeKind.BLOCKS), EnumSet.of(ShapeKind.SLABS), EnumSet.of(ShapeKind.STAIRS),
				EnumSet.allOf(ShapeKind.class))) {
			for (int stairWidth = 1; stairWidth <= 5; stairWidth++) {
				for (DetailPreset preset : DetailPreset.values()) {
					BuildTool spiral = ToolRegistry.get("spiral");
					DetailSettings details = DetailSettings.of(((Detailable) spiral).presetDetails(preset).toArray(new DetailFeature[0]));
					GenerationContext ctx = GenerationContext.DEFAULT.withMaterialTypes(types).withDetails(details);
					Set<Long> circle = circleColumns(GenerationContext.DEFAULT, width, length);
					GeometryResult r = TestShapes.generate("spiral", ctx, "follow_circle", true, "master_shape", width == length ? "CIRCLE" : "OVAL",
							"circle_width", width, "circle_length", length, "stair_width", stairWidth, "height", 12, "revolutions", 1);
					assertFalse(r.isEmpty(), r.warnings().toString());

					for (Placement p : r.placements()) {
						assertTrue(circle.contains(Voxels.pack(p.x(), 0, p.z())),
								"block " + p + " is outside the " + width + "×" + length + " circle (" + types + ", width " + stairWidth + ", " + preset + ")");
					}

					assertTrue(r.warnings().stream().noneMatch(w -> w.contains("exceeds")), r.warnings().toString());
				}
			}
		}
	}

	@Test
	void spiralOuterBoundaryMatchesTheCircleExactly() {
		GeometryResult circle = TestShapes.generate("circle", "size", 33, "fill", "FILLED");
		GeometryResult spiral = TestShapes.generate("spiral", "follow_circle", true, "circle_width", 33, "stair_width", 3);
		assertEquals(circle.bounds().minX(), spiral.bounds().minX());
		assertEquals(circle.bounds().maxX(), spiral.bounds().maxX());
		assertEquals(circle.bounds().minZ(), spiral.bounds().minZ());
		assertEquals(circle.bounds().maxZ(), spiral.bounds().maxZ());
		assertEquals(33, spiral.width());
		assertEquals(33, spiral.length());
	}

	@Test
	void stairWidthGrowsInwardsWhileTheOuterBoundaryStaysFixed() {
		int previous = 0;

		for (int width = 1; width <= 5; width++) {
			GeometryResult r = TestShapes.generate("spiral", "follow_circle", true, "circle_width", 33, "stair_width", width);
			assertEquals(33, r.width(), "outer boundary fixed for width " + width);
			int columns = (int) r.placements().stream().map(p -> Voxels.pack(p.x(), 0, p.z())).distinct().count();
			assertTrue(columns > previous, "wider stairs cover more of the disc");
			previous = columns;
		}
	}

	@Test
	void innerRadiusCutsTheHoleAroundTheSameCentre() {
		GeometryResult r = TestShapes.generate("spiral", "follow_circle", true, "circle_width", 33, "inner_radius", 6);
		assertEquals(33, r.width());

		for (Placement p : r.placements()) {
			double distance = Math.hypot(p.x(), p.z());
			assertTrue(distance >= 5, "no step inside the inner radius: " + p);
		}

		assertEquals(6.0, r.values().get("inner_radius"));
	}

	@ParameterizedTest(name = "size {0}")
	@ValueSource(ints = {32, 33, 16, 15})
	void circleAndSpiralShareTheSameCentreCells(int size) {
		GeometryResult circle = TestShapes.generate("circle", "size", size);
		GeometryResult spiral = TestShapes.generate("spiral", "follow_circle", true, "circle_width", size);
		assertEquals(columns(circle.centreCells()), columns(spiral.centreCells()));
		assertEquals(size % 2 == 0 ? 4 : 1, spiral.centreCells().size());

		for (boolean alignX : new boolean[] {true, false}) {
			GenerationContext ctx = GenerationContext.DEFAULT.withAlignment(alignX, true, !alignX);
			assertEquals(columns(TestShapes.generate("circle", ctx, "size", size).centreCells()),
					columns(TestShapes.generate("spiral", ctx, "follow_circle", true, "circle_width", size).centreCells()));
		}
	}

	@Test
	void twoByTwoSpiralWithItsOwnRadiusIsSymmetricAroundTheCorner() {
		GeometryResult r = TestShapes.generate("spiral", "diameter", 16, "stair_width", 3);
		assertEquals(16, r.width());
		assertEquals(16, r.length());
		assertEquals(4, r.centreCells().size());
		Set<Long> circle = circleColumns(GenerationContext.DEFAULT, 16, 16);

		for (Placement p : r.placements()) {
			assertTrue(circle.contains(Voxels.pack(p.x(), 0, p.z())), "inside the 16 × 16 circle: " + p);
		}
	}

	/** Odd and even sizes are both native: the footprint is exactly the requested size, never rounded to odd. */
	@ParameterizedTest(name = "{0} × {0}")
	@ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 16, 30, 31, 32, 33, 34, 64})
	void spiralKeepsEveryOddAndEvenSizeExactly(int size) {
		for (boolean follow : new boolean[] {false, true}) {
			GeometryResult r = follow
					? TestShapes.generate("spiral", "follow_circle", true, "circle_width", size, "stair_width", 2, "height", 8, "revolutions", 1)
					: TestShapes.generate("spiral", "diameter", size, "stair_width", 2, "height", 8, "revolutions", 1);
			String mode = (follow ? "following a circle" : "own size") + " " + size;
			assertFalse(r.isEmpty(), mode + ": " + r.warnings());
			assertEquals(size, r.width(), mode + ": exact width");
			assertEquals(size, r.length(), mode + ": exact length");

			int xs = (int) r.centreCells().stream().mapToInt(c -> c[0]).distinct().count();
			int zs = (int) r.centreCells().stream().mapToInt(c -> c[2]).distinct().count();
			int expected = size % 2 == 0 ? 2 : 1;
			assertEquals(expected, xs, mode + ": centre width");
			assertEquals(expected, zs, mode + ": centre length");

			// The centre is the geometric middle of the footprint: equal distance to both edges on each axis.
			int minCx = r.centreCells().stream().mapToInt(c -> c[0]).min().orElseThrow();
			int maxCx = r.centreCells().stream().mapToInt(c -> c[0]).max().orElseThrow();
			assertEquals(minCx - r.bounds().minX(), r.bounds().maxX() - maxCx, mode + ": centred along X");
			int minCz = r.centreCells().stream().mapToInt(c -> c[2]).min().orElseThrow();
			int maxCz = r.centreCells().stream().mapToInt(c -> c[2]).max().orElseThrow();
			assertEquals(minCz - r.bounds().minZ(), r.bounds().maxZ() - maxCz, mode + ": centred along Z");

			if (size >= 3) {
				GeometryResult circle = TestShapes.generate("circle", "size", size, "fill", "FILLED");
				assertEquals(circle.bounds().minX(), r.bounds().minX(), mode + ": same footprint as the circle");
				assertEquals(columns(circle.centreCells()), columns(r.centreCells()), mode + ": same centre as the circle");
			}
		}
	}

	@Test
	void incompatibleCentreModeIsReportedNotGenerated() {
		GeometryResult r = TestShapes.generate("spiral", "follow_circle", true, "circle_width", 33, "centre_size", "TWO_BY_TWO");
		assertTrue(r.isEmpty());
		assertTrue(r.warnings().stream().anyMatch(w -> w.contains("2×2 centre needs even")), r.warnings().toString());
	}

	@Test
	void offsetsMoveCircleAndSpiralIdentically() {
		WorldTransform t = new WorldTransform(100, 77, 200, 7, -3, 4);
		GeometryResult circle = TestShapes.generate("circle", "size", 33, "fill", "FILLED");
		GeometryResult spiral = TestShapes.generate("spiral", "follow_circle", true, "circle_width", 33);
		Set<Long> circleWorld = new HashSet<>();

		for (Placement p : circle.placements()) {
			int[] w = t.apply(p);
			circleWorld.add(Voxels.pack(w[0] - 64, 0, w[2] - 192));
		}

		for (Placement p : spiral.placements()) {
			int[] w = t.apply(p);
			assertTrue(circleWorld.contains(Voxels.pack(w[0] - 64, 0, w[2] - 192)), "spiral stays in the moved circle");
			assertTrue(w[1] >= 74, "nothing below the moved base layer");
		}

		assertEquals(107 - 16, t.x(circle.bounds().minX()));
		assertEquals(107 - 16, t.x(spiral.bounds().minX()));
		assertEquals(204 + 16, t.z(spiral.bounds().maxZ()));
		assertEquals(74, t.y(spiral.bounds().minY()));
	}

	@Test
	void offsetYMinusOneMovesEverythingDownExactlyOneBlock() {
		GeometryResult spiral = TestShapes.generate("spiral", "follow_circle", true, "circle_width", 33);
		WorldTransform level = new WorldTransform(147, 77, -55, 0, 0, 0);
		WorldTransform down = new WorldTransform(147, 77, -55, 0, -1, 0);

		assertEquals(77, level.y(spiral.bounds().minY()), "with no offset the spiral starts exactly on the centre's layer");

		for (Placement p : spiral.placements()) {
			int[] a = level.apply(p);
			int[] b = down.apply(p);
			assertEquals(a[0], b[0]);
			assertEquals(a[1] - 1, b[1]);
			assertEquals(a[2], b[2]);
		}
	}

	/** Every tool, every detail preset and material combination: base-anchored tools never go below layer 0. */
	@Test
	void baseAnchoredToolsNeverGenerateBelowTheBase() {
		List<String> failures = new ArrayList<>();

		for (BuildTool tool : ToolRegistry.all()) {
			for (DetailPreset preset : DetailPreset.values()) {
				for (EnumSet<ShapeKind> types : List.of(EnumSet.of(ShapeKind.BLOCKS), EnumSet.allOf(ShapeKind.class))) {
					DetailSettings details = tool instanceof Detailable d
							? DetailSettings.of(d.presetDetails(preset).toArray(new DetailFeature[0])) : DetailSettings.NONE;
					GenerationContext ctx = GenerationContext.DEFAULT.withMaterialTypes(types).withDetails(details);

					for (ToolSettings settings : variants(tool)) {
						if (tool.verticalAnchor(settings) != VerticalAnchor.BASE) continue;

						GeometryResult r = GeometryPipeline.generate(tool, settings, ctx);

						if (!r.isEmpty() && r.bounds().minY() < 0) failures.add(tool.id() + " " + preset + " " + types + " " + settings + " minY " + r.bounds().minY());
						if (r.warnings().stream().anyMatch(w -> w.contains("Internal error"))) failures.add(tool.id() + " warns: " + r.warnings());
					}
				}
			}
		}

		assertTrue(failures.isEmpty(), String.join("\n", failures));
	}

	private static List<ToolSettings> variants(BuildTool tool) {
		ToolSettings base = tool.defaults();
		List<ToolSettings> list = new ArrayList<>(List.of(base));

		switch (tool.id()) {
			case "cylinder" -> {
				list.add(base.with("orientation", "ALONG_X"));
				list.add(base.with("orientation", "ALONG_Z").with("caps", "BOTH"));
			}
			case "spiral" -> {
				list.add(base.with("follow_circle", true).with("circle_width", 20));
				list.add(base.with("start_height", 3));
				list.add(base.with("allow_outside", true));
			}
			case "corridor" -> {
				list.add(base.with("profile", "SPHERE"));
				list.add(base.with("profile", "DOME").with("axis", "ALONG_X"));
			}
			case "dome" -> list.add(base.with("floor", true));
			default -> {
			}
		}

		return list;
	}

	@Test
	void rotationKeepsTwoByTwoCentresAndFootprints() {
		for (int turns = 1; turns <= 3; turns++) {
			GenerationContext ctx = GenerationContext.DEFAULT.withRotation(turns);
			GeometryResult base = TestShapes.generate("circle", "size", 32, "fill", "FILLED");
			GeometryResult turned = TestShapes.generate("circle", ctx, "size", 32, "fill", "FILLED");
			assertEquals(TestShapes.positions(base), TestShapes.positions(turned), "a 32 × 32 circle is unchanged by " + turns + " turns");
			assertEquals(columns(base.centreCells()), columns(turned.centreCells()));

			GeometryResult rect = TestShapes.generate("rectangle", "width", 8, "length", 4, "fill", "FILLED");
			GeometryResult rectTurned = TestShapes.generate("rectangle", ctx, "width", 8, "length", 4, "fill", "FILLED");
			assertEquals(columns(rect.centreCells()), columns(rectTurned.centreCells()), "the 2×2 centre stays put");
			assertEquals(turns % 2 == 1 ? 4 : 8, rectTurned.width());
		}
	}

	/** The command build covers exactly the preview's blocks: nothing more (no extra layer), nothing less. */
	@Test
	void commandsExpandToExactlyThePreviewBlocks() {
		WorldTransform t = new WorldTransform(147, 77, -55, 5, 0, -3);

		for (String tool : List.of("spiral", "circle", "corridor", "dome")) {
			GenerationContext ctx = GenerationContext.DEFAULT.withMaterialTypes(EnumSet.allOf(ShapeKind.class))
					.withDetails(DetailSettings.of(DetailFeature.LANDING, DetailFeature.CENTRAL_COLUMN, DetailFeature.OUTER_RAIL, DetailFeature.ARCH_FRAMES));
			GeometryResult r = TestShapes.generate(tool, ctx);
			List<StatePlacement> blocks = new ArrayList<>();

			for (Placement p : r.placements()) {
				int[] w = t.apply(p);
				blocks.add(new StatePlacement(w[0], w[1], w[2], "minecraft:" + p.role().name().toLowerCase() + "[" + p.shape() + "]"));
			}

			CommandPlan plan = CommandPlanner.plan(blocks, CommandPlanner.Options.DEFAULT);
			Map<Long, String> built = CommandPlanner.expand(plan.commands());
			assertEquals(blocks.size(), built.size(), tool + ": every planned block, and only those");

			for (StatePlacement b : blocks) {
				assertEquals(b.state(), built.get(Voxels.pack(b.x(), b.y(), b.z())), tool + ": state preserved at " + b);
				assertTrue(b.y() >= 77 || !tool.equals("spiral"), "nothing below the spiral's base");
			}
		}
	}

	private static Set<List<Integer>> columns(List<int[]> cells) {
		Set<List<Integer>> set = new HashSet<>();

		for (int[] c : cells) {
			set.add(List.of(c[0], c[2]));
		}

		return set;
	}
}
