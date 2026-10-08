package com.howtobuild.palette;

import java.util.EnumSet;
import java.util.Set;

import com.howtobuild.geometry.MaterialRole;

/**
 * Everything that decides a randomised arrangement. With the same geometry and the same settings (including the seed)
 * the result is always identical.
 *
 * @param enabled        randomise the selected roles of every tool
 * @param palette        blocks and percentages
 * @param mode           weighted, fully random (equal shares) or deterministic
 * @param pattern        spatial structure
 * @param seed           random seed
 * @param clusterSize    size of clusters, stripes, rings and patches in blocks
 * @param noiseScale     noise feature size in blocks (noise / custom patterns)
 * @param noiseStrength  0–1: how much the noise (vs. per-block randomness) decides the arrangement
 * @param octaves        noise octaves
 * @param contrast       noise contrast (1 = unchanged)
 * @param threshold      −1..1: shifts the noise towards the first or the last palette entries
 * @param roles          material roles that are randomised (others keep their fixed material)
 * @param protectEdge    keep the outer edge in one block
 * @param edgeBlock      the edge block (empty: the first palette entry)
 * @param edgeVariation  0–100: percentage of edge blocks that are randomised anyway
 * @param symmetry       per block, per step or per revolution (spirals)
 * @param mirror         mirrored copies repeat or are randomised independently
 */
public record RandomSettings(boolean enabled, WeightedPalette palette, RandomMode mode, RandomPattern pattern, long seed, int clusterSize,
		double noiseScale, double noiseStrength, int octaves, double contrast, double threshold, Set<MaterialRole> roles, boolean protectEdge,
		String edgeBlock, int edgeVariation, RandomSymmetry symmetry, MirrorRandomisation mirror) {
	public static final RandomSettings OFF = new RandomSettings(false, new WeightedPalette(java.util.List.of(), true), RandomMode.WEIGHTED,
			RandomPattern.NATURAL, 1, 3, 8, 0.8, 3, 1, 0, EnumSet.of(MaterialRole.PRIMARY), false, "", 0, RandomSymmetry.INDEPENDENT,
			MirrorRandomisation.MIRRORED);

	public RandomSettings {
		roles = roles == null || roles.isEmpty() ? EnumSet.noneOf(MaterialRole.class) : EnumSet.copyOf(roles);
		clusterSize = Math.max(1, clusterSize);
		noiseScale = Math.max(0.5, noiseScale);
		noiseStrength = Math.max(0, Math.min(1, noiseStrength));
		octaves = Math.max(1, Math.min(8, octaves));
		contrast = Math.max(0.1, Math.min(10, contrast));
		threshold = Math.max(-1, Math.min(1, threshold));
		edgeVariation = Math.max(0, Math.min(100, edgeVariation));
		edgeBlock = edgeBlock == null ? "" : edgeBlock;
	}

	/** Settings for randomising every role with a palette (the Randomisation tool and terrain layers). */
	public static RandomSettings of(WeightedPalette palette, RandomPattern pattern, long seed) {
		return new RandomSettings(true, palette, RandomMode.WEIGHTED, pattern, seed, 3, 8, 0.8, 3, 1, 0, EnumSet.allOf(MaterialRole.class), false, "",
				0, RandomSymmetry.INDEPENDENT, MirrorRandomisation.MIRRORED);
	}

	public RandomSettings withSeed(long newSeed) {
		return new RandomSettings(enabled, palette, mode, pattern, newSeed, clusterSize, noiseScale, noiseStrength, octaves, contrast, threshold, roles,
				protectEdge, edgeBlock, edgeVariation, symmetry, mirror);
	}

	public RandomSettings withRoles(Set<MaterialRole> newRoles) {
		return new RandomSettings(enabled, palette, mode, pattern, seed, clusterSize, noiseScale, noiseStrength, octaves, contrast, threshold, newRoles,
				protectEdge, edgeBlock, edgeVariation, symmetry, mirror);
	}

	public RandomSettings withEdge(boolean protect, String block, int variation) {
		return new RandomSettings(enabled, palette, mode, pattern, seed, clusterSize, noiseScale, noiseStrength, octaves, contrast, threshold, roles,
				protect, block, variation, symmetry, mirror);
	}

	public RandomSettings withSymmetry(RandomSymmetry newSymmetry) {
		return new RandomSettings(enabled, palette, mode, pattern, seed, clusterSize, noiseScale, noiseStrength, octaves, contrast, threshold, roles,
				protectEdge, edgeBlock, edgeVariation, newSymmetry, mirror);
	}

	public RandomSettings withCluster(int size) {
		return new RandomSettings(enabled, palette, mode, pattern, seed, size, noiseScale, noiseStrength, octaves, contrast, threshold, roles,
				protectEdge, edgeBlock, edgeVariation, symmetry, mirror);
	}

	public boolean active() {
		return enabled && !palette.isEmpty() && !roles.isEmpty();
	}
}
