package com.howtobuild.geometry;

/**
 * Factory for ovals (ellipses with independent width and height). The result is a {@link CircleShape}, which models
 * any block-grid ellipse; a circle is simply an oval whose width equals its height.
 */
public final class OvalShape {
	private OvalShape() {
	}

	public static CircleShape of(int width, int height, FillMode fillMode) {
		return CircleGenerator.generate(width, height, fillMode);
	}
}
