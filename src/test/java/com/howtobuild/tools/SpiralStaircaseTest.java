package com.howtobuild.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.howtobuild.TestShapes;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.tools.impl.SpiralStaircaseTool;
import com.howtobuild.tools.impl.SpiralStaircaseTool.PartType;

class SpiralStaircaseTest {
	static Stream<Arguments> combinations() {
		return Stream.of(
				Arguments.of(EnumSet.of(ShapeKind.BLOCKS)),
				Arguments.of(EnumSet.of(ShapeKind.SLABS)),
				Arguments.of(EnumSet.of(ShapeKind.STAIRS)),
				Arguments.of(EnumSet.of(ShapeKind.BLOCKS, ShapeKind.SLABS)),
				Arguments.of(EnumSet.of(ShapeKind.BLOCKS, ShapeKind.STAIRS)),
				Arguments.of(EnumSet.of(ShapeKind.SLABS, ShapeKind.STAIRS)),
				Arguments.of(EnumSet.of(ShapeKind.BLOCKS, ShapeKind.SLABS, ShapeKind.STAIRS)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("combinations")
	void everyMaterialCombinationProducesACoherentStaircase(Set<ShapeKind> types) {
		GenerationContext ctx = GenerationContext.DEFAULT.withMaterialTypes(types);
		GeometryResult r = TestShapes.generate("spiral", ctx);
		assertFalse(r.isEmpty());
		assertTrue(r.warnings().isEmpty(), "defaults are valid: " + r.warnings());

		// Only selected material types appear.
		for (Placement p : r.placements()) {
			assertTrue(types.contains(p.shape().materialKind()), p + " uses an unselected type");
		}

		// Every selected type is used.
		for (ShapeKind kind : types) {
			assertTrue(r.placements().stream().anyMatch(p -> p.shape().materialKind() == kind), kind + " is used");
		}

		SpiralStaircaseTool.Assignment a = SpiralStaircaseTool.assignment(TestShapes.settings("spiral"), types);

		if (types.contains(ShapeKind.STAIRS)) assertEquals(PartType.STAIRS, a.main());
		else if (types.contains(ShapeKind.SLABS)) assertEquals(PartType.SLABS, a.main());
		else assertEquals(PartType.BLOCKS, a.main());

		assertHeightRises(r);
	}

	@Test
	void blocksSlabsAndStairsTogether() {
		GeometryResult r = TestShapes.generate("spiral", TestShapes.types(ShapeKind.BLOCKS, ShapeKind.SLABS, ShapeKind.STAIRS));
		SpiralStaircaseTool.Assignment a = SpiralStaircaseTool.assignment(TestShapes.settings("spiral"), EnumSet.allOf(ShapeKind.class));
		assertEquals(new SpiralStaircaseTool.Assignment(PartType.STAIRS, PartType.BLOCKS, PartType.SLABS), a);
		assertTrue(r.count(BlockShape.Kind.STAIRS) > 0);
		assertTrue(r.count(BlockShape.Kind.SLAB) > 0);
		assertTrue(r.count(BlockShape.Kind.FULL) > 0);
		assertTrue(r.count(MaterialRole.SUPPORT) > 0);
		assertTrue(r.count(MaterialRole.TRIM) > 0);
	}

	@Test
	void stairsFaceTheDirectionOfAscent() {
		for (String direction : List.of("CLOCKWISE", "COUNTER_CLOCKWISE")) {
			GeometryResult r = TestShapes.generate("spiral", TestShapes.types(ShapeKind.STAIRS), "direction", direction);
			int dir = direction.equals("CLOCKWISE") ? 1 : -1;

			for (Placement p : r.placements()) {
				if (p.role() == MaterialRole.STEP && p.shape().isStairs()) {
					assertEquals(SpiralStaircaseTool.ascent(p.x(), p.z(), dir), p.shape().facing(), "facing follows the tangent at " + p);
					assertEquals(BlockShape.Half.BOTTOM, p.shape().half());
				}
			}

			// Facing changes around the spiral (not one orientation repeated).
			assertEquals(4, r.placements().stream().filter(p -> p.shape().isStairs() && p.role() == MaterialRole.STEP)
					.map(p -> p.shape().facing()).distinct().count());
		}
	}

	@Test
	void slabStaircaseAlternatesBottomAndTopSlabs() {
		GeometryResult r = TestShapes.generate("spiral", TestShapes.types(ShapeKind.SLABS));
		assertTrue(r.placements().stream().anyMatch(p -> p.shape() == BlockShape.BOTTOM_SLAB));
		assertTrue(r.placements().stream().anyMatch(p -> p.shape() == BlockShape.TOP_SLAB));
		int steps = (int) Math.round(r.values().get("steps"));
		assertEquals(2 * 16, steps, "half-block steps");
	}

	@ParameterizedTest(name = "{0} revolutions")
	@ValueSource(ints = {1, 2, 5})
	void revolutions(int revolutions) {
		int height = 8 * revolutions;
		GeometryResult r = TestShapes.generate("spiral", TestShapes.types(ShapeKind.BLOCKS), "revolutions", revolutions, "height", height);
		assertEquals(revolutions, r.values().get("revolutions"));
		assertEquals(height, r.height(), "steps reach the full height");
		assertEquals(2 * 5 + 1, r.width());
		assertTrue(r.warnings().isEmpty(), r.warnings().toString());
	}

	@ParameterizedTest(name = "width {0}")
	@ValueSource(ints = {1, 2, 3, 5})
	void varyingWidthKeepsTheHole(int width) {
		GeometryResult r = TestShapes.generate("spiral", TestShapes.types(ShapeKind.BLOCKS), "outer_radius", 6, "stair_width", width);
		int inner = 6 - width;

		for (Placement p : r.placements()) {
			double radius = Math.sqrt(p.x() * p.x() + p.z() * p.z());
			assertTrue(radius >= inner - 1.0, "no step inside the inner radius: " + p);
			assertTrue(radius <= 6.5, "no step outside the outer radius: " + p);
		}
	}

	@Test
	void invalidSettingsWarnInsteadOfCrashing() {
		GeometryResult wide = TestShapes.generate("spiral", "outer_radius", 3, "stair_width", 6);
		assertTrue(wide.warnings().stream().anyMatch(w -> w.contains("larger than the outer radius")));
		GeometryResult flat = TestShapes.generate("spiral", "height", 4, "revolutions", 4);
		assertTrue(flat.warnings().stream().anyMatch(w -> w.contains("headroom")));
		GeometryResult noTurn = TestShapes.generate("spiral", "revolutions", 0, "extra_angle", 0);
		assertTrue(noTurn.isEmpty());
		assertFalse(noTurn.warnings().isEmpty());
	}

	@Test
	void detailsAreGenerated() {
		GeometryResult r = TestShapes.generate("spiral", TestShapes.details(DetailFeature.CENTRAL_COLUMN, DetailFeature.OUTER_RAIL,
				DetailFeature.LANDING, DetailFeature.SUPPORT_PILLARS, DetailFeature.WALL_ATTACHMENT), "outer_radius", 6, "stair_width", 3);
		assertTrue(r.count(MaterialRole.RAIL) > 0);
		assertTrue(r.count(MaterialRole.FLOOR) > 0);
		assertTrue(r.at(0, 5, 0) != null, "central column");
		assertEquals(2 * 6 + 3, r.width(), "wall attachment surrounds the stairs");
	}

	private static void assertHeightRises(GeometryResult r) {
		int maxY = r.bounds().maxY();
		int minY = r.bounds().minY();
		assertTrue(maxY - minY >= 10, "the staircase climbs");
	}
}
