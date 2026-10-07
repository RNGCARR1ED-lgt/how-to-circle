package com.howtobuild.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable parameter values for one tool. Values are stored as strings (easy to persist and compare) and read through
 * typed accessors. Equal settings produce equal geometry, so instances are used as cache keys.
 */
public final class ToolSettings {
	private final Map<String, String> values;
	private final Map<String, ToolParameter> definitions;

	private ToolSettings(Map<String, String> values, Map<String, ToolParameter> definitions) {
		this.values = values;
		this.definitions = definitions;
	}

	/** Builds settings for a parameter list, filling in defaults and sanitising every value. */
	public static ToolSettings of(List<ToolParameter> parameters, Map<String, String> raw) {
		Map<String, String> values = new LinkedHashMap<>();
		Map<String, ToolParameter> definitions = new LinkedHashMap<>();

		for (ToolParameter parameter : parameters) {
			definitions.put(parameter.id(), parameter);
			values.put(parameter.id(), parameter.sanitize(raw == null ? null : raw.get(parameter.id())));
		}

		return new ToolSettings(Collections.unmodifiableMap(values), Collections.unmodifiableMap(definitions));
	}

	public static ToolSettings defaults(List<ToolParameter> parameters) {
		return of(parameters, Map.of());
	}

	public ToolSettings with(String id, Object value) {
		Map<String, String> copy = new LinkedHashMap<>(values);
		copy.put(id, String.valueOf(value instanceof Enum<?> e ? e.name() : value));
		return of(List.copyOf(definitions.values()), copy);
	}

	public int getInt(String id) {
		return Integer.parseInt(require(id));
	}

	public boolean getBool(String id) {
		return Boolean.parseBoolean(require(id));
	}

	public <E extends Enum<E>> E getEnum(String id, Class<E> type) {
		return Enum.valueOf(type, require(id));
	}

	public String raw(String id) {
		return values.get(id);
	}

	public boolean has(String id) {
		return values.containsKey(id);
	}

	public Map<String, String> asMap() {
		return values;
	}

	public ToolParameter definition(String id) {
		return definitions.get(id);
	}

	private String require(String id) {
		String value = values.get(id);

		if (value == null) throw new IllegalArgumentException("Unknown parameter " + id);

		return value;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ToolSettings other && values.equals(other.values);
	}

	@Override
	public int hashCode() {
		return values.hashCode();
	}

	@Override
	public String toString() {
		return values.toString();
	}
}
