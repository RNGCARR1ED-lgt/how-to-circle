package com.howtocircle.geometry;

/**
 * Whether a generated shape contains every block inside the ellipse or only its perimeter.
 */
public enum FillMode {
	/** Every block from the centre to the outer edge. */
	FILLED,
	/** Only the blocks that form the outer perimeter. */
	OUTLINE;

	public FillMode next() {
		return this == FILLED ? OUTLINE : FILLED;
	}
}
