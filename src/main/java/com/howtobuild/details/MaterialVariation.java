package com.howtobuild.details;

/**
 * Deterministic material variation: a fraction of blocks use one of the role's variant blocks (e.g. cracked or mossy
 * stone bricks). The choice is a hash of the position and seed, so rebuilding gives exactly the same pattern.
 */
public enum MaterialVariation {
	NONE(0),
	SUBTLE(0.12),
	MEDIUM(0.3),
	HEAVY(0.55);

	public static final int MAX_VARIANTS = 3;

	private final double fraction;

	MaterialVariation(double fraction) {
		this.fraction = fraction;
	}

	public double fraction() {
		return fraction;
	}

	/** 0 for the main block, otherwise 1..{@link #MAX_VARIANTS}. */
	public int variant(long seed, int x, int y, int z) {
		if (this == NONE) return 0;

		long h = mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L));
		double unit = (h >>> 11) * 0x1.0p-53;

		if (unit >= fraction) return 0;

		return 1 + Math.floorMod(mix(h), MAX_VARIANTS);
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
