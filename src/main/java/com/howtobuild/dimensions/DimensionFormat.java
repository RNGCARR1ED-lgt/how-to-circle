package com.howtobuild.dimensions;

/**
 * How a section's size is written.
 */
public enum DimensionFormat {
	/** All non-trivial sides, e.g. {@code 6 × 1} or {@code 4 × 3 × 2}. */
	FULL,
	/** The longest side only, e.g. {@code 6}. */
	SIMPLIFIED,
	/** Size along X, e.g. {@code 6 W}. */
	WIDTH_ONLY,
	/** Size along Y, e.g. {@code 10 H}. */
	HEIGHT_ONLY,
	/** Named sides, e.g. {@code WIDTH: 6}. */
	NAMED,
	/** A user template such as {@code W {width} / H {height}}. */
	CUSTOM
}
