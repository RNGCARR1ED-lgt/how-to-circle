package com.howtobuild.details;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The detailing choices shared by all tools.
 *
 * @param features      enabled detail features (only those a tool supports have an effect)
 * @param interval      spacing used by repeated details (bands, accents, arches, pillars), in blocks
 * @param pattern       two-material pattern applied to the main structure
 * @param patternSize   size of one pattern cell or band, in blocks
 * @param variation     deterministic material variation strength
 * @param seed          seed for variation (same seed → same result)
 */
public record DetailSettings(Set<DetailFeature> features, int interval, MaterialPattern pattern, int patternSize,
		MaterialVariation variation, long seed) {
	public static final DetailSettings NONE = new DetailSettings(EnumSet.noneOf(DetailFeature.class), 4, MaterialPattern.NONE, 1, MaterialVariation.NONE, 0);

	public DetailSettings {
		features = Collections.unmodifiableSet(features.isEmpty() ? EnumSet.noneOf(DetailFeature.class) : EnumSet.copyOf(features));
		interval = Math.max(2, interval);
		patternSize = Math.max(1, patternSize);
	}

	public boolean has(DetailFeature feature) {
		return features.contains(feature);
	}

	public DetailSettings withFeatures(Set<DetailFeature> newFeatures) {
		return new DetailSettings(newFeatures, interval, pattern, patternSize, variation, seed);
	}

	public static DetailSettings of(DetailFeature... features) {
		EnumSet<DetailFeature> set = EnumSet.noneOf(DetailFeature.class);
		Collections.addAll(set, features);
		return NONE.withFeatures(set);
	}
}
