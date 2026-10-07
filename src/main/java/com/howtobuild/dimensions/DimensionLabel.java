package com.howtobuild.dimensions;

/**
 * Text helpers for section dimension labels.
 */
public final class DimensionLabel {
	private DimensionLabel() {
	}

	/** The full label for a section, e.g. {@code "7 by 1"} or {@code "4 by 3"}. */
	public static String text(ConnectedSection section, LabelFormat format) {
		return switch (format) {
			case LONG_BY_SHORT -> section.longSide() + " by " + section.shortSide();
			case WIDTH_BY_HEIGHT -> section.width() + " by " + section.height();
		};
	}

	/** A single measurement for one side of a section (used on CAD-style dimension lines). */
	public static String side(int blocks) {
		return Integer.toString(blocks);
	}
}
