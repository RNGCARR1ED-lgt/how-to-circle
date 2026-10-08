package com.howtobuild.geometry;

/** A text label at a block position relative to the anchor (e.g. {@code Y=90} on a contour line). */
public record Annotation(int x, int y, int z, String text) {
}
