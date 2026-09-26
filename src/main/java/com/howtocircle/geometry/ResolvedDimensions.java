package com.howtocircle.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * The dimensions that will actually be generated, after applying the shape type and centre size rules.
 *
 * @param width     effective width in blocks
 * @param height    effective height in blocks
 * @param centreWidth  1 when the width is odd, 2 when it is even
 * @param centreHeight 1 when the height is odd, 2 when it is even
 * @param notes     human-readable explanations of every adjustment that was made (never silent)
 */
public record ResolvedDimensions(int width, int height, int centreWidth, int centreHeight, List<String> notes) {
	public static final int MIN_SIZE = 1;
	public static final int MAX_SIZE = 1024;

	public ResolvedDimensions {
		notes = List.copyOf(notes);
	}

	public boolean mixedParity() {
		return centreWidth != centreHeight;
	}

	public boolean adjusted() {
		return !notes.isEmpty();
	}

	public String centreLabel() {
		return centreWidth + "×" + centreHeight;
	}

	/**
	 * Resolves the requested dimensions.
	 *
	 * @param type            circle (height follows width) or oval
	 * @param requestedWidth  width typed by the player
	 * @param requestedHeight height typed by the player (ignored for circles)
	 * @param centreSize      centre size rule
	 */
	public static ResolvedDimensions resolve(ShapeType type, int requestedWidth, int requestedHeight, CentreSize centreSize) {
		List<String> notes = new ArrayList<>();
		int width = clamp(requestedWidth, "Width", notes);
		int height = type == ShapeType.CIRCLE ? width : clamp(requestedHeight, "Height", notes);

		switch (centreSize) {
			case ONE_BY_ONE -> {
				width = makeParity(width, true, "Width", "1×1", notes);
				height = type == ShapeType.CIRCLE ? width : makeParity(height, true, "Height", "1×1", notes);
			}
			case TWO_BY_TWO -> {
				width = makeParity(width, false, "Width", "2×2", notes);
				height = type == ShapeType.CIRCLE ? width : makeParity(height, false, "Height", "2×2", notes);
			}
			case AUTO -> {
			}
		}

		return new ResolvedDimensions(width, height, width % 2 == 1 ? 1 : 2, height % 2 == 1 ? 1 : 2, notes);
	}

	private static int clamp(int value, String name, List<String> notes) {
		if (value < MIN_SIZE) {
			notes.add(name + " " + value + " → " + MIN_SIZE + " (minimum)");
			return MIN_SIZE;
		}

		if (value > MAX_SIZE) {
			notes.add(name + " " + value + " → " + MAX_SIZE + " (maximum)");
			return MAX_SIZE;
		}

		return value;
	}

	private static int makeParity(int value, boolean odd, String name, String centre, List<String> notes) {
		boolean isOdd = value % 2 == 1;

		if (isOdd == odd) {
			return value;
		}

		// Grow rather than shrink so the shape is never smaller than requested (and never below 1).
		int adjusted = value + 1 <= MAX_SIZE ? value + 1 : value - 1;
		notes.add(name + " " + value + " → " + adjusted + " so the " + centre + " centre is exact");
		return adjusted;
	}
}
