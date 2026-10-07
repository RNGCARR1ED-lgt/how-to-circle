package com.howtobuild.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

class CoreGeometryTest {
	@Test
	void voxelPackingRoundTripsNegativeAndLargeValues() {
		int[] values = {0, 1, -1, 30_000_000 % Voxels.LIMIT, -Voxels.LIMIT, Voxels.LIMIT - 1, 12345, -64};

		for (int x : values) {
			for (int y : values) {
				for (int z : values) {
					long key = Voxels.pack(x, y, z);
					assertEquals(x, Voxels.x(key));
					assertEquals(y, Voxels.y(key));
					assertEquals(z, Voxels.z(key));
				}
			}
		}
	}

	@Test
	void boxDecomposerPartitionsExactly() {
		Random random = new Random(42);

		for (int trial = 0; trial < 50; trial++) {
			LongOpenHashSet cells = new LongOpenHashSet();

			for (int i = 0; i < 400; i++) {
				cells.add(Voxels.pack(random.nextInt(12) - 6, random.nextInt(6), random.nextInt(12) - 6));
			}

			LongOpenHashSet covered = new LongOpenHashSet();

			for (Box box : BoxDecomposer.decompose(cells, 7)) {
				assertTrue(box.volume() <= 7, "volume limit");

				for (int y = box.minY(); y <= box.maxY(); y++) {
					for (int z = box.minZ(); z <= box.maxZ(); z++) {
						for (int x = box.minX(); x <= box.maxX(); x++) {
							long key = Voxels.pack(x, y, z);
							assertTrue(cells.contains(key), "box only covers input cells");
							assertTrue(covered.add(key), "boxes do not overlap");
						}
					}
				}
			}

			assertEquals(cells, covered, "every cell covered");
		}
	}

	@Test
	void solidCuboidIsOneBox() {
		LongOpenHashSet cells = new LongOpenHashSet();

		for (int x = 0; x < 4; x++) {
			for (int y = 0; y < 3; y++) {
				for (int z = 0; z < 5; z++) {
					cells.add(Voxels.pack(x, y, z));
				}
			}
		}

		assertEquals(List.of(new Box(0, 0, 0, 3, 2, 4)), BoxDecomposer.decompose(cells));
	}

	@Test
	void stairMirroringFlipsFacingAcrossThePlaneAndSwapsHandedness() {
		BlockShape east = BlockShape.stairs(Facing.EAST, BlockShape.Half.BOTTOM, BlockShape.StairShape.OUTER_LEFT);
		BlockShape acrossX = east.mirrored(true);
		assertEquals(Facing.WEST, acrossX.facing());
		assertEquals(BlockShape.StairShape.OUTER_RIGHT, acrossX.stairShape());
		BlockShape acrossZ = east.mirrored(false);
		assertEquals(Facing.EAST, acrossZ.facing());
		assertEquals(BlockShape.StairShape.OUTER_RIGHT, acrossZ.stairShape());
		assertEquals(east, east.mirrored(true).mirrored(true));
		assertEquals(BlockShape.BOTTOM_SLAB, BlockShape.BOTTOM_SLAB.mirrored(true));
	}

	@Test
	void stairShapesFollowVanillaCornerRules() {
		// An L of stairs: a row facing north turning into a row facing east forms corners like in vanilla.
		GeometryBuilder b = new GeometryBuilder();
		b.set(0, 0, 0, MaterialRole.STEP, BlockShape.stairs(Facing.NORTH, BlockShape.Half.BOTTOM));
		b.set(0, 0, -1, MaterialRole.STEP, BlockShape.stairs(Facing.EAST, BlockShape.Half.BOTTOM));
		StairShapes.resolve(b);
		// The north-facing stair has an east-facing stair in front of it: outer corner (east = clockwise → right).
		assertEquals(BlockShape.StairShape.OUTER_RIGHT, b.get(0, 0, 0).shape().stairShape());
		// The east-facing stair has a north-facing stair behind it... (behind = west): no; behind (0,0,-1)-west = (-1,0,-1) empty.
		assertEquals(BlockShape.StairShape.STRAIGHT, b.get(0, 0, -1).shape().stairShape());
	}

	@Test
	void innerCornerWhenPerpendicularStairBehind() {
		GeometryBuilder b = new GeometryBuilder();
		b.set(0, 0, 0, MaterialRole.STEP, BlockShape.stairs(Facing.NORTH, BlockShape.Half.BOTTOM));
		// Behind (south of) a north-facing stair: a stair facing west → inner corner, west = counter-clockwise → left.
		b.set(0, 0, 1, MaterialRole.STEP, BlockShape.stairs(Facing.WEST, BlockShape.Half.BOTTOM));
		StairShapes.resolve(b);
		assertEquals(BlockShape.StairShape.INNER_LEFT, b.get(0, 0, 0).shape().stairShape());
		// Different halves never connect.
		GeometryBuilder c = new GeometryBuilder();
		c.set(0, 0, 0, MaterialRole.STEP, BlockShape.stairs(Facing.NORTH, BlockShape.Half.BOTTOM));
		c.set(0, 0, 1, MaterialRole.STEP, BlockShape.stairs(Facing.WEST, BlockShape.Half.TOP));
		StairShapes.resolve(c);
		assertEquals(BlockShape.StairShape.STRAIGHT, c.get(0, 0, 0).shape().stairShape());
	}

	@Test
	void stateProperties() {
		assertEquals("facing=east,half=bottom,shape=straight", BlockShape.stairs(Facing.EAST, BlockShape.Half.BOTTOM).properties());
		assertEquals("type=top", BlockShape.TOP_SLAB.properties());
		assertEquals("", BlockShape.FULL.properties());
		assertFalse(BlockShape.FULL.isStairs());
	}

	@Test
	void facingNearestAndRotation() {
		assertEquals(Facing.EAST, Facing.nearest(1, 0.2));
		assertEquals(Facing.NORTH, Facing.nearest(0.1, -1));
		assertEquals(Facing.SOUTH, Facing.NORTH.rotateClockwise(2));
		assertEquals(Facing.EAST, Facing.NORTH.clockwise());
	}
}
