package com.howtobuild.tools;

/**
 * What the anchor's Y layer means for a tool. There is no hidden vertical adjustment anywhere: with a Y offset of 0 the
 * anchor layer is exactly the layer described here.
 */
public enum VerticalAnchor {
	/** The anchor layer is the lowest layer of the geometry; nothing is generated below it. */
	BASE,
	/** The anchor layer is the middle layer (or the lower of the two middle layers for an even height). */
	CENTRE
}
