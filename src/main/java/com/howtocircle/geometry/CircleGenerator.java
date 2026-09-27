package com.howtocircle.geometry;

import java.util.BitSet;

/**
 * Generates block-grid circles and ovals.
 *
 * <h2>Algorithm</h2>
 * A block belongs to the filled shape when the centre of the block lies inside (or on) the ellipse whose extents are
 * exactly {@code width × height} blocks. To make the test exact and perfectly symmetric the coordinates are doubled so
 * that every value is an integer:
 *
 * <pre>
 *   X = 2u + 1 - width      (block centre, relative to the shape centre, times two)
 *   Z = 2v + 1 - height
 *   inside  ⇔  X²·height² + Z²·width² ≤ width²·height²
 * </pre>
 *
 * Using integers (not floating point) means there are no rounding errors: the result is deterministic, identical for
 * mirrored cells, and has no gaps. The shape's central row(s) and column(s) always span the full requested width and
 * height, so the generated bounding box always matches the requested dimensions — for normal circles this is already
 * guaranteed by the inequality, for very thin even-sized ovals (e.g. 20×2) it corrects the extremes that would otherwise
 * lose their tips.
 *
 * <p>The outline is the set of filled blocks that have at least one empty edge-neighbour (or lie on the border of the
 * bounding box). This produces the classic one-block-thick Minecraft circle whose blocks touch along edges or at
 * diagonal steps, with no gaps.
 */
public final class CircleGenerator {
	private CircleGenerator() {
	}

	public static CircleShape generate(int width, int height, FillMode fillMode) {
		if (width < ResolvedDimensions.MIN_SIZE || height < ResolvedDimensions.MIN_SIZE) {
			throw new IllegalArgumentException("Dimensions must be at least 1, got " + width + "×" + height);
		}

		if (width > ResolvedDimensions.MAX_SIZE || height > ResolvedDimensions.MAX_SIZE) {
			throw new IllegalArgumentException("Dimensions must be at most " + ResolvedDimensions.MAX_SIZE + ", got " + width + "×" + height);
		}

		BitSet filled = filled(width, height);
		BitSet cells = fillMode == FillMode.FILLED ? filled : outline(filled, width, height);
		return new CircleShape(width, height, fillMode, cells);
	}

	static BitSet filled(int width, int height) {
		BitSet cells = new BitSet(width * height);
		long w2 = (long) width * width;
		long h2 = (long) height * height;
		long limit = w2 * h2;

		for (int v = 0; v < height; v++) {
			long z = 2L * v + 1 - height;
			boolean centralRow = Math.abs(z) <= 1;

			for (int u = 0; u < width; u++) {
				long x = 2L * u + 1 - width;
				boolean centralColumn = Math.abs(x) <= 1;

				if (centralRow || centralColumn || x * x * h2 + z * z * w2 <= limit) {
					cells.set(v * width + u);
				}
			}
		}

		return cells;
	}

	static BitSet outline(BitSet filled, int width, int height) {
		BitSet cells = new BitSet(width * height);

		for (int v = 0; v < height; v++) {
			for (int u = 0; u < width; u++) {
				if (!filled.get(v * width + u)) continue;

				boolean edge = u == 0 || v == 0 || u == width - 1 || v == height - 1
						|| !filled.get(v * width + u - 1)
						|| !filled.get(v * width + u + 1)
						|| !filled.get((v - 1) * width + u)
						|| !filled.get((v + 1) * width + u);

				if (edge) cells.set(v * width + u);
			}
		}

		return cells;
	}
}
