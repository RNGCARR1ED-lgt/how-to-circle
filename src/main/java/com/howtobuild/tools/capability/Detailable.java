package com.howtobuild.tools.capability;

import java.util.Set;

import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailPreset;

/**
 * The tool supports optional details.
 */
public interface Detailable {
	Set<DetailFeature> supportedDetails();

	/** The features a preset turns on for this tool. */
	Set<DetailFeature> presetDetails(DetailPreset preset);
}
