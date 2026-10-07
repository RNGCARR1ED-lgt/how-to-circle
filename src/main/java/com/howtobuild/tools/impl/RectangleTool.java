package com.howtobuild.tools.impl;

import com.howtobuild.geometry.Mask2D;

/** Rectangle: independent width and length. */
public final class RectangleTool extends PlanarShapeTool {
	@Override
	public String id() {
		return "rectangle";
	}

	@Override
	protected Mask2D mask(int width, int length) {
		return Mask2D.rectangle(width, length);
	}

	@Override
	protected boolean uniform() {
		return false;
	}
}
