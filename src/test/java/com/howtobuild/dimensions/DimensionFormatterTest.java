package com.howtobuild.dimensions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.howtobuild.geometry.Box;

class DimensionFormatterTest {
	private static DimensionFormatter.Style style(DimensionFormat format) {
		return new DimensionFormatter.Style(format, LabelFormat.LONG_BY_SHORT, "W {width} / H {height}", "", 1);
	}

	@Test
	void formats() {
		Box line = new Box(0, 0, 0, 5, 0, 0); // 6 × 1 × 1
		assertEquals("6 × 1", DimensionFormatter.format(line, -1, style(DimensionFormat.FULL), Map.of()));
		assertEquals("6", DimensionFormatter.format(line, -1, style(DimensionFormat.SIMPLIFIED), Map.of()));
		assertEquals("6 W", DimensionFormatter.format(line, -1, style(DimensionFormat.WIDTH_ONLY), Map.of()));
		assertEquals("1 H", DimensionFormatter.format(line, -1, style(DimensionFormat.HEIGHT_ONLY), Map.of()));
		assertEquals("WIDTH: 6", DimensionFormatter.format(line, -1, style(DimensionFormat.NAMED), Map.of()));
		assertEquals("W 6 / H 1", DimensionFormatter.format(line, -1, style(DimensionFormat.CUSTOM), Map.of()));
		Box wall = new Box(0, 0, 0, 5, 9, 0); // 6 wide, 10 high
		assertEquals("10 H", DimensionFormatter.format(wall, 2, style(DimensionFormat.HEIGHT_ONLY), Map.of()));
		assertEquals("10 × 6", DimensionFormatter.format(wall, 2, style(DimensionFormat.FULL), Map.of()));
		Box block = new Box(0, 0, 0, 3, 1, 2);
		assertEquals("4 × 3 × 2", DimensionFormatter.format(block, -1, style(DimensionFormat.FULL), Map.of()));
		DimensionFormatter.Style axisOrder = new DimensionFormatter.Style(DimensionFormat.FULL, LabelFormat.WIDTH_BY_HEIGHT, "", "", 1);
		assertEquals("4 × 3", DimensionFormatter.format(new Box(0, 0, 0, 3, 0, 2), 1, axisOrder, Map.of()));
		DimensionFormatter.Style units = new DimensionFormatter.Style(DimensionFormat.SIMPLIFIED, LabelFormat.LONG_BY_SHORT, "", " blocks", 1);
		assertEquals("6 blocks", DimensionFormatter.format(line, -1, units, Map.of()));
		assertEquals("2.5", DimensionFormatter.number(2.5, style(DimensionFormat.FULL)));
		assertEquals("Spiral r=5", DimensionFormatter.template("{tool} r={radius}", line, -1, style(DimensionFormat.CUSTOM),
				Map.of("tool", "Spiral", "radius", "5")));
	}
}
