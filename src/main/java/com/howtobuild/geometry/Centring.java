package com.howtobuild.geometry;

/**
 * The shared centre rule used by every tool and by the mirror centre.
 *
 * <p>Along an axis with an odd extent the centre is exactly one block (the anchor). Along an even extent the true
 * centre is a block boundary, so the centre is two blocks: the anchor and its neighbour in the positive or negative
 * direction, chosen by {@code alignPositive}. In 2D this gives the familiar 1×1 or 2×2 centres; mixed parity gives 1×2.
 */
public final class Centring {
	private Centring() {
	}

	/** Offset (relative to the anchor) of index 0 of an extent laid out around the anchor. */
	public static int minOffset(int extent, boolean alignPositive) {
		return ShapePlacement.minOffset(extent, alignPositive);
	}

	/** The 1 or 2 centre offsets (relative to the anchor) along an axis of the given extent. */
	public static int[] centreOffsets(int extent, boolean alignPositive) {
		if (extent % 2 == 1) return new int[] {0};
		return alignPositive ? new int[] {0, 1} : new int[] {-1, 0};
	}

	/**
	 * Twice the coordinate of the centre plane of an extent (block centres are at {@code 2x + 1}). For an odd extent this
	 * is {@code 1} (the middle of the anchor block); for an even extent it is the shared face of the two centre blocks.
	 */
	public static int doubledCentre(int extent, boolean alignPositive) {
		if (extent % 2 == 1) return 1;
		return alignPositive ? 2 : 0;
	}
}
