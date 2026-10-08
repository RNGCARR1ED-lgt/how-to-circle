package com.howtobuild.config;

import java.util.ArrayList;
import java.util.List;

import com.howtobuild.palette.PaletteEntry;
import com.howtobuild.palette.WeightedPalette;
import com.howtobuild.tools.TerrainLayer;

/** One terrain material layer (top first): one or more blocks with percentages, and a thickness (0 = down to the base). */
public final class TerrainLayerConfig {
	public List<RandomConfig.Entry> blocks = new ArrayList<>();
	public int thickness = 1;

	public TerrainLayerConfig() {
	}

	public TerrainLayerConfig(int thickness, RandomConfig.Entry... entries) {
		this.thickness = thickness;
		this.blocks = new ArrayList<>(List.of(entries));
	}

	public static List<TerrainLayerConfig> defaults() {
		return new ArrayList<>(List.of(new TerrainLayerConfig(1, new RandomConfig.Entry("minecraft:grass_block", 100)),
				new TerrainLayerConfig(3, new RandomConfig.Entry("minecraft:dirt", 100)),
				new TerrainLayerConfig(0, new RandomConfig.Entry("minecraft:stone", 80), new RandomConfig.Entry("minecraft:andesite", 20))));
	}

	public void sanitize() {
		if (blocks == null) blocks = new ArrayList<>();
		blocks.removeIf(e -> e == null || e.block == null || e.block.isBlank());
		thickness = Math.max(0, Math.min(256, thickness));
	}

	public TerrainLayer toLayer() {
		return new TerrainLayer(new WeightedPalette(blocks.stream().map(e -> new PaletteEntry(e.block, e.weight, e.enabled)).toList(), true), thickness);
	}
}
