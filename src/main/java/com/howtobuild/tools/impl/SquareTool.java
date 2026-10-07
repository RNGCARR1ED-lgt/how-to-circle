package com.howtobuild.tools.impl;

import com.howtobuild.geometry.Mask2D;

/** Square: one side length. */
public final class SquareTool extends PlanarShapeTool {
	@Override
	public String id() {
		return "square";
	}

	@Override
	protected Mask2D mask(int width, int length) {
		return Mask2D.rectangle(width, length);
	}

	@Override
	protected boolean uniform() {
		return true;
	}
}
