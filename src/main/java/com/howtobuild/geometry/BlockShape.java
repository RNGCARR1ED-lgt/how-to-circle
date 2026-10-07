package com.howtobuild.geometry;

import java.util.Locale;

/**
 * The block shape of a placement, independent of the material: a full block, a slab or a stair with its exact state.
 * The client resolves it to a real {@code BlockState} ({@code SlabBlock.TYPE}, {@code StairBlock.FACING/HALF/SHAPE}).
 *
 * @param kind       full block, slab or stairs
 * @param slabType   slab type (slabs only)
 * @param facing     stair facing: the direction a player walks to go up (stairs only)
 * @param half       stair half (stairs only)
 * @param stairShape stair shape (stairs only)
 */
public record BlockShape(Kind kind, SlabType slabType, Facing facing, Half half, StairShape stairShape) {
	public static final BlockShape FULL = new BlockShape(Kind.FULL, null, null, null, null);
	public static final BlockShape BOTTOM_SLAB = new BlockShape(Kind.SLAB, SlabType.BOTTOM, null, null, null);
	public static final BlockShape TOP_SLAB = new BlockShape(Kind.SLAB, SlabType.TOP, null, null, null);
	public static final BlockShape DOUBLE_SLAB = new BlockShape(Kind.SLAB, SlabType.DOUBLE, null, null, null);

	public enum Kind {
		FULL,
		SLAB,
		STAIRS
	}

	public enum SlabType {
		BOTTOM,
		TOP,
		DOUBLE
	}

	public enum Half {
		BOTTOM,
		TOP
	}

	public enum StairShape {
		STRAIGHT,
		INNER_LEFT,
		INNER_RIGHT,
		OUTER_LEFT,
		OUTER_RIGHT;

		public StairShape mirrored() {
			return switch (this) {
				case STRAIGHT -> STRAIGHT;
				case INNER_LEFT -> INNER_RIGHT;
				case INNER_RIGHT -> INNER_LEFT;
				case OUTER_LEFT -> OUTER_RIGHT;
				case OUTER_RIGHT -> OUTER_LEFT;
			};
		}
	}

	public static BlockShape slab(SlabType type) {
		return switch (type) {
			case BOTTOM -> BOTTOM_SLAB;
			case TOP -> TOP_SLAB;
			case DOUBLE -> DOUBLE_SLAB;
		};
	}

	public static BlockShape stairs(Facing facing, Half half) {
		return new BlockShape(Kind.STAIRS, null, facing, half, StairShape.STRAIGHT);
	}

	public static BlockShape stairs(Facing facing, Half half, StairShape shape) {
		return new BlockShape(Kind.STAIRS, null, facing, half, shape);
	}

	public boolean isFull() {
		return kind == Kind.FULL;
	}

	public boolean isSlab() {
		return kind == Kind.SLAB;
	}

	public boolean isStairs() {
		return kind == Kind.STAIRS;
	}

	/** The material type needed to build this shape. A double slab is built from the slab block. */
	public ShapeKind materialKind() {
		return switch (kind) {
			case FULL -> ShapeKind.BLOCKS;
			case SLAB -> ShapeKind.SLABS;
			case STAIRS -> ShapeKind.STAIRS;
		};
	}

	public BlockShape withStairShape(StairShape shape) {
		return isStairs() ? new BlockShape(kind, null, facing, half, shape) : this;
	}

	/** Mirrors across a vertical plane perpendicular to X ({@code alongX = true}) or Z. Left and right swap. */
	public BlockShape mirrored(boolean acrossX) {
		if (!isStairs()) return this;

		Facing newFacing = facing.alongX() == acrossX ? facing.opposite() : facing;
		return new BlockShape(kind, null, newFacing, half, stairShape.mirrored());
	}

	/** Rotates clockwise about the vertical axis in 90° steps. */
	public BlockShape rotated(int quarterTurns) {
		if (!isStairs() || (quarterTurns & 3) == 0) return this;
		return new BlockShape(kind, null, facing.rotateClockwise(quarterTurns), half, stairShape);
	}

	/** Block state properties in Minecraft's syntax (without the block id), e.g. {@code facing=east,half=bottom,shape=straight}. */
	public String properties() {
		return switch (kind) {
			case FULL -> "";
			case SLAB -> "type=" + slabType.name().toLowerCase(Locale.ROOT);
			case STAIRS -> "facing=" + facing.serializedName() + ",half=" + half.name().toLowerCase(Locale.ROOT)
					+ ",shape=" + stairShape.name().toLowerCase(Locale.ROOT);
		};
	}

	@Override
	public String toString() {
		return kind == Kind.FULL ? "full" : kind.name().toLowerCase(Locale.ROOT) + "[" + properties() + "]";
	}
}
