package com.howtobuild.dimensions;

/**
 * A rectangular group of blocks that are connected in straight lines, in shape cell coordinates.
 *
 * @param u      first cell along the shape width
 * @param v      first cell along the shape height
 * @param width  number of blocks along the shape width ({@code u})
 * @param height number of blocks along the shape height ({@code v})
 */
public record ConnectedSection(int u, int v, int width, int height) {
	public ConnectedSection {
		if (width < 1 || height < 1) {
			throw new IllegalArgumentException("Section must be at least 1×1, got " + width + "×" + height);
		}
	}

	public enum Kind {
		/** A single block. */
		SINGLE,
		/** A straight line along the width axis ({@code n by 1}). */
		LINE_ALONG_WIDTH,
		/** A straight line along the height axis ({@code 1 by n}). */
		LINE_ALONG_HEIGHT,
		/** A rectangle at least 2 blocks in both directions. */
		RECTANGLE
	}

	public Kind kind() {
		if (width == 1 && height == 1) return Kind.SINGLE;
		if (height == 1) return Kind.LINE_ALONG_WIDTH;
		if (width == 1) return Kind.LINE_ALONG_HEIGHT;
		return Kind.RECTANGLE;
	}

	public int blockCount() {
		return width * height;
	}

	/** Exclusive end along the width axis. */
	public int uEnd() {
		return u + width;
	}

	/** Exclusive end along the height axis. */
	public int vEnd() {
		return v + height;
	}

	public boolean contains(int cu, int cv) {
		return cu >= u && cu < uEnd() && cv >= v && cv < vEnd();
	}

	public int longSide() {
		return Math.max(width, height);
	}

	public int shortSide() {
		return Math.min(width, height);
	}
}
