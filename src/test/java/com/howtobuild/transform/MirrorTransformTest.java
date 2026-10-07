package com.howtobuild.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.howtobuild.TestShapes;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.geometry.Voxels;

class MirrorTransformTest {
	private static boolean singlePlane;

	@Test
	void reflectionAboutOneAndTwoBlockCentres() {
		// 1×1 centre at block 3: the plane is the middle of block 3 → 3 ↔ 3, 4 ↔ 2.
		int p1 = MirrorTransform.planeDoubled(3, false, true, 0);
		assertEquals(3, MirrorTransform.reflect(3, p1));
		assertEquals(2, MirrorTransform.reflect(4, p1));
		// 2×2 centre at blocks 3 and 4: the plane is their shared face → 3 ↔ 4.
		int p2 = MirrorTransform.planeDoubled(3, true, true, 0);
		assertEquals(4, MirrorTransform.reflect(3, p2));
		assertEquals(3, MirrorTransform.reflect(4, p2));
		// Negative alignment: blocks 2 and 3.
		int p3 = MirrorTransform.planeDoubled(3, true, false, 0);
		assertEquals(2, MirrorTransform.reflect(3, p3));
		// Offsets move the plane by whole blocks.
		assertEquals(3 + 2 * 5, MirrorTransform.reflect(3, MirrorTransform.planeDoubled(3, false, true, 5)));
		assertEquals(3 - 2 * 3, MirrorTransform.reflect(3, MirrorTransform.planeDoubled(3, false, true, -3)));
	}

	@ParameterizedTest(name = "axis {0} twoWide {1} offset {2}")
	@CsvSource({
			"X,false,0", "X,true,0", "X,false,5", "X,true,-3",
			"Z,false,0", "Z,true,0", "Z,false,-3", "Z,true,5",
			"X_AND_Z,false,0", "X_AND_Z,true,4", "X_AND_Z,false,-2"})
	void corridorMirrorIsExact(MirrorAxis axis, boolean twoWide, int offset) {
		GeometryResult source = TestShapes.generate("corridor",
				TestShapes.details(DetailFeature.ARCH_FRAMES, DetailFeature.KEYSTONE, DetailFeature.SMOOTH_CURVES)
						.withMaterialTypes(java.util.EnumSet.allOf(ShapeKind.class)),
				"width", 6, "height", 10, "length", 20);
		int centreX = 7;
		int centreZ = -4;
		MirrorSettings settings = new MirrorSettings(true, axis, MirrorMode.DUPLICATE, twoWide, twoWide, true, false, offset, offset);
		GeometryResult mirrored = MirrorTransform.apply(source, settings, centreX, centreZ);
		int planeX = MirrorTransform.planeDoubled(centreX, twoWide, true, offset);
		int planeZ = MirrorTransform.planeDoubled(centreZ, twoWide, false, offset);

		singlePlane = axis != MirrorAxis.X_AND_Z;
		Map<Long, Placement> index = new HashMap<>();
		mirrored.placements().forEach(p -> index.putIfAbsent(p.key(), p));

		for (Placement original : source.placements()) {
			assertNotNull(index.get(original.key()), "original kept");

			if (axis.mirrorsX()) checkPartner(index, original, planeX - 1 - original.x(), original.z(), true, false);
			if (axis.mirrorsZ()) checkPartner(index, original, original.x(), planeZ - 1 - original.z(), false, true);
			if (axis.mirrorsX() && axis.mirrorsZ()) checkPartner(index, original, planeX - 1 - original.x(), planeZ - 1 - original.z(), true, true);
		}

		// The combined result is exactly symmetric about each mirror plane.
		Set<Long> cells = TestShapes.positions(mirrored);

		for (Placement p : mirrored.placements()) {
			if (axis.mirrorsX()) assertTrue(cells.contains(Voxels.pack(planeX - 1 - p.x(), p.y(), p.z())));
			if (axis.mirrorsZ()) assertTrue(cells.contains(Voxels.pack(p.x(), p.y(), planeZ - 1 - p.z())));
		}
	}

	private static void checkPartner(Map<Long, Placement> index, Placement original, int x, int z, boolean acrossX, boolean acrossZ) {
		Placement partner = index.get(Voxels.pack(x, original.y(), z));
		assertNotNull(partner, "mirror of " + original);

		// With a single plane each position has exactly one source, so the state must be the mirrored state. (Stair
		// corner shapes are re-derived from neighbours afterwards, so facing, half and kind are compared.)
		if (partner.mirrored() && acrossX != acrossZ && singlePlane) {
			var expected = original.shape();
			if (acrossX) expected = expected.mirrored(true);
			if (acrossZ) expected = expected.mirrored(false);
			assertEquals(expected.kind(), partner.shape().kind(), "block kind for " + original);
			assertEquals(expected.facing(), partner.shape().facing(), "facing mirrored for " + original);
			assertEquals(expected.half(), partner.shape().half());
			assertEquals(expected.slabType(), partner.shape().slabType());
			assertEquals(original.role(), partner.role());
		}
	}

	@Test
	void replaceKeepsOnlyTheMirror() {
		GeometryResult source = TestShapes.generate("spiral", TestShapes.types(ShapeKind.STAIRS));
		MirrorSettings settings = new MirrorSettings(true, MirrorAxis.X, MirrorMode.REPLACE, false, false, true, true, 3, 0);
		GeometryResult mirrored = MirrorTransform.apply(source, settings, 0, 0);
		assertEquals(source.blockCount(), mirrored.blockCount());
		assertTrue(mirrored.placements().stream().allMatch(Placement::mirrored));
	}

	@Test
	void mirroringASymmetricShapeAboutItsOwnCentreChangesNothing() {
		GeometryResult circle = TestShapes.generate("circle", "size", 15);
		MirrorSettings settings = new MirrorSettings(true, MirrorAxis.X_AND_Z, MirrorMode.DUPLICATE, false, false, true, true, 0, 0);
		assertEquals(circle.blockCount(), MirrorTransform.apply(circle, settings, 0, 0).blockCount());
		GeometryResult even = TestShapes.generate("circle", "size", 16);
		MirrorSettings evenSettings = new MirrorSettings(true, MirrorAxis.X_AND_Z, MirrorMode.DUPLICATE, true, true, true, true, 0, 0);
		assertEquals(even.blockCount(), MirrorTransform.apply(even, evenSettings, 0, 0).blockCount());
	}

	@Test
	void mirrorTwiceIsIdentity() {
		GeometryResult source = TestShapes.generate("spiral", TestShapes.types(ShapeKind.STAIRS, ShapeKind.SLABS));
		int plane = MirrorTransform.planeDoubled(2, true, false, 1);

		for (Placement p : source.placements()) {
			Placement twice = MirrorTransform.mirror(MirrorTransform.mirror(p, true, false, plane, 0), true, false, plane, 0);
			assertEquals(p.x(), twice.x());
			assertEquals(p.shape(), twice.shape());
		}
	}
}
