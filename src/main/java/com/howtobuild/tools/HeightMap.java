package com.howtobuild.tools;

import java.util.Arrays;

/**
 * A snapshot of the existing ground height around the anchor, taken on the client thread before generation so pure
 * generators (terrain blending) can use the world without touching it.
 *
 * <p>Heights are the Y of the top solid block, relative to the anchor's Y; {@link #UNKNOWN} where nothing was sampled
 * (unloaded chunks, or no snapshot).
 */
public final class HeightMap {
	public static final int UNKNOWN = Integer.MIN_VALUE;
	public static final HeightMap NONE = new HeightMap(0, 0, 0, 0, new int[0]);

	private final int minX;
	private final int minZ;
	private final int width;
	private final int length;
	private final int[] heights;
	private final int hash;

	public HeightMap(int minX, int minZ, int width, int length, int[] heights) {
		this.minX = minX;
		this.minZ = minZ;
		this.width = width;
		this.length = length;
		this.heights = heights.clone();
		this.hash = 31 * (31 * (31 * minX + minZ) + width) + Arrays.hashCode(this.heights);
	}

	/** A flat snapshot (every column at {@code height}) of the given area, for tests. */
	public static HeightMap flat(int minX, int minZ, int width, int length, int height) {
		int[] h = new int[width * length];
		Arrays.fill(h, height);
		return new HeightMap(minX, minZ, width, length, h);
	}

	public int heightAt(int x, int z) {
		int u = x - minX;
		int v = z - minZ;

		if (u < 0 || v < 0 || u >= width || v >= length) return UNKNOWN;

		return heights[v * width + u];
	}

	public boolean isEmpty() {
		return heights.length == 0;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof HeightMap m && m.minX == minX && m.minZ == minZ && m.width == width && m.length == length && Arrays.equals(m.heights, heights);
	}

	@Override
	public int hashCode() {
		return hash;
	}
}
