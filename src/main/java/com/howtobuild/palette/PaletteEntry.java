package com.howtobuild.palette;

/**
 * One block of a weighted palette.
 *
 * @param block   block id, e.g. {@code minecraft:mossy_stone_bricks}
 * @param weight  relative weight (percent when the palette is normalised)
 * @param enabled disabled entries are kept in the list but not used
 */
public record PaletteEntry(String block, double weight, boolean enabled) {
	public PaletteEntry {
		weight = Double.isNaN(weight) ? 0 : Math.max(0, weight);
	}

	public static PaletteEntry of(String block, double weight) {
		return new PaletteEntry(block, weight, true);
	}
}
