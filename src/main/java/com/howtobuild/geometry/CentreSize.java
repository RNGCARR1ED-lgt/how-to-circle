package com.howtobuild.geometry;

/**
 * How the centre of the shape is chosen.
 *
 * <p>A block shape can only be exactly centred on a single block when the dimension along that axis is odd, and only on
 * a pair of blocks (a block corner) when it is even. Forcing a centre size therefore adjusts the dimensions instead of
 * silently producing an off-centre shape; see {@link ResolvedDimensions}.
 */
public enum CentreSize {
	/** Derive the centre from the parity of each dimension (odd: 1 block, even: 2 blocks). */
	AUTO,
	/** Always use a 1×1 centre block; even dimensions are increased by one. */
	ONE_BY_ONE,
	/** Always use a 2×2 centre; odd dimensions are increased by one. */
	TWO_BY_TWO;

	public CentreSize next() {
		return values()[(ordinal() + 1) % values().length];
	}
}
