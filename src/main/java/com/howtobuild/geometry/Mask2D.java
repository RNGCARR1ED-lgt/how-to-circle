package com.howtobuild.geometry;

import java.util.BitSet;

/**
 * An immutable 2D cell mask of {@code width × height} cells, used as cross-sections by every tool.
 */
public final class Mask2D implements Grid2D {
	private final int width;
	private final int height;
	private final BitSet cells;

	private Mask2D(int width, int height, BitSet cells) {
		this.width = width;
		this.height = height;
		this.cells = cells;
	}

	/** The filled block-grid ellipse with exactly these extents (same rule as {@link CircleGenerator}). */
	public static Mask2D ellipse(int width, int height) {
		if (width <= 0 || height <= 0) return new Mask2D(Math.max(width, 0), Math.max(height, 0), new BitSet());
		return new Mask2D(width, height, CircleGenerator.filled(width, height));
	}

	/** A mask of the cells for which {@code test} returns true. */
	public static Mask2D of(int width, int height, java.util.function.BiPredicate<Integer, Integer> test) {
		BitSet bits = new BitSet(Math.max(0, width * height));

		for (int v = 0; v < height; v++) {
			for (int u = 0; u < width; u++) {
				if (test.test(u, v)) bits.set(v * width + u);
			}
		}

		return new Mask2D(Math.max(width, 0), Math.max(height, 0), bits);
	}

	public static Mask2D rectangle(int width, int height) {
		BitSet bits = new BitSet(Math.max(0, width * height));
		bits.set(0, Math.max(0, width * height));
		return new Mask2D(Math.max(width, 0), Math.max(height, 0), bits);
	}

	/** Cells of this mask with at least one empty edge-neighbour (the classic one-block outline). */
	public Mask2D outline() {
		return new Mask2D(width, height, CircleGenerator.outline(cells, width, height));
	}

	/**
	 * One erosion step: keeps cells whose four edge-neighbours are all set. {@code solidBelow} treats the row under
	 * {@code v = 0} as set (keeps the base of an arch open).
	 */
	public Mask2D erode(boolean solidBelow) {
		BitSet bits = new BitSet(width * height);

		for (int v = 0; v < height; v++) {
			for (int u = 0; u < width; u++) {
				if (contains(u, v) && contains(u - 1, v) && contains(u + 1, v) && contains(u, v + 1)
						&& (contains(u, v - 1) || (solidBelow && v == 0))) {
					bits.set(v * width + u);
				}
			}
		}

		return new Mask2D(width, height, bits);
	}

	/** This mask eroded {@code times} times. */
	public Mask2D eroded(int times, boolean solidBelow) {
		Mask2D result = this;

		for (int i = 0; i < times; i++) {
			result = result.erode(solidBelow);
		}

		return result;
	}

	/**
	 * A ring exactly {@code thickness} cells thick: this mask minus its {@code thickness}-times erosion. Gap-free, and
	 * identical to {@link #outline()} for a thickness of 1.
	 */
	public Mask2D ring(int thickness) {
		if (thickness <= 0) return this;
		return minus(eroded(thickness, false), 0, 0);
	}

	public Mask2D minus(Mask2D other, int offsetU, int offsetV) {
		BitSet bits = (BitSet) cells.clone();

		for (int v = 0; v < other.height; v++) {
			for (int u = 0; u < other.width; u++) {
				int tu = u + offsetU;
				int tv = v + offsetV;

				if (other.contains(u, v) && tu >= 0 && tv >= 0 && tu < width && tv < height) bits.clear(tv * width + tu);
			}
		}

		return new Mask2D(width, height, bits);
	}

	@Override
	public int width() {
		return width;
	}

	@Override
	public int height() {
		return height;
	}

	@Override
	public boolean contains(int u, int v) {
		return u >= 0 && v >= 0 && u < width && v < height && cells.get(v * width + u);
	}

	public int count() {
		return cells.cardinality();
	}
}
