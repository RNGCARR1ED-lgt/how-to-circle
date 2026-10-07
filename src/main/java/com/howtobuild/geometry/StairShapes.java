package com.howtobuild.geometry;

import java.util.List;

/**
 * Derives stair shapes (straight, inner/outer left/right) from neighbouring stairs with the same rules Minecraft uses
 * when stairs are placed, so the preview matches what the world will show after neighbour updates.
 *
 * <p>Vanilla rule for a stair facing {@code F} with half {@code H}: if the block in front (towards {@code F}) is a stair
 * with the same half whose facing is perpendicular, the shape is an <em>outer</em> corner (left if that stair faces
 * {@code F}'s counter-clockwise direction) unless the stair on that side would make it inconsistent. Otherwise, if the
 * block behind is such a stair, the shape is an <em>inner</em> corner.
 */
public final class StairShapes {
	private StairShapes() {
	}

	/** Recomputes the shape of every stair in the builder. */
	public static void resolve(GeometryBuilder builder) {
		List<Placement> stairs = builder.snapshot().stream().filter(p -> p.shape().isStairs()).toList();

		for (Placement p : stairs) {
			BlockShape shape = p.shape();
			BlockShape.StairShape derived = shapeAt(builder, p.x(), p.y(), p.z(), shape.facing(), shape.half());
			builder.put(p.withShape(shape.withStairShape(derived)));
		}
	}

	static BlockShape.StairShape shapeAt(GeometryBuilder builder, int x, int y, int z, Facing facing, BlockShape.Half half) {
		Placement front = builder.get(x + facing.dx, y, z + facing.dz);

		if (isStair(front, half)) {
			Facing frontFacing = front.shape().facing();

			if (frontFacing.alongX() != facing.alongX() && canTakeShape(builder, x, y, z, facing, half, frontFacing.opposite())) {
				return frontFacing == facing.counterClockwise() ? BlockShape.StairShape.OUTER_LEFT : BlockShape.StairShape.OUTER_RIGHT;
			}
		}

		Facing back = facing.opposite();
		Placement behind = builder.get(x + back.dx, y, z + back.dz);

		if (isStair(behind, half)) {
			Facing behindFacing = behind.shape().facing();

			if (behindFacing.alongX() != facing.alongX() && canTakeShape(builder, x, y, z, facing, half, behindFacing)) {
				return behindFacing == facing.counterClockwise() ? BlockShape.StairShape.INNER_LEFT : BlockShape.StairShape.INNER_RIGHT;
			}
		}

		return BlockShape.StairShape.STRAIGHT;
	}

	/** Vanilla's {@code canTakeShape}: false if the neighbour on that side is a stair with the same facing and half. */
	private static boolean canTakeShape(GeometryBuilder builder, int x, int y, int z, Facing facing, BlockShape.Half half, Facing side) {
		Placement neighbour = builder.get(x + side.dx, y, z + side.dz);
		return !isStair(neighbour, half) || neighbour.shape().facing() != facing;
	}

	private static boolean isStair(Placement placement, BlockShape.Half half) {
		return placement != null && placement.shape().isStairs() && placement.shape().half() == half;
	}
}
