package com.howtobuild.geometry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Rotates and mirrors block state strings without Minecraft classes, so exact saved states can go through the same
 * pure pipeline (rotation about the true centre, mirroring) as generated shapes.
 *
 * <p>Every property that carries an orientation is rewritten:
 * <ul>
 *     <li>{@code facing} and any value made of direction words ({@code orientation}, rail {@code shape} such as
 *     {@code ascending_east} or {@code north_west});</li>
 *     <li>{@code axis} (x ↔ z on quarter turns);</li>
 *     <li>{@code rotation} (0–15, signs and banners);</li>
 *     <li>stair {@code shape} (left ↔ right when mirrored), door {@code hinge}, chest {@code type} (left ↔ right);</li>
 *     <li>side connections {@code north / east / south / west} (fences, walls, panes, redstone);</li>
 *     <li>vertical flips: {@code facing} up ↔ down, {@code half} and slab {@code type} top ↔ bottom, {@code face} floor ↔
 *     ceiling.</li>
 * </ul>
 * Properties that do not exist on a state are simply not touched.
 */
public final class StateTransform {
	private static final String[] HORIZONTAL = {"north", "east", "south", "west"};
	private static final Set<String> STAIR_SHAPES = Set.of("straight", "inner_left", "inner_right", "outer_left", "outer_right");

	private StateTransform() {
	}

	/** Splits {@code ns:id[k=v,…]} into the id and an ordered property map. */
	public static Map<String, String> properties(String state) {
		Map<String, String> map = new LinkedHashMap<>();
		int open = state.indexOf('[');
		int close = state.lastIndexOf(']');

		if (open < 0 || close < open) return map;

		for (String pair : state.substring(open + 1, close).split(",")) {
			int eq = pair.indexOf('=');

			if (eq > 0) map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
		}

		return map;
	}

	public static String blockId(String state) {
		int open = state.indexOf('[');
		return open < 0 ? state : state.substring(0, open);
	}

	public static String compose(String id, Map<String, String> properties) {
		if (properties.isEmpty()) return id;

		StringBuilder s = new StringBuilder(id).append('[');
		boolean first = true;

		for (Map.Entry<String, String> e : properties.entrySet()) {
			if (!first) s.append(',');
			s.append(e.getKey()).append('=').append(e.getValue());
			first = false;
		}

		return s.append(']').toString();
	}

	/** Clockwise (seen from above) quarter turns about the vertical axis. */
	public static String rotateY(String state, int quarterTurns) {
		int q = quarterTurns & 3;

		if (q == 0 || state.indexOf('[') < 0) return state;

		Map<String, String> in = properties(state);
		Map<String, String> out = new LinkedHashMap<>();

		for (Map.Entry<String, String> e : in.entrySet()) {
			String key = e.getKey();
			String value = e.getValue();

			switch (key) {
				case "axis" -> value = (q & 1) == 1 ? (value.equals("x") ? "z" : value.equals("z") ? "x" : value) : value;
				case "rotation" -> value = Integer.toString(Math.floorMod(parse(value) + 4 * q, 16));
				case "north", "east", "south", "west" -> key = turn(key, q);
				default -> value = mapDirectionWords(value, w -> turn(w, q));
			}

			out.put(key, value);
		}

		return compose(blockId(state), reorder(in, out));
	}

	/**
	 * Mirror: {@code acrossX} reflects x → −x (east ↔ west), otherwise z → −z (north ↔ south). Handedness flips either
	 * way (stair corners, hinges, chest halves).
	 */
	public static String mirror(String state, boolean acrossX) {
		if (state.indexOf('[') < 0) return state;

		Map<String, String> in = properties(state);
		Map<String, String> out = new LinkedHashMap<>();

		for (Map.Entry<String, String> e : in.entrySet()) {
			String key = e.getKey();
			String value = e.getValue();

			switch (key) {
				case "rotation" -> value = Integer.toString(Math.floorMod((acrossX ? 16 : 8) - parse(value), 16));
				case "hinge" -> value = value.equals("left") ? "right" : value.equals("right") ? "left" : value;
				case "type" -> value = value.equals("left") ? "right" : value.equals("right") ? "left" : value;
				case "north", "east", "south", "west" -> key = flip(key, acrossX);
				case "shape" -> value = STAIR_SHAPES.contains(value) ? swapHand(value) : mapDirectionWords(value, w -> flip(w, acrossX));
				default -> value = mapDirectionWords(value, w -> flip(w, acrossX));
			}

			out.put(key, value);
		}

		return compose(blockId(state), reorder(in, out));
	}

	/** Upside down (reflect y → −y): facing up ↔ down, top ↔ bottom halves, floor ↔ ceiling attachments. */
	public static String flipVertical(String state) {
		if (state.indexOf('[') < 0) return state;

		Map<String, String> in = properties(state);
		Map<String, String> out = new LinkedHashMap<>();

		for (Map.Entry<String, String> e : in.entrySet()) {
			String key = e.getKey();
			String value = e.getValue();

			switch (key) {
				case "half", "type" -> value = value.equals("top") ? "bottom" : value.equals("bottom") ? "top" : value;
				case "face", "attach_face" -> value = value.equals("floor") ? "ceiling" : value.equals("ceiling") ? "floor" : value;
				case "up", "down" -> key = key.equals("up") ? "down" : "up";
				default -> value = mapDirectionWords(value, w -> w.equals("up") ? "down" : w.equals("down") ? "up" : w);
			}

			out.put(key, value);
		}

		return compose(blockId(state), reorder(in, out));
	}

	private static Map<String, String> reorder(Map<String, String> original, Map<String, String> transformed) {
		// Keep the original key order where keys survived (connection keys are permuted among themselves).
		Map<String, String> ordered = new LinkedHashMap<>();

		for (String key : original.keySet()) {
			if (transformed.containsKey(key)) ordered.put(key, transformed.get(key));
		}

		for (Map.Entry<String, String> e : transformed.entrySet()) {
			ordered.putIfAbsent(e.getKey(), e.getValue());
		}

		return ordered;
	}

	private static String swapHand(String shape) {
		if (shape.endsWith("_left")) return shape.substring(0, shape.length() - 5) + "_right";
		if (shape.endsWith("_right")) return shape.substring(0, shape.length() - 6) + "_left";
		return shape;
	}

	private static String turn(String word, int q) {
		for (int i = 0; i < 4; i++) {
			if (HORIZONTAL[i].equals(word)) return HORIZONTAL[(i + q) & 3];
		}

		return word;
	}

	private static String flip(String word, boolean acrossX) {
		if (acrossX) return word.equals("east") ? "west" : word.equals("west") ? "east" : word;
		return word.equals("north") ? "south" : word.equals("south") ? "north" : word;
	}

	/** Maps every direction word in an underscore-joined value ("ascending_east", "north_up", "east"). */
	private static String mapDirectionWords(String value, java.util.function.UnaryOperator<String> map) {
		String[] parts = value.split("_");
		boolean changed = false;

		for (int i = 0; i < parts.length; i++) {
			String mapped = map.apply(parts[i]);

			if (!mapped.equals(parts[i])) {
				parts[i] = mapped;
				changed = true;
			}
		}

		return changed ? String.join("_", parts) : value;
	}

	private static int parse(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
