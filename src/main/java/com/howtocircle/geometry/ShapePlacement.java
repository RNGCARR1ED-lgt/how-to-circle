package com.howtocircle.geometry;

/**
 * Maps shape cells {@code (u, v)} onto world block offsets relative to the selected centre (anchor) block.
 *
 * <p>World axes use Minecraft's convention: {@code +X} = east, {@code +Y} = up, {@code +Z} = south.
 *
 * <h2>Centring</h2>
 * Along an axis with an odd extent the anchor block is the exact middle block. Along an axis with an even extent the
 * true centre is a block boundary, so the two middle blocks are the anchor and one neighbour; {@code alignPositiveU} /
 * {@code alignPositiveV} choose whether that neighbour lies in the positive or negative world direction. This is the
 * "centre alignment" option: for a 2×2 centre it picks which of the four blocks around the corner is the anchor, and
 * for mixed parity (e.g. a 1×2 centre) it picks the side the second centre block extends to.
 *
 * @param plane          horizontal (floor) or vertical (wall)
 * @param rotated        rotate 90° about the vertical axis
 * @param alignPositiveU for an even extent along the width axis, extend the centre towards the positive world axis
 * @param alignPositiveV for an even extent along the height axis, extend the centre towards the positive world axis
 * @param verticalOffset extra vertical offset in blocks (move hologram up/down)
 */
public record ShapePlacement(Plane plane, boolean rotated, boolean alignPositiveU, boolean alignPositiveV, int verticalOffset) {
	public static final ShapePlacement DEFAULT = new ShapePlacement(Plane.HORIZONTAL, false, true, true, 0);

	public enum Plane {
		HORIZONTAL,
		VERTICAL;

		public Plane next() {
			return this == HORIZONTAL ? VERTICAL : HORIZONTAL;
		}
	}

	public enum Axis {
		X("East", "West"),
		Y("Up", "Down"),
		Z("South", "North");

		private final String positiveName;
		private final String negativeName;

		Axis(String positiveName, String negativeName) {
			this.positiveName = positiveName;
			this.negativeName = negativeName;
		}

		public String directionName(boolean positive) {
			return positive ? positiveName : negativeName;
		}
	}

	/** The world axis the shape's width ({@code u}) runs along. */
	public Axis widthAxis() {
		if (plane == Plane.HORIZONTAL) return rotated ? Axis.Z : Axis.X;
		return rotated ? Axis.Z : Axis.X;
	}

	/** The world axis the shape's height ({@code v}) runs along. */
	public Axis heightAxis() {
		if (plane == Plane.HORIZONTAL) return rotated ? Axis.X : Axis.Z;
		return Axis.Y;
	}

	/** The world axis perpendicular to the shape (its one-block thickness). */
	public Axis normalAxis() {
		if (plane == Plane.HORIZONTAL) return Axis.Y;
		return rotated ? Axis.X : Axis.Z;
	}

	/** Offset of cell index {@code 0} along an axis with the given extent. */
	public static int minOffset(int extent, boolean alignPositive) {
		int doubledCentre = extent % 2 == 1 ? 1 : (alignPositive ? 2 : 0);
		return Math.floorDiv(doubledCentre - extent, 2);
	}

	public int minOffsetU(int width) {
		return minOffset(width, alignPositiveU);
	}

	public int minOffsetV(int height) {
		return minOffset(height, alignPositiveV);
	}

	/**
	 * World offset (x, y, z) from the anchor block of the cell {@code (u, v)} in a shape of the given size.
	 */
	public int[] offset(int u, int v, int width, int height) {
		int[] out = new int[3];
		add(out, widthAxis(), minOffsetU(width) + u);
		add(out, heightAxis(), minOffsetV(height) + v);
		out[1] += verticalOffset;
		return out;
	}

	private static void add(int[] out, Axis axis, int amount) {
		out[axis.ordinal()] += amount;
	}

	public ShapePlacement withPlane(Plane newPlane) {
		return new ShapePlacement(newPlane, rotated, alignPositiveU, alignPositiveV, verticalOffset);
	}

	public ShapePlacement withRotated(boolean newRotated) {
		return new ShapePlacement(plane, newRotated, alignPositiveU, alignPositiveV, verticalOffset);
	}

	public ShapePlacement withAlignment(boolean positiveU, boolean positiveV) {
		return new ShapePlacement(plane, rotated, positiveU, positiveV, verticalOffset);
	}

	public ShapePlacement withVerticalOffset(int offset) {
		return new ShapePlacement(plane, rotated, alignPositiveU, alignPositiveV, offset);
	}
}
