package com.howtocircle.geometry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShapePlacementTest {
	@Test
	void oddExtentIsCentredOnAnchor() {
		assertEquals(-7, ShapePlacement.minOffset(15, true));
		assertEquals(-7, ShapePlacement.minOffset(15, false));
		assertEquals(0, ShapePlacement.minOffset(1, true));
	}

	@Test
	void evenExtentExtendsTowardsChosenSide() {
		// 16 wide: the two centre blocks are the anchor and its neighbour.
		assertEquals(-7, ShapePlacement.minOffset(16, true)); // cells -7..8, centre blocks 0 and +1
		assertEquals(-8, ShapePlacement.minOffset(16, false)); // cells -8..7, centre blocks -1 and 0
		assertEquals(0, ShapePlacement.minOffset(2, true));
		assertEquals(-1, ShapePlacement.minOffset(2, false));
	}

	@Test
	void centreCellsLandOnAnchor() {
		for (int size = 1; size <= 40; size++) {
			for (boolean positive : new boolean[] {true, false}) {
				int min = ShapePlacement.minOffset(size, positive);
				int max = min + size - 1;
				assertEquals(max, -min + (size % 2 == 0 ? (positive ? 1 : -1) : 0), "symmetric around the centre");
				// Centre cell(s) index (size-1)/2 and size/2 must include offset 0 (the anchor).
				int c1 = min + (size - 1) / 2;
				int c2 = min + size / 2;
				assertEquals(true, c1 == 0 || c2 == 0, "anchor is a centre block for size " + size);
			}
		}
	}

	@Test
	void horizontalPlaneMapsToXAndZ() {
		ShapePlacement placement = ShapePlacement.DEFAULT;
		assertEquals(ShapePlacement.Axis.X, placement.widthAxis());
		assertEquals(ShapePlacement.Axis.Z, placement.heightAxis());
		assertEquals(ShapePlacement.Axis.Y, placement.normalAxis());
		// 21x13: cell (0,0) is 10 west and 6 north of the anchor.
		assertArrayEquals(new int[] {-10, 0, -6}, placement.offset(0, 0, 21, 13));
		assertArrayEquals(new int[] {0, 0, 0}, placement.offset(10, 6, 21, 13));
	}

	@Test
	void rotationSwapsAxes() {
		ShapePlacement placement = ShapePlacement.DEFAULT.withRotated(true);
		assertArrayEquals(new int[] {-6, 0, -10}, placement.offset(0, 0, 21, 13));
	}

	@Test
	void verticalPlaneUsesYForHeight() {
		ShapePlacement placement = ShapePlacement.DEFAULT.withPlane(ShapePlacement.Plane.VERTICAL).withVerticalOffset(3);
		assertEquals(ShapePlacement.Axis.Y, placement.heightAxis());
		assertEquals(ShapePlacement.Axis.Z, placement.normalAxis());
		assertArrayEquals(new int[] {-2, 1, 0}, placement.offset(0, 0, 5, 5));
		assertEquals(ShapePlacement.Axis.X, placement.withRotated(true).normalAxis());
	}
}
