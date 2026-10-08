package com.howtobuild.geometry;

/** Kinds of guide cells (reference outlines that are drawn but never built). */
public final class Guide {
	/** The master footprint a spiral follows or fits inside. */
	public static final int OUTLINE = 0;
	/** A protected area that must not be changed. */
	public static final int PROTECTED = 1;
	/** The outer boundary of a region (terrain, randomisation). */
	public static final int BOUNDARY = 2;
	/** An elevation contour line. */
	public static final int CONTOUR = 3;
	/** The usable inner boundary (e.g. a spiral inset inside its wall). */
	public static final int INSET = 4;

	private Guide() {
	}
}
