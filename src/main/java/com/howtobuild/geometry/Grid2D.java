package com.howtobuild.geometry;

/**
 * A rectangular grid of occupied cells, as used by the 2D connected-section detector.
 */
public interface Grid2D {
	int width();

	int height();

	/** Whether cell {@code (u, v)} is occupied; out-of-range cells are empty. */
	boolean contains(int u, int v);
}
