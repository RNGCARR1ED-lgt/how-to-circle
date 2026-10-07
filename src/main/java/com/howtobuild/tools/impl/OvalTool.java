package com.howtobuild.tools.impl;

import com.howtobuild.geometry.Mask2D;

/** Oval: independent width and length. */
public final class OvalTool extends PlanarShapeTool {
	@Override
	public String id() {
		return "oval";
	}

	@Override
	protected Mask2D mask(int width, int length) {
		return Mask2D.ellipse(width, length);
	}

	@Override
	protected boolean uniform() {
		return false;
	}
}
