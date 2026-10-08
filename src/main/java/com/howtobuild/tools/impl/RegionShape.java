package com.howtobuild.tools.impl;

import com.howtobuild.geometry.CircularFootprint;
import com.howtobuild.geometry.Mask2D;

/** Shapes a region (randomisation area, terrain region, protected area) can have, using the shared exact footprints. */
public enum RegionShape {
	CIRCLE,
	OVAL,
	SQUARE,
	RECTANGLE;

	/** Whether the length always equals the width. */
	public boolean uniform() {
		return this == CIRCLE || this == SQUARE;
	}

	/** The exact cells of this shape with the given extents (circles and ovals are the Circle tool's own cells). */
	public Mask2D mask(int width, int length) {
		int l = uniform() ? width : length;
		return switch (this) {
			case CIRCLE, OVAL -> CircularFootprint.of(width, l, true, true).mask();
			case SQUARE, RECTANGLE -> Mask2D.rectangle(width, l);
		};
	}

	public int length(int width, int length) {
		return uniform() ? width : length;
	}
}
