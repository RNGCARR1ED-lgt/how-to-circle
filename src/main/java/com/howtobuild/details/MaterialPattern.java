package com.howtobuild.details;

/**
 * Two-material patterns applied to the main structure: alternate cells use the trim material.
 */
public enum MaterialPattern {
	NONE,
	/** Alternating stripes along X. */
	STRIPES,
	/** 3D checkerboard. */
	CHECKER,
	/** Concentric rings by horizontal distance from the centre. */
	RINGS,
	/** Horizontal bands by height. */
	BANDS,
	/** Angular sectors around the centre. */
	SECTIONS;

	/** Whether the cell at the given offset uses the second material. Pure and deterministic. */
	public boolean secondary(int x, int y, int z, int size) {
		int s = Math.max(1, size);

		return switch (this) {
			case NONE -> false;
			case STRIPES -> Math.floorMod(Math.floorDiv(x, s), 2) == 1;
			case CHECKER -> Math.floorMod(Math.floorDiv(x, s) + Math.floorDiv(y, s) + Math.floorDiv(z, s), 2) == 1;
			case RINGS -> ((int) Math.floor(Math.sqrt((double) x * x + (double) z * z) / s)) % 2 == 1;
			case BANDS -> Math.floorMod(Math.floorDiv(y, s), 2) == 1;
			case SECTIONS -> {
				int sectors = Math.max(4, 2 * s);
				double angle = Math.atan2(z + 0.5, x + 0.5) + Math.PI;
				yield ((int) Math.floor(angle / (2 * Math.PI) * sectors)) % 2 == 1;
			}
		};
	}
}
