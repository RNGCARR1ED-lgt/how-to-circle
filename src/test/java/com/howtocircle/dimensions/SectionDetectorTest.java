package com.howtocircle.dimensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.howtocircle.geometry.CircleShape;
import com.howtocircle.geometry.FillMode;
import com.howtocircle.geometry.OvalShape;

class SectionDetectorTest {
	private static List<String> labels(List<ConnectedSection> sections, LabelFormat format) {
		return sections.stream().map(s -> DimensionLabel.text(s, format)).toList();
	}

	@Test
	void horizontalLineIsOneSection() {
		List<ConnectedSection> sections = SectionDetector.detect(OvalShape.of(7, 1, FillMode.FILLED));
		assertEquals(List.of(new ConnectedSection(0, 0, 7, 1)), sections);
		assertEquals(ConnectedSection.Kind.LINE_ALONG_WIDTH, sections.getFirst().kind());
		assertEquals("7 by 1", DimensionLabel.text(sections.getFirst(), LabelFormat.LONG_BY_SHORT));
	}

	@Test
	void verticalLineIsOneSection() {
		List<ConnectedSection> sections = SectionDetector.detect(OvalShape.of(1, 5, FillMode.FILLED));
		assertEquals(List.of(new ConnectedSection(0, 0, 1, 5)), sections);
		assertEquals(ConnectedSection.Kind.LINE_ALONG_HEIGHT, sections.getFirst().kind());
		assertEquals("5 by 1", DimensionLabel.text(sections.getFirst(), LabelFormat.LONG_BY_SHORT));
		assertEquals("1 by 5", DimensionLabel.text(sections.getFirst(), LabelFormat.WIDTH_BY_HEIGHT));
	}

	@Test
	void filledSevenCircleStacksEqualRows() {
		List<ConnectedSection> sections = SectionDetector.detect(CircleShape.circle(7, FillMode.FILLED));
		assertEquals(List.of("3 by 1", "5 by 1", "7 by 3", "5 by 1", "3 by 1"), labels(sections, LabelFormat.WIDTH_BY_HEIGHT));
		assertEquals(new ConnectedSection(0, 2, 7, 3), sections.get(2));
	}

	@Test
	void outlineSevenCircleUsesLinesAndSingles() {
		List<ConnectedSection> sections = SectionDetector.detect(CircleShape.circle(7, FillMode.OUTLINE));
		assertEquals(List.of(
				new ConnectedSection(2, 0, 3, 1),
				new ConnectedSection(1, 1, 1, 1),
				new ConnectedSection(5, 1, 1, 1),
				new ConnectedSection(0, 2, 1, 3),
				new ConnectedSection(6, 2, 1, 3),
				new ConnectedSection(1, 5, 1, 1),
				new ConnectedSection(5, 5, 1, 1),
				new ConnectedSection(2, 6, 3, 1)), sections);
	}

	@Test
	void diagonalBlocksAreNeverGrouped() {
		// The 7x7 outline's step blocks at (1,1) and (2,0)/(0,2) only touch diagonally or along a different line.
		for (ConnectedSection section : SectionDetector.detect(CircleShape.circle(7, FillMode.OUTLINE))) {
			if (section.contains(1, 1)) {
				assertEquals(ConnectedSection.Kind.SINGLE, section.kind());
			}
		}
	}

	@Test
	void thinOvalBecomesOneRectangle() {
		assertEquals(List.of(new ConnectedSection(0, 0, 20, 2)), SectionDetector.detect(OvalShape.of(20, 2, FillMode.OUTLINE)));
	}

	@Test
	void tallOvalPrefersColumns() {
		CircleShape tall = OvalShape.of(13, 21, FillMode.FILLED);
		List<ConnectedSection> sections = SectionDetector.detect(tall);
		List<ConnectedSection> wide = SectionDetector.detect(OvalShape.of(21, 13, FillMode.FILLED));
		assertEquals(wide.size(), sections.size(), "transposed shape gives the same number of sections");
		assertTrue(sections.stream().anyMatch(s -> s.height() == 21), "the full-height centre column is one section");
	}

	@ParameterizedTest(name = "{0}x{1}")
	@CsvSource({"1,1", "2,2", "3,3", "5,5", "7,7", "10,10", "15,15", "20,12", "21,13", "64,64", "100,100", "150,90"})
	void sectionsExactlyPartitionTheShape(int width, int height) {
		for (FillMode mode : FillMode.values()) {
			CircleShape shape = OvalShape.of(width, height, mode);
			List<ConnectedSection> sections = SectionDetector.detect(shape);
			int[] owner = new int[width * height];
			int covered = 0;

			for (int i = 0; i < sections.size(); i++) {
				ConnectedSection s = sections.get(i);

				for (int v = s.v(); v < s.vEnd(); v++) {
					for (int u = s.u(); u < s.uEnd(); u++) {
						assertTrue(shape.contains(u, v), "section " + s + " only contains shape blocks");
						assertEquals(0, owner[v * width + u], "no block is in two sections");
						owner[v * width + u] = i + 1;
						covered++;
					}
				}
			}

			assertEquals(shape.blockCount(), covered, "every block belongs to a section");
			assertEquals(sections, SectionDetector.detect(shape), "deterministic");
		}
	}

	@Test
	void sectionCountsStayReadableForLargeShapes() {
		List<ConnectedSection> filled = SectionDetector.detect(CircleShape.circle(100, FillMode.FILLED));
		List<ConnectedSection> outline = SectionDetector.detect(CircleShape.circle(100, FillMode.OUTLINE));
		// 7,860 blocks collapse into well under a hundred rectangles.
		assertTrue(filled.size() < 100, "filled sections: " + filled.size());
		assertTrue(outline.size() < 200, "outline sections: " + outline.size());
	}

	@Test
	void singleBlock() {
		List<ConnectedSection> sections = SectionDetector.detect(CircleShape.circle(1, FillMode.FILLED));
		assertEquals("1 by 1", DimensionLabel.text(sections.getFirst(), LabelFormat.LONG_BY_SHORT));
	}
}
