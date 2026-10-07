package com.howtobuild.config;

import java.util.ArrayList;
import java.util.List;

import com.howtobuild.dimensions.DimensionFormat;
import com.howtobuild.dimensions.LabelComponent;
import com.howtobuild.dimensions.LabelFormat;
import com.howtobuild.dimensions.LabelPlacement;

/**
 * Label content and style. Changing any value rebuilds the label set immediately (the geometry is not regenerated).
 */
public final class LabelSettings {
	public boolean showDimensions = true;
	public boolean showPopups = true;
	public boolean showSummary = true;
	public boolean showOverall = true;
	public DimensionFormat format = DimensionFormat.FULL;
	public LabelFormat order = LabelFormat.LONG_BY_SHORT;
	public String template = "W {width} / H {height}";
	public String units = "";
	public int decimals = 1;
	public List<LabelComponent> components = new ArrayList<>(List.of(LabelComponent.TOOL, LabelComponent.WIDTH, LabelComponent.HEIGHT,
			LabelComponent.LENGTH, LabelComponent.RADIUS, LabelComponent.STEPS, LabelComponent.REVOLUTIONS));
	public LabelPlacement placement = LabelPlacement.AUTOMATIC;
	public float textSize = 1.0F;
	public float offset = 1.25F;
	public float opacity = 0.95F;
	public boolean background = true;
	public float backgroundOpacity = 0.62F;
	public boolean border = false;
	public int padding = 1;
	public boolean leaderLines = true;
	public boolean distanceScaling = true;
	public boolean billboard = true;
	public boolean throughWalls = false;
	public int textColor = 0xFFFFFF;
	public int backgroundColor = 0x0B2530;
	public int borderColor = 0x33D6FF;
	public float labelDistance = 64F;
	public float popupDistance = 40F;
	/** For 3D shapes, sections whose longest side is shorter than this are not labelled. */
	public int minSectionSize = 3;

	public void sanitize() {
		if (format == null) format = DimensionFormat.FULL;
		if (order == null) order = LabelFormat.LONG_BY_SHORT;
		if (placement == null) placement = LabelPlacement.AUTOMATIC;
		if (template == null) template = "{long}";
		if (units == null) units = "";
		if (components == null) components = new ArrayList<>();
		components.removeIf(java.util.Objects::isNull);
		decimals = Math.max(0, Math.min(3, decimals));
		textSize = clamp(textSize, 0.5F, 3F);
		offset = clamp(offset, 0.25F, 6F);
		opacity = clamp(opacity, 0.1F, 1F);
		backgroundOpacity = clamp(backgroundOpacity, 0F, 1F);
		padding = Math.max(0, Math.min(4, padding));
		labelDistance = clamp(labelDistance, 8F, 256F);
		popupDistance = clamp(popupDistance, 4F, 256F);
		minSectionSize = Math.max(1, Math.min(64, minSectionSize));
		textColor &= 0xFFFFFF;
		backgroundColor &= 0xFFFFFF;
		borderColor &= 0xFFFFFF;
	}

	static float clamp(float v, float min, float max) {
		return Float.isNaN(v) ? min : Math.max(min, Math.min(max, v));
	}
}
