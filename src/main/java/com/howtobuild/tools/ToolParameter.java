package com.howtobuild.tools;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A declarative tool parameter. The GUI, config, presets and validation are all generated from these definitions, so
 * a new tool never needs GUI code.
 *
 * <p>Translation keys: {@code param.howtobuild.<id>} (label) and {@code param.howtobuild.<id>.tooltip}; enum values use
 * {@code enum.howtobuild.<value>}.
 */
public final class ToolParameter {
	public enum Type {
		INT,
		BOOL,
		ENUM,
		/** Free text (e.g. a saved build's name). */
		TEXT
	}

	private final String id;
	private final Type type;
	private final int min;
	private final int max;
	private final String defaultValue;
	private final List<String> options;
	private boolean advanced;
	private String section = "geometry";
	private Predicate<ToolSettings> visible = s -> true;

	private ToolParameter(String id, Type type, int min, int max, String defaultValue, List<String> options) {
		this.id = id;
		this.type = type;
		this.min = min;
		this.max = max;
		this.defaultValue = defaultValue;
		this.options = options;
	}

	public static ToolParameter integer(String id, int min, int max, int defaultValue) {
		return new ToolParameter(id, Type.INT, min, max, Integer.toString(defaultValue), List.of());
	}

	public static ToolParameter bool(String id, boolean defaultValue) {
		return new ToolParameter(id, Type.BOOL, 0, 1, Boolean.toString(defaultValue), List.of());
	}

	/** A text value of at most 128 characters. */
	public static ToolParameter text(String id, String defaultValue) {
		return new ToolParameter(id, Type.TEXT, 0, 128, defaultValue, List.of());
	}

	public static <E extends Enum<E>> ToolParameter choice(String id, E defaultValue) {
		List<String> values = Arrays.stream(defaultValue.getDeclaringClass().getEnumConstants()).map(Enum::name).toList();
		return new ToolParameter(id, Type.ENUM, 0, values.size() - 1, defaultValue.name(), values);
	}

	/** Only shown in Advanced mode. */
	public ToolParameter advanced() {
		this.advanced = true;
		return this;
	}

	/** The heading this parameter is grouped under in the GUI. */
	public ToolParameter section(String sectionKey) {
		this.section = sectionKey;
		return this;
	}

	/** Hides the parameter when it does not apply (e.g. the second diameter in circle mode). */
	public ToolParameter visibleWhen(Predicate<ToolSettings> predicate) {
		this.visible = predicate;
		return this;
	}

	public String id() {
		return id;
	}

	public Type type() {
		return type;
	}

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	public String defaultValue() {
		return defaultValue;
	}

	public List<String> options() {
		return options;
	}

	public boolean isAdvanced() {
		return advanced;
	}

	public String sectionKey() {
		return section;
	}

	public boolean isVisible(ToolSettings settings) {
		return visible.test(settings);
	}

	public String labelKey() {
		return "param.howtobuild." + id;
	}

	public String tooltipKey() {
		return labelKey() + ".tooltip";
	}

	public static String optionKey(String option) {
		return "enum.howtobuild." + option.toLowerCase(Locale.ROOT);
	}

	/** Normalises a raw (e.g. user typed or loaded) value into a valid value for this parameter. */
	public String sanitize(String raw) {
		if (raw == null) return defaultValue;

		return switch (type) {
			case INT -> {
				try {
					yield Integer.toString(Math.max(min, Math.min(max, Integer.parseInt(raw.trim()))));
				} catch (NumberFormatException e) {
					yield defaultValue;
				}
			}
			case BOOL -> raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false") ? raw.toLowerCase(Locale.ROOT) : defaultValue;
			case ENUM -> options.contains(raw) ? raw : defaultValue;
			case TEXT -> raw.length() > max ? raw.substring(0, max) : raw;
		};
	}
}
