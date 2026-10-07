package com.howtobuild.dimensions;

/**
 * How a section's two dimensions are ordered in its label.
 */
public enum LabelFormat {
	/** Longest side first, e.g. a 1-wide, 5-long column reads {@code 5 by 1}. */
	LONG_BY_SHORT,
	/** Width axis first, then height axis, e.g. the same column reads {@code 1 by 5}. */
	WIDTH_BY_HEIGHT;

	public LabelFormat next() {
		return this == LONG_BY_SHORT ? WIDTH_BY_HEIGHT : LONG_BY_SHORT;
	}
}
