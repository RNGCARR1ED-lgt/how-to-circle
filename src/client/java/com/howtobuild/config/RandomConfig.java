package com.howtobuild.config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.palette.MirrorRandomisation;
import com.howtobuild.palette.PaletteEntry;
import com.howtobuild.palette.RandomMode;
import com.howtobuild.palette.RandomPattern;
import com.howtobuild.palette.RandomSettings;
import com.howtobuild.palette.RandomSymmetry;
import com.howtobuild.palette.WeightedPalette;

/** The Randomise tab: the player's palette and how it is arranged (saved in the config and in presets). */
public final class RandomConfig {
	/** One palette row. */
	public static final class Entry {
		public String block = "minecraft:stone";
		public double weight = 50;
		public boolean enabled = true;

		public Entry() {
		}

		public Entry(String block, double weight) {
			this.block = block;
			this.weight = weight;
		}
	}

	public boolean enabled = false;
	public List<Entry> palette = defaultPalette();
	public boolean normalise = true;
	public RandomMode mode = RandomMode.WEIGHTED;
	public RandomPattern pattern = RandomPattern.NATURAL;
	public long seed = 1;
	public boolean lockSeed = false;
	public int clusterSize = 3;
	public int noiseScale = 8;
	public int noiseStrength = 80;
	public int octaves = 3;
	public int contrast = 10;
	public int threshold = 0;
	public List<String> roles = new ArrayList<>(List.of("primary"));
	public boolean protectEdge = false;
	public String edgeBlock = "";
	public int edgeVariation = 0;
	public RandomSymmetry symmetry = RandomSymmetry.INDEPENDENT;
	public MirrorRandomisation mirror = MirrorRandomisation.MIRRORED;

	public static List<Entry> defaultPalette() {
		return new ArrayList<>(List.of(new Entry("minecraft:stone_bricks", 50), new Entry("minecraft:cracked_stone_bricks", 25),
				new Entry("minecraft:mossy_stone_bricks", 25)));
	}

	public void sanitize() {
		if (palette == null) palette = defaultPalette();
		palette.removeIf(e -> e == null || e.block == null || e.block.isBlank());
		palette.forEach(e -> e.weight = Math.max(0, Math.min(1000, Double.isFinite(e.weight) ? e.weight : 0)));
		if (mode == null) mode = RandomMode.WEIGHTED;
		if (pattern == null) pattern = RandomPattern.NATURAL;
		clusterSize = clamp(clusterSize, 1, 64);
		noiseScale = clamp(noiseScale, 1, 256);
		noiseStrength = clamp(noiseStrength, 0, 100);
		octaves = clamp(octaves, 1, 8);
		contrast = clamp(contrast, 1, 100);
		threshold = clamp(threshold, -100, 100);
		if (roles == null) roles = new ArrayList<>(List.of("primary"));
		if (edgeBlock == null) edgeBlock = "";
		edgeVariation = clamp(edgeVariation, 0, 100);
		if (symmetry == null) symmetry = RandomSymmetry.INDEPENDENT;
		if (mirror == null) mirror = MirrorRandomisation.MIRRORED;
	}

	public WeightedPalette weightedPalette() {
		return new WeightedPalette(palette.stream().map(e -> new PaletteEntry(e.block, e.weight, e.enabled)).toList(), normalise);
	}

	public EnumSet<MaterialRole> roleSet() {
		EnumSet<MaterialRole> set = EnumSet.noneOf(MaterialRole.class);

		for (String r : roles) {
			try {
				set.add(MaterialRole.valueOf(r.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				// Unknown roles from newer versions are ignored.
			}
		}

		return set;
	}

	public boolean hasRole(MaterialRole role) {
		return roleSet().contains(role);
	}

	public void toggleRole(MaterialRole role) {
		if (!roles.remove(role.key())) roles.add(role.key());
	}

	public RandomSettings toSettings() {
		return new RandomSettings(enabled, weightedPalette(), mode, pattern, seed, clusterSize, noiseScale, noiseStrength / 100.0, octaves, contrast / 10.0,
				threshold / 100.0, roleSet(), protectEdge, edgeBlock, edgeVariation, symmetry, mirror);
	}

	/** A new random seed (unless locked). */
	public void reseed() {
		if (!lockSeed) seed = Math.floorMod(new java.util.Random().nextLong(), 1_000_000L);
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
