package com.howtobuild.geometry;

/**
 * Packs block offsets into a {@code long} (21 signed bits per axis) for allocation-free hash sets and maps.
 */
public final class Voxels {
	private static final int BITS = 21;
	private static final long MASK = (1L << BITS) - 1;
	public static final int LIMIT = 1 << (BITS - 1);

	private Voxels() {
	}

	public static long pack(int x, int y, int z) {
		return ((x & MASK) << (2 * BITS)) | ((y & MASK) << BITS) | (z & MASK);
	}

	public static int x(long key) {
		return (int) ((key << (64 - 3 * BITS)) >> (64 - BITS));
	}

	public static int y(long key) {
		return (int) ((key << (64 - 2 * BITS)) >> (64 - BITS));
	}

	public static int z(long key) {
		return (int) ((key << (64 - BITS)) >> (64 - BITS));
	}
}
