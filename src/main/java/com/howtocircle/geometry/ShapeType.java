package com.howtocircle.geometry;

/**
 * Circle mode locks the height to the width; oval mode uses two independent dimensions.
 */
public enum ShapeType {
	CIRCLE,
	OVAL;

	public ShapeType next() {
		return this == CIRCLE ? OVAL : CIRCLE;
	}
}
