package com.howtobuild.dimensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.howtobuild.geometry.Box;

/**
 * Formats dimension labels. Every number comes from generated block coordinates (box sizes are
 * {@code max - min + 1}), so a label can never disagree with the geometry.
 */
public final class DimensionFormatter {
	public static final String TIMES = " × ";

	/**
	 * @param format    the format
	 * @param order     side order for {@link DimensionFormat#FULL}
	 * @param template  template for {@link DimensionFormat#CUSTOM}
	 * @param units     suffix appended to numbers (e.g. "" or " blocks")
	 * @param decimals  decimals for non-integer values
	 */
	public record Style(DimensionFormat format, LabelFormat order, String template, String units, int decimals) {
		public static final Style DEFAULT = new Style(DimensionFormat.FULL, LabelFormat.LONG_BY_SHORT, "{width} × {length}", "", 1);
	}

	private DimensionFormatter() {
	}

	/**
	 * Formats a section box. {@code planeNormalAxis} (0/1/2, or -1 for volumes) is the axis to ignore for flat shapes.
	 */
	public static String format(Box box, int planeNormalAxis, Style style, Map<String, String> extra) {
		int w = box.sizeX();
		int h = box.sizeY();
		int l = box.sizeZ();

		return switch (style.format()) {
			case FULL -> full(box, planeNormalAxis, style);
			case SIMPLIFIED -> number(box.longestSide(), style);
			case WIDTH_ONLY -> number(w, style) + " W";
			case HEIGHT_ONLY -> number(h, style) + " H";
			case NAMED -> named(w, h, l, planeNormalAxis, style);
			case CUSTOM -> template(style.template(), box, planeNormalAxis, style, extra);
		};
	}

	private static String full(Box box, int planeNormalAxis, Style style) {
		List<Integer> sides = new ArrayList<>();

		for (int axis = 0; axis < 3; axis++) {
			if (axis != planeNormalAxis) sides.add(box.size(axis));
		}

		// Drop sides of length 1 while more than two remain (a 7×1×1 line reads "7 × 1").
		for (int i = sides.size() - 1; i >= 0 && sides.size() > 2; i--) {
			if (sides.get(i) == 1) sides.remove(i);
		}

		if (style.order() == LabelFormat.LONG_BY_SHORT) {
			sides.sort((a, b) -> Integer.compare(b, a));
		}

		StringBuilder text = new StringBuilder();

		for (int i = 0; i < sides.size(); i++) {
			if (i > 0) text.append(TIMES);
			text.append(sides.get(i));
		}

		return text + style.units();
	}

	private static String named(int w, int h, int l, int planeNormalAxis, Style style) {
		List<String> parts = new ArrayList<>();

		if (planeNormalAxis != 0 && (w > 1 || planeNormalAxis == -1)) parts.add("WIDTH: " + number(w, style));
		if (planeNormalAxis != 1 && h > 1) parts.add("HEIGHT: " + number(h, style));
		if (planeNormalAxis != 2 && l > 1) parts.add("LENGTH: " + number(l, style));

		if (parts.isEmpty()) parts.add("WIDTH: " + number(w, style));

		return String.join(", ", parts);
	}

	/** Replaces {@code {name}} placeholders; unknown placeholders are left as they are. */
	public static String template(String template, Box box, int planeNormalAxis, Style style, Map<String, String> extra) {
		List<Integer> sides = new ArrayList<>();

		for (int axis = 0; axis < 3; axis++) {
			if (axis != planeNormalAxis) sides.add(box.size(axis));
		}

		sides.sort((a, b) -> Integer.compare(b, a));
		String result = template == null || template.isBlank() ? "{long}" : template;
		result = result.replace("{width}", number(box.sizeX(), style))
				.replace("{height}", number(box.sizeY(), style))
				.replace("{length}", number(box.sizeZ(), style))
				.replace("{long}", number(sides.getFirst(), style))
				.replace("{short}", number(sides.getLast(), style))
				.replace("{blocks}", Long.toString(box.volume()));

		if (extra != null) {
			for (Map.Entry<String, String> e : extra.entrySet()) {
				result = result.replace("{" + e.getKey() + "}", e.getValue());
			}
		}

		return result;
	}

	public static String number(double value, Style style) {
		if (Math.abs(value - Math.rint(value)) < 1e-9) return Long.toString(Math.round(value)) + style.units();

		return String.format(Locale.ROOT, "%." + Math.max(0, Math.min(3, style.decimals())) + "f", value) + style.units();
	}

	private static String number(int value, Style style) {
		return value + style.units();
	}
}
