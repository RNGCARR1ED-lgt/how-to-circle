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
import org.junit.jupiter.params.provider.MethodSource;

import com.howtobuild.TestShapes;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.details.SlabMode;
import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.Facing;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.ShapeKind;

/** Arch corridors with every Blocks / Slabs / Stairs combination and explicit part assignment. */
class CorridorMaterialsTest {
	static Stream<Set<ShapeKind>> combinations() {
		return Stream.of(EnumSet.of(ShapeKind.BLOCKS), EnumSet.of(ShapeKind.SLABS), EnumSet.of(ShapeKind.STAIRS),
				EnumSet.of(ShapeKind.BLOCKS, ShapeKind.SLABS), EnumSet.of(ShapeKind.BLOCKS, ShapeKind.STAIRS),
				EnumSet.of(ShapeKind.SLABS, ShapeKind.STAIRS), EnumSet.allOf(ShapeKind.class));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("combinations")
	void everyCombinationKeepsTheExactCorridor(Set<ShapeKind> types) {
		GeometryResult blocks = TestShapes.generate("corridor", "width", 6, "height", 10, "length", 20);
		GenerationContext ctx = TestShapes.types(types.toArray(new ShapeKind[0])).withDetails(DetailSettings.of(DetailFeature.ARCH_FRAMES, DetailFeature.KEYSTONE));
		GeometryResult r = TestShapes.generate("corridor", ctx, "width", 6, "height", 10, "length", 20);
		GeometryResult plainFramed = TestShapes.generate("corridor", GenerationContext.DEFAULT.withDetails(ctx.details()), "width", 6, "height", 10, "length", 20);

		assertEquals(6, blocks.width());
		assertEquals(TestShapes.positions(plainFramed), TestShapes.positions(r), "shaping never adds or removes blocks");
		assertTrue(r.warnings().isEmpty(), r.warnings().toString());

		boolean stairs = r.placements().stream().anyMatch(p -> p.shape().isStairs());
		boolean slabs = r.placements().stream().anyMatch(p -> p.shape().isSlab() && p.shape() != BlockShape.DOUBLE_SLAB);
		assertEquals(types.contains(ShapeKind.STAIRS), stairs, "stairs appear exactly when selected");

		if (types.contains(ShapeKind.SLABS) && !types.contains(ShapeKind.STAIRS)) assertTrue(slabs, "slabs shape the curve");
		if (!types.contains(ShapeKind.SLABS)) assertFalse(r.placements().stream().anyMatch(p -> p.shape().isSlab()));
	}

	@Test
	void stairsFollowTheCurvature() {
		GeometryResult r = TestShapes.generate("corridor", TestShapes.types(ShapeKind.BLOCKS, ShapeKind.STAIRS), "width", 9, "height", 10,
				"length", 5, "corridor_style", "HOLLOW");
		List<Placement> curve = r.placements().stream().filter(p -> p.shape().isStairs()).toList();
		assertFalse(curve.isEmpty());

		for (Placement p : curve) {
			assertTrue(p.shape().facing() == Facing.EAST || p.shape().facing() == Facing.WEST, "stairs turn across the corridor: " + p);
			if (p.shape().half() == BlockShape.Half.TOP) assertTrue(r.at(p.x(), p.y() - 1, p.z()) == null, "upside down under the curve: " + p);
			else assertTrue(r.at(p.x(), p.y() + 1, p.z()) == null, "upright on the roof: " + p);
			// The low side of the stair is open: the stair faces away from the air it smooths.
			int dx = p.shape().facing() == Facing.EAST ? 1 : -1;
			assertTrue(r.at(p.x() - dx, p.y(), p.z()) == null, "open on the low side: " + p);
		}

		// Both sides of the arch: stairs facing east on one side, west on the other, mirrored.
		assertTrue(curve.stream().anyMatch(p -> p.shape().facing() == Facing.EAST && p.x() < 0));
		assertTrue(curve.stream().anyMatch(p -> p.shape().facing() == Facing.WEST && p.x() > 0));
		assertTrue(curve.stream().anyMatch(p -> p.shape().half() == BlockShape.Half.TOP), "intrados");
		assertTrue(curve.stream().anyMatch(p -> p.shape().half() == BlockShape.Half.BOTTOM), "extrados");
	}

	@Test
	void slabsUseTheExposedHalfOrTheSlabMode() {
		GeometryResult r = TestShapes.generate("corridor", TestShapes.types(ShapeKind.BLOCKS, ShapeKind.SLABS), "width", 9, "height", 10, "length", 3);

		for (Placement p : r.placements()) {
			if (p.shape() == BlockShape.TOP_SLAB) assertTrue(r.at(p.x(), p.y() - 1, p.z()) == null, "top slab has air below: " + p);
			if (p.shape() == BlockShape.BOTTOM_SLAB) assertTrue(r.at(p.x(), p.y() + 1, p.z()) == null, "bottom slab has air above: " + p);
		}

		assertTrue(r.placements().stream().anyMatch(p -> p.shape() == BlockShape.TOP_SLAB));
		assertTrue(r.placements().stream().anyMatch(p -> p.shape() == BlockShape.BOTTOM_SLAB));

		GeometryResult doubles = TestShapes.generate("corridor", TestShapes.types(ShapeKind.BLOCKS, ShapeKind.SLABS).withSlabMode(SlabMode.DOUBLE),
				"width", 9, "height", 10, "length", 3);
		assertTrue(doubles.placements().stream().filter(p -> p.shape().isSlab()).allMatch(p -> p.shape() == BlockShape.DOUBLE_SLAB));
	}

	@Test
	void explicitAssignmentBlocksStructureStairsCurveSlabTrim() {
		GenerationContext ctx = GenerationContext.DEFAULT.withMaterialTypes(EnumSet.allOf(ShapeKind.class))
				.withDetails(DetailSettings.of(DetailFeature.ARCH_FRAMES));
		GeometryResult r = TestShapes.generate("corridor", ctx, "width", 9, "height", 10, "length", 11, "structure_part", "BLOCKS",
				"curve_part", "STAIRS", "trim_part", "SLABS");

		assertTrue(r.placements().stream().anyMatch(p -> p.role() == MaterialRole.PRIMARY && p.shape().isStairs()), "curve in stairs");
		assertTrue(r.placements().stream().filter(p -> p.role() == MaterialRole.TRIM).allMatch(p -> p.shape().isSlab() || p.shape().isStairs() == false),
				"trim made of slabs");
		assertTrue(r.placements().stream().anyMatch(p -> p.role() == MaterialRole.TRIM && p.shape().isSlab()));
		assertTrue(r.placements().stream().filter(p -> p.role() == MaterialRole.PRIMARY && p.y() < 3).allMatch(p -> p.shape().isFull()),
				"straight walls stay blocks");
	}

	@Test
	void unselectedExplicitPartWarnsAndFallsBack() {
		GeometryResult r = TestShapes.generate("corridor", TestShapes.types(ShapeKind.BLOCKS), "curve_part", "STAIRS");
		assertTrue(r.warnings().stream().anyMatch(w -> w.contains("not selected")), r.warnings().toString());
		assertTrue(r.placements().stream().allMatch(p -> p.shape().isFull()));
	}
}
