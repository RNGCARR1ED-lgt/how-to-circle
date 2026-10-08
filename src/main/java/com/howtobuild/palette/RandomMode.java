package com.howtobuild.palette;

/** How palette weights are used. */
public enum RandomMode {
	/** The percentages decide the share of each block. */
	WEIGHTED,
	/** Every enabled block gets the same share. */
	FULLY_RANDOM,
	/** Weighted, with the seed locked so the same geometry always gives the same arrangement. */
	DETERMINISTIC
}
