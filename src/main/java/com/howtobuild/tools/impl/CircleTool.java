package com.howtobuild.tools.impl;

import com.howtobuild.geometry.Mask2D;

/** Circle: one diameter, exact block-grid circle (the original How to Circle tool). */
public final class CircleTool extends PlanarShapeTool {
	@Override
	public String id() {
		return "circle";
	}

	@Override
	protected Mask2D mask(int width, int length) {
		return Mask2D.ellipse(width, length);
	}

	@Override
	protected boolean uniform() {
		return true;
	}
}
