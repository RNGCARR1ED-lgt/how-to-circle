package com.howtocircle.geometry;

import java.util.BitSet;

/**
 * An immutable, block-grid aligned circle or oval.
 *
 * <p>Cells are addressed by {@code (u, v)} with {@code 0 <= u < width} and {@code 0 <= v < height}. The {@code u} axis
 * runs along the shape's width, the {@code v} axis along its height. How those axes map onto the Minecraft world is
 * decided by {@link ShapePlacement}.
 *
 * <p>Instances are created by {@link CircleGenerator}; use {@link #circle(int, FillMode)} or
 * {@link OvalShape#of(int, int, FillMode)} for convenience.
 */
public final class CircleShape {
	private final int width;
	private final int height;
	private final FillMode fillMode;
	private final BitSet cells;
	private final int blockCount;

	CircleShape(int width, int height, FillMode fillMode, BitSet cells) {
		this.width = width;
		this.height = height;
		this.fillMode = fillMode;
		this.cells = cells;
		this.blockCount = cells.cardinality();
	}

	/** Generates a circle with the given diameter. */
	public static CircleShape circle(int diameter, FillMode fillMode) {
		return CircleGenerator.generate(diameter, diameter, fillMode);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public FillMode fillMode() {
		return fillMode;
	}

	public boolean isCircle() {
		return width == height;
	}

	public int blockCount() {
		return blockCount;
	}

	/** Returns whether the cell is part of the shape; out-of-range coordinates return {@code false}. */
	public boolean contains(int u, int v) {
		return u >= 0 && v >= 0 && u < width && v < height && cells.get(v * width + u);
	}

	/** Whether the cell is one of the 1 or 2 central cells along both axes. */
	public boolean isCentreCell(int u, int v) {
		return Math.abs(2 * u + 1 - width) <= 1 && Math.abs(2 * v + 1 - height) <= 1;
	}

	/** Number of blocks in row {@code v}. */
	public int rowCount(int v) {
		int count = 0;

		for (int u = 0; u < width; u++) {
			if (contains(u, v)) count++;
		}

		return count;
	}

	/** Number of blocks in column {@code u}. */
	public int columnCount(int u) {
		int count = 0;

		for (int v = 0; v < height; v++) {
			if (contains(u, v)) count++;
		}

		return count;
	}

	/** A copy of the backing cell set, indexed {@code v * width + u}. */
	public BitSet cellsCopy() {
		return (BitSet) cells.clone();
	}

	/**
	 * Renders the shape as text ({@code #} = block, {@code .} = empty), one row per line. Useful for tests and logs.
	 */
	public String toAscii() {
		StringBuilder builder = new StringBuilder((width + 1) * height);

		for (int v = 0; v < height; v++) {
			for (int u = 0; u < width; u++) {
				builder.append(contains(u, v) ? '#' : '.');
			}

			builder.append('\n');
		}

		return builder.toString();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof CircleShape other && width == other.width && height == other.height
				&& fillMode == other.fillMode && cells.equals(other.cells);
	}

	@Override
	public int hashCode() {
		return 31 * (31 * (31 * width + height) + fillMode.hashCode()) + cells.hashCode();
	}

	@Override
	public String toString() {
		return "CircleShape[" + width + "×" + height + ", " + fillMode + ", " + blockCount + " blocks]";
	}
}
