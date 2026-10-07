package com.howtobuild.geometry;

/**
 * Exact integer tests for 3D shapes, using doubled block-centre coordinates ({@code 2i + 1 - n}) so there are no
 * rounding errors and results are perfectly symmetric.
 */
public final class Solids {
	/** Largest supported extent per axis for ellipsoids (keeps all products inside a {@code long}). */
	public static final int MAX_EXTENT = 512;

	private Solids() {
	}

	/** Doubled offset of cell {@code i} from the centre of an extent {@code n}. */
	public static long doubled(int i, int n) {
		return 2L * i + 1 - n;
	}

	/**
	 * Whether cell {@code (i, j, k)} of a {@code w × h × l} box lies in the inscribed ellipsoid. Cells on the three
	 * central axis lines are always inside, so the shape always spans the full requested extents.
	 */
	public static boolean ellipsoid(int i, int j, int k, int w, int h, int l) {
		if (w <= 0 || h <= 0 || l <= 0 || i < 0 || j < 0 || k < 0 || i >= w || j >= h || k >= l) return false;

		long x = doubled(i, w);
		long y = doubled(j, h);
		long z = doubled(k, l);
		int central = (Math.abs(x) <= 1 ? 1 : 0) + (Math.abs(y) <= 1 ? 1 : 0) + (Math.abs(z) <= 1 ? 1 : 0);

		if (central >= 2) return true;

		long w2 = (long) w * w;
		long h2 = (long) h * h;
		long l2 = (long) l * l;
		return x * x * h2 * l2 + y * y * w2 * l2 + z * z * w2 * h2 <= w2 * h2 * l2;
	}

	/**
	 * Upper half-ellipsoid ("dome") of footprint {@code w × l} and height {@code h}: cell layers {@code j = 0..h-1}
	 * measured up from the base, with the full footprint at the base and a single centre column reaching height {@code h}.
	 */
	public static boolean dome(int i, int j, int k, int w, int h, int l) {
		if (w <= 0 || h <= 0 || l <= 0 || i < 0 || j < 0 || k < 0 || i >= w || j >= h || k >= l) return false;

		long x = doubled(i, w);
		long y = 2L * j + 1; // doubled height of the cell centre above the base
		long z = doubled(k, l);
		long hh = 2L * h;

		if (Math.abs(x) <= 1 && Math.abs(z) <= 1) return true;
		if (j == 0 && (Math.abs(x) <= 1 || Math.abs(z) <= 1)) return true;

		long w2 = (long) w * w;
		long h2 = hh * hh;
		long l2 = (long) l * l;
		return x * x * h2 * l2 + y * y * w2 * l2 + z * z * w2 * h2 <= w2 * h2 * l2;
	}
}
