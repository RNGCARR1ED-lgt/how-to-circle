package com.howtobuild.tools;

import com.howtobuild.palette.WeightedPalette;

/**
 * One material layer of generated terrain, from the surface down.
 *
 * @param palette   the blocks of this layer and their percentages
 * @param thickness blocks thick; 0 = down to the base (only meaningful for the last layer)
 */
public record TerrainLayer(WeightedPalette palette, int thickness) {
	public TerrainLayer {
		thickness = Math.max(0, thickness);
	}
}
