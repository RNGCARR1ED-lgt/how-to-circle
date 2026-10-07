package com.howtobuild.geometry;

/**
 * The structural purpose of a placement. Tools emit roles; the player's material profile decides which block each
 * role becomes. This keeps blocks out of the geometry code entirely.
 */
public enum MaterialRole {
	/** Main structure. */
	PRIMARY,
	/** Edges, rims, rings, bands, frames. */
	TRIM,
	/** Accents, keystones, crowns, highlights. */
	ACCENT,
	/** Walking surface of stairs. */
	STEP,
	/** Supports, columns, pillars, undersides. */
	SUPPORT,
	/** Caps and closed ends. */
	CAP,
	/** Inner surface of thick walls. */
	INNER,
	/** Railings. */
	RAIL,
	/** Floors. */
	FLOOR;

	public String key() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
