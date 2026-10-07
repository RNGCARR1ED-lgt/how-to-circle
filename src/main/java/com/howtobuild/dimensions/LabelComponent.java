package com.howtobuild.dimensions;

import java.util.Locale;

/**
 * Values that can appear in the summary label of a shape. Each one can be toggled.
 */
public enum LabelComponent {
	TOOL,
	WIDTH,
	HEIGHT,
	LENGTH,
	THICKNESS,
	RADIUS,
	DIAMETER,
	STEPS,
	REVOLUTIONS,
	MATERIAL,
	SECTION;

	/** Key in {@link com.howtobuild.geometry.GeometryResult#values()} (for numeric components). */
	public String valueKey() {
		return name().toLowerCase(Locale.ROOT);
	}
}
