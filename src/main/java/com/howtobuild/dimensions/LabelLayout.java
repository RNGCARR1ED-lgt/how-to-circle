package com.howtobuild.dimensions;

import java.util.Arrays;

/**
 * Greedy, allocation-free overlap avoidance for dimension labels in screen space.
 *
 * <p>Labels are added as axis-aligned rectangles (centre plus half extents) in any 2D screen-like coordinate system
 * together with a priority. {@link #solve()} places them from highest to lowest priority. A label that would overlap an
 * already placed label is nudged up or down by whole label heights (up to {@link #MAX_NUDGES} times in each direction);
 * if no free spot is found it is hidden. Placed labels are indexed in a spatial hash so the cost stays roughly linear in
 * the number of labels.
 *
 * <p>The arrays are reused between frames, so steady-state use performs no allocations.
 */
public final class LabelLayout {
	public static final int MAX_NUDGES = 2;
	private static final int TABLE_SIZE = 1 << 12;
	private static final double GAP = 1.08;

	private int count;
	private double[] x = new double[64];
	private double[] y = new double[64];
	private double[] halfW = new double[64];
	private double[] halfH = new double[64];
	private double[] priority = new double[64];
	private double[] shift = new double[64];
	private boolean[] visible = new boolean[64];
	private Integer[] order = new Integer[64];

	// Spatial hash of placed rectangles: bucket heads and a node pool of (label index, next node).
	private final int[] heads = new int[TABLE_SIZE];
	private int[] nodeLabel = new int[256];
	private int[] nodeNext = new int[256];
	private int nodeCount;
	private double cellSize = 1;

	public void clear() {
		count = 0;
	}

	public int size() {
		return count;
	}

	/**
	 * Adds a label and returns its index.
	 */
	public int add(double centreX, double centreY, double halfWidth, double halfHeight, double labelPriority) {
		ensureCapacity(count + 1);
		int i = count++;
		x[i] = centreX;
		y[i] = centreY;
		halfW[i] = halfWidth;
		halfH[i] = halfHeight;
		priority[i] = labelPriority;
		shift[i] = 0;
		visible[i] = false;
		return i;
	}

	/** Whether the label found a free spot. */
	public boolean visible(int index) {
		return visible[index];
	}

	/** Vertical displacement applied to the label, in the same units as its coordinates. */
	public double shiftY(int index) {
		return shift[index];
	}

	public void solve() {
		if (count == 0) return;

		double maxExtent = 0;

		for (int i = 0; i < count; i++) {
			maxExtent = Math.max(maxExtent, Math.max(halfW[i], halfH[i]));
		}

		cellSize = Math.max(maxExtent * 2, 1e-9);
		Arrays.fill(heads, -1);
		nodeCount = 0;

		for (int i = 0; i < count; i++) {
			order[i] = i;
		}

		Arrays.sort(order, 0, count, (a, b) -> {
			int byPriority = Double.compare(priority[b], priority[a]);
			return byPriority != 0 ? byPriority : Integer.compare(a, b);
		});

		for (int k = 0; k < count; k++) {
			int i = order[k];
			visible[i] = false;

			for (int attempt = 0; attempt <= MAX_NUDGES * 2; attempt++) {
				// 0, +1, -1, +2, -2 label heights
				int step = (attempt + 1) / 2;
				double candidate = (attempt % 2 == 1 ? step : -step) * halfH[i] * 2 * GAP;

				if (!overlaps(i, candidate)) {
					shift[i] = candidate;
					visible[i] = true;
					insert(i);
					break;
				}
			}
		}
	}

	private boolean overlaps(int i, double dy) {
		double cy = y[i] + dy;
		int minCx = cell(x[i] - halfW[i]);
		int maxCx = cell(x[i] + halfW[i]);
		int minCy = cell(cy - halfH[i]);
		int maxCy = cell(cy + halfH[i]);

		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cyi = minCy; cyi <= maxCy; cyi++) {
				for (int node = heads[bucket(cx, cyi)]; node != -1; node = nodeNext[node]) {
					int j = nodeLabel[node];

					if (Math.abs(x[i] - x[j]) < halfW[i] + halfW[j]
							&& Math.abs(cy - (y[j] + shift[j])) < halfH[i] + halfH[j]) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private void insert(int i) {
		double cy = y[i] + shift[i];
		int minCx = cell(x[i] - halfW[i]);
		int maxCx = cell(x[i] + halfW[i]);
		int minCy = cell(cy - halfH[i]);
		int maxCy = cell(cy + halfH[i]);

		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cyi = minCy; cyi <= maxCy; cyi++) {
				if (nodeCount == nodeLabel.length) {
					nodeLabel = Arrays.copyOf(nodeLabel, nodeCount * 2);
					nodeNext = Arrays.copyOf(nodeNext, nodeCount * 2);
				}

				int bucket = bucket(cx, cyi);
				nodeLabel[nodeCount] = i;
				nodeNext[nodeCount] = heads[bucket];
				heads[bucket] = nodeCount++;
			}
		}
	}

	private int cell(double value) {
		return (int) Math.floor(value / cellSize);
	}

	private static int bucket(int cx, int cy) {
		return ((cx * 73856093) ^ (cy * 19349663)) & (TABLE_SIZE - 1);
	}

	private void ensureCapacity(int needed) {
		if (needed <= x.length) return;

		int size = Math.max(needed, x.length * 2);
		x = Arrays.copyOf(x, size);
		y = Arrays.copyOf(y, size);
		halfW = Arrays.copyOf(halfW, size);
		halfH = Arrays.copyOf(halfH, size);
		priority = Arrays.copyOf(priority, size);
		shift = Arrays.copyOf(shift, size);
		visible = Arrays.copyOf(visible, size);
		order = Arrays.copyOf(order, size);
	}
}
