package com.howtobuild.config;

/**
 * Hologram appearance.
 */
public final class HologramConfig {
	public boolean visible = true;
	public float opacity = 0.42F;
	/** Tint each block with its material's map colour (otherwise use a single hologram colour per role). */
	public boolean colorByMaterial = true;
	public int color = 0x33D6FF;
	public boolean seeThroughBlocks = false;
	public boolean showEdges = true;
	public boolean showCentre = true;
	/** Draw reference outlines such as the master circle a spiral follows. */
	public boolean showGuides = true;
	/** Show exact bounds (min / max X and Z), size and centre on the HUD for checking geometry. */
	public boolean debug = false;

	public void sanitize() {
		opacity = LabelSettings.clamp(opacity, 0.05F, 0.9F);
		color &= 0xFFFFFF;
	}
}
