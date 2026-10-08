package com.howtobuild.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.howtobuild.TestShapes;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.geometry.Box;
import com.howtobuild.geometry.CircularFootprint;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.GeometryValidator;
import com.howtobuild.geometry.Guide;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.tools.impl.SpiralStaircaseTool;

class SpiralInsetTest {
	private static GeometryResult fit(int size, int wall, int clearance, Object... extra) {
		java.util.List<Object> kv = new java.util.ArrayList<>(java.util.List.of("circle_mode", "FIT_INSIDE", "circle_width", size, "wall_thickness", wall,
				"clearance", clearance, "stair_width", 3, "height", 12));
		kv.addAll(java.util.List.of(extra));
		return TestShapes.generate("spiral", TestShapes.details(), kv.toArray());
	}

	private static Set<String> columns(GeometryResult r) {
		return r.placements().stream().map(p -> p.x() + "," + p.z()).collect(Collectors.toSet());
	}

	@Test
	void sixteenWithInsetsZeroOneTwo() {
		GeometryResult follow = TestShapes.generate("spiral", "circle_mode", "FOLLOW", "circle_width", 16, "stair_width", 3, "height", 12);

		for (int inset = 0; inset <= 2; inset++) {
			GeometryResult r = fit(16, inset, 0);
			Box b = r.bounds();
			assertEquals(16 - 2 * inset, b.sizeX(), "inset " + inset + ": width");
			assertEquals(16 - 2 * inset, b.sizeZ(), "inset " + inset + ": length");
			assertEquals(inset, r.values().get("inset").intValue());
			assertEquals(4, r.centreCells().size(), "the 2×2 centre is kept");
			assertFalse(r.warnings().stream().anyMatch(w -> w.startsWith("⚠") || GeometryValidator.isBlocking(w)), r.warnings().toString());

			if (inset == 0) assertEquals(columns(follow), columns(r), "inset 0 is the followed circle");
		}
	}

	@Test
	void thirtyTwoWithTwoByTwoCentreAndClearance() {
		GeometryResult r = fit(32, 1, 1);
		assertEquals(28, r.bounds().sizeX());
		assertEquals(28, r.bounds().sizeZ());
		assertEquals(4, r.centreCells().size());
		assertTrue(r.guides().stream().anyMatch(g -> g[3] == Guide.INSET), "the usable boundary is shown");
		assertTrue(r.guides().stream().anyMatch(g -> g[3] == Guide.OUTLINE), "the circle is shown");
	}

	@Test
	void nothingEntersTheWallOrClearanceExceptTheWall() {
		ToolSettings s = TestShapes.settings("spiral", "circle_mode", "FIT_INSIDE", "circle_width", 21, "wall_thickness", 2, "clearance", 1);
		CircularFootprint master = SpiralStaircaseTool.footprint(s, GenerationContext.DEFAULT);
		CircularFootprint usable = SpiralStaircaseTool.fitInside(master, 3);
		CircularFootprint wall = master.without(master.inset(2));
		GeometryResult r = TestShapes.generate("spiral", TestShapes.details(DetailFeature.WALL_ATTACHMENT, DetailFeature.OUTER_RAIL, DetailFeature.CENTRAL_COLUMN,
				DetailFeature.LANDING, DetailFeature.SUPPORT_PILLARS), "circle_mode", "FIT_INSIDE", "circle_width", 21, "wall_thickness", 2, "clearance", 1);
		int wallBlocks = 0;

		for (Placement p : r.placements()) {
			if (usable.contains(p.x(), p.z())) continue;

			assertTrue(wall.contains(p.x(), p.z()), "only the wall leaves the usable area: " + p);
			assertEquals(MaterialRole.PRIMARY, p.role());
			wallBlocks++;
		}

		assertTrue(wallBlocks > 0, "the wall fills the wall zone");
		assertFalse(r.warnings().stream().anyMatch(w -> w.contains("report")), r.warnings().toString());
	}

	@Test
	void manualInsetAndValidation() {
		GeometryResult r = TestShapes.generate("spiral", TestShapes.details(), "circle_mode", "FIT_INSIDE", "circle_width", 20, "auto_inset", false, "inset", 3);
		assertEquals(14, r.bounds().sizeX());
		ValidationResult tooBig = ToolRegistry.get("spiral").validate(TestShapes.settings("spiral", "circle_mode", "FIT_INSIDE", "circle_width", 6,
				"wall_thickness", 2, "clearance", 1), GenerationContext.DEFAULT);
		assertFalse(tooBig.ok(), "no room inside a 6 circle with 3 blocks inset");
	}

	@Test
	void outerAndInnerEdgeOptions() {
		GeometryResult simple = fit(21, 1, 0);

		for (SpiralStaircaseTool.OuterEdge edge : SpiralStaircaseTool.OuterEdge.values()) {
			GeometryResult r = fit(21, 1, 0, "outer_edge", edge.name(), "edge_thickness", 1);
			assertFalse(r.warnings().stream().anyMatch(GeometryValidator::isBlocking), edge + ": " + r.warnings());

			if (edge == SpiralStaircaseTool.OuterEdge.SIMPLE) continue;

			assertTrue(r.count(MaterialRole.OUTER_EDGE) + r.count(MaterialRole.TRIM) + r.count(MaterialRole.HIGHLIGHT) > 0, edge + " marks the edge");

			if (edge == SpiralStaircaseTool.OuterEdge.STRAIGHT) {
				assertEquals(TestShapes.positions(simple), TestShapes.positions(r), "a straight edge only changes the material");
			}
		}

		for (SpiralStaircaseTool.InnerEdge edge : SpiralStaircaseTool.InnerEdge.values()) {
			GeometryResult r = fit(21, 1, 0, "inner_radius", 4, "inner_edge", edge.name());
			assertFalse(r.warnings().stream().anyMatch(GeometryValidator::isBlocking), edge + ": " + r.warnings());

			if (edge != SpiralStaircaseTool.InnerEdge.OPEN && edge != SpiralStaircaseTool.InnerEdge.INNER_RAIL) {
				assertTrue(r.count(MaterialRole.INNER_EDGE) > 0, edge + " uses the inner edge material");
			}
		}
	}

	@Test
	void thickerEdgesCoverMoreBlocks() {
		long one = fit(25, 1, 0, "outer_edge", "STRAIGHT", "edge_thickness", 1).count(MaterialRole.OUTER_EDGE);
		long two = fit(25, 1, 0, "outer_edge", "STRAIGHT", "edge_thickness", 2).count(MaterialRole.OUTER_EDGE);
		assertTrue(two > one && one > 0);
	}

	@Test
	void everyStepCarriesItsGroup() {
		GeometryResult r = fit(21, 1, 0);
		long steps = r.values().get("steps").longValue();
		Set<Integer> groups = r.placements().stream().filter(p -> p.role() == MaterialRole.STEP).map(p -> r.groups().get(p.key()))
				.collect(Collectors.toSet());
		assertEquals(steps, groups.size());
	}
}
