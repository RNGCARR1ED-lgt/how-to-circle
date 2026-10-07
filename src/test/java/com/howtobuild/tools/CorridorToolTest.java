package com.howtobuild.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.howtobuild.TestShapes;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.ShapeKind;

class CorridorToolTest {
	@ParameterizedTest(name = "{3} {0}x{1}x{2}")
	@CsvSource({
			"3,4,5,ARCH", "12,6,8,ARCH", "5,16,6,ARCH", "6,10,2,ARCH", "6,10,60,ARCH",
			"3,4,5,DOME", "12,6,8,DOME", "5,16,6,DOME", "6,10,60,DOME",
			"3,4,5,SPHERE", "12,6,8,SPHERE", "5,16,6,SPHERE", "6,10,60,SPHERE"})
	void sizesAndProfiles(int w, int h, int l, String profile) {
		GeometryResult r = TestShapes.generate("corridor", "width", w, "height", h, "length", l, "profile", profile);
		assertEquals(w, r.width(), "width across X");
		assertEquals(h, r.height(), "height");
		assertEquals(l, r.length(), "length along Z");
		SolidToolsTest.assertSymmetric(r, true, false, false);
	}

	@Test
	void archCorridorSixByTenByTwentyWithRepeatingArches() {
		GeometryResult r = TestShapes.generate("corridor", TestShapes.details(DetailFeature.ARCH_FRAMES, DetailFeature.KEYSTONE),
				"width", 6, "height", 10, "length", 20, "profile", "ARCH", "arch_interval", 5);
		// The corridor itself is 6 × 10 × 20; frames add one block around each arch.
		assertEquals(20, r.length());
		assertEquals(4.0, r.values().get("arches"));
		assertEquals(4, r.placements().stream().filter(p -> p.role() == MaterialRole.ACCENT).map(p -> p.z()).distinct().count(),
				"a keystone on each of the 4 arches");
		// Hollow inside: the middle of the corridor is empty above the floor.
		assertNull(r.at(0, 3, 0));
		// Walls are 10 high on the arch slice.
		// Between arches (z = 0) the 6-wide profile spans x = -2..3 and its top row (y = 9) is the four middle blocks.
		assertTrue(r.at(-2, 9, 0) == null && r.at(-1, 9, 0) != null && r.at(2, 9, 0) != null && r.at(3, 9, 0) == null, "the arch closes at the top");
		assertTrue(r.at(-2, 0, 0) != null && r.at(3, 0, 0) != null, "walls stand on the full width");
	}

	@Test
	void partToggles() {
		GeometryResult noFloor = TestShapes.generate("corridor", "floor", false);
		assertEquals(0, noFloor.count(MaterialRole.FLOOR));
		GeometryResult open = TestShapes.generate("corridor", "corridor_style", "OPEN");
		GeometryResult hollow = TestShapes.generate("corridor");
		assertTrue(open.blockCount() < hollow.blockCount());
		GeometryResult noLeft = TestShapes.generate("corridor", "left_wall", false);
		assertTrue(noLeft.bounds().minX() > hollow.bounds().minX() || noLeft.blockCount() < hollow.blockCount());
		GeometryResult filled = TestShapes.generate("corridor", "corridor_style", "FILLED");
		assertTrue(filled.blockCount() > hollow.blockCount());
	}

	@Test
	void smoothingUsesSelectedTypes() {
		GeometryResult stairs = TestShapes.generate("corridor",
				TestShapes.details(DetailFeature.SMOOTH_CURVES).withMaterialTypes(java.util.EnumSet.of(ShapeKind.BLOCKS, ShapeKind.STAIRS)),
				"width", 9, "height", 8);
		assertTrue(stairs.count(BlockShape.Kind.STAIRS) > 0);
		assertTrue(stairs.placements().stream().anyMatch(p -> p.shape().isStairs() && p.shape().half() == BlockShape.Half.TOP),
				"upside-down stairs smooth the inside of the arch");
		GeometryResult slabs = TestShapes.generate("corridor",
				TestShapes.details(DetailFeature.SMOOTH_CURVES).withMaterialTypes(java.util.EnumSet.of(ShapeKind.BLOCKS, ShapeKind.SLABS)),
				"width", 9, "height", 8);
		assertEquals(0, slabs.count(BlockShape.Kind.STAIRS));
		assertTrue(slabs.count(BlockShape.Kind.SLAB) > 0);
		GeometryResult blocks = TestShapes.generate("corridor", TestShapes.details(DetailFeature.SMOOTH_CURVES), "width", 9, "height", 8);
		assertEquals(0, blocks.count(BlockShape.Kind.STAIRS) + blocks.count(BlockShape.Kind.SLAB), "blocks only: nothing to smooth with");
	}

	@Test
	void alongXSwapsAxes() {
		GeometryResult r = TestShapes.generate("corridor", "axis", "ALONG_X");
		assertEquals(20, r.width());
		assertEquals(6, r.length());
	}

	@Test
	void corridorDetails() {
		GeometryResult r = TestShapes.generate("corridor", TestShapes.details(DetailFeature.SIDE_COLUMNS, DetailFeature.FLOOR_BORDER,
				DetailFeature.LIGHT_RECESSES, DetailFeature.CEILING_RIBS, DetailFeature.ENTRY_FRAME, DetailFeature.WALL_PANELS));
		assertTrue(r.count(MaterialRole.SUPPORT) > 0);
		assertTrue(r.count(MaterialRole.ACCENT) > 0, "light recesses");
		assertTrue(r.count(MaterialRole.TRIM) > 0);
	}
}
