package com.howtobuild.tools;

import java.util.List;

import com.howtobuild.palette.RandomSettings;
import com.howtobuild.palette.WeightedPalette;

/**
 * Material inputs shared by every tool: the randomisation settings, the terrain layers and the existing-ground
 * snapshot.
 */
public record Palettes(RandomSettings random, List<TerrainLayer> terrainLayers, HeightMap heights) {
	public static final List<TerrainLayer> DEFAULT_TERRAIN = List.of(
			new TerrainLayer(WeightedPalette.single("minecraft:grass_block"), 1),
			new TerrainLayer(WeightedPalette.single("minecraft:dirt"), 3),
			new TerrainLayer(WeightedPalette.single("minecraft:stone"), 0));
	public static final Palettes NONE = new Palettes(RandomSettings.OFF, DEFAULT_TERRAIN, HeightMap.NONE);

	public Palettes {
		terrainLayers = terrainLayers == null || terrainLayers.isEmpty() ? DEFAULT_TERRAIN : List.copyOf(terrainLayers);
		heights = heights == null ? HeightMap.NONE : heights;
		random = random == null ? RandomSettings.OFF : random;
	}

	public Palettes withRandom(RandomSettings newRandom) {
		return new Palettes(newRandom, terrainLayers, heights);
	}

	public Palettes withTerrainLayers(List<TerrainLayer> layers) {
		return new Palettes(random, layers, heights);
	}

	public Palettes withHeights(HeightMap newHeights) {
		return new Palettes(random, terrainLayers, newHeights);
	}
}
