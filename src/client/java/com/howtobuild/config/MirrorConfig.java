package com.howtobuild.config;

import com.howtobuild.transform.MirrorAxis;
import com.howtobuild.transform.MirrorMode;
import com.howtobuild.transform.MirrorSettings;

/**
 * Persisted mirror options. The mirror centre position itself belongs to the current world session.
 */
public final class MirrorConfig {
	public boolean enabled = false;
	public MirrorAxis axis = MirrorAxis.X;
	public MirrorMode mode = MirrorMode.DUPLICATE;
	/** Use the shape's own centre instead of a separately selected mirror centre. */
	public boolean useShapeCentre = true;
	public boolean twoWideX = false;
	public boolean twoWideZ = false;
	public boolean alignX = true;
	public boolean alignZ = true;
	public int offsetX = 0;
	public int offsetZ = 0;

	public MirrorSettings toSettings() {
		return new MirrorSettings(enabled, axis, mode, twoWideX, twoWideZ, alignX, alignZ, offsetX, offsetZ);
	}

	public void sanitize() {
		if (axis == null) axis = MirrorAxis.X;
		if (mode == null) mode = MirrorMode.DUPLICATE;
		offsetX = Math.max(-512, Math.min(512, offsetX));
		offsetZ = Math.max(-512, Math.min(512, offsetZ));
	}
}
