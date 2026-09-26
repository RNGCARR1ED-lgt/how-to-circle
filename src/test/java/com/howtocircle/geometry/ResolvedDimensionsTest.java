package com.howtocircle.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ResolvedDimensionsTest {
	@ParameterizedTest(name = "{0}x{1} -> {2}x{3} centre")
	@CsvSource({"15,15,1,1", "16,16,2,2", "21,13,1,1", "20,12,2,2", "21,12,1,2", "20,13,2,1", "1,1,1,1", "2,2,2,2"})
	void autoCentreFollowsParity(int width, int height, int centreWidth, int centreHeight) {
		ResolvedDimensions resolved = ResolvedDimensions.resolve(ShapeType.OVAL, width, height, CentreSize.AUTO);
		assertEquals(width, resolved.width());
		assertEquals(height, resolved.height());
		assertEquals(centreWidth, resolved.centreWidth());
		assertEquals(centreHeight, resolved.centreHeight());
		assertFalse(resolved.adjusted());
		assertEquals(centreWidth != centreHeight, resolved.mixedParity());
	}

	@Test
	void circleLocksHeightToWidth() {
		ResolvedDimensions resolved = ResolvedDimensions.resolve(ShapeType.CIRCLE, 15, 99, CentreSize.AUTO);
		assertEquals(15, resolved.width());
		assertEquals(15, resolved.height());
	}

	@Test
	void forcedOneByOneAdjustsEvenDimensionsVisibly() {
		ResolvedDimensions resolved = ResolvedDimensions.resolve(ShapeType.OVAL, 16, 13, CentreSize.ONE_BY_ONE);
		assertEquals(17, resolved.width());
		assertEquals(13, resolved.height());
		assertEquals("1×1", resolved.centreLabel());
		assertTrue(resolved.adjusted());
		assertEquals(1, resolved.notes().size());
		assertTrue(resolved.notes().getFirst().contains("16 → 17"));
	}

	@Test
	void forcedTwoByTwoAdjustsOddDimensionsVisibly() {
		ResolvedDimensions resolved = ResolvedDimensions.resolve(ShapeType.CIRCLE, 15, 15, CentreSize.TWO_BY_TWO);
		assertEquals(16, resolved.width());
		assertEquals(16, resolved.height());
		assertEquals("2×2", resolved.centreLabel());
		assertEquals(1, resolved.notes().size(), "circle reports the change once");
	}

	@Test
	void clampsOutOfRangeValues() {
		ResolvedDimensions resolved = ResolvedDimensions.resolve(ShapeType.OVAL, 0, 5000, CentreSize.AUTO);
		assertEquals(ResolvedDimensions.MIN_SIZE, resolved.width());
		assertEquals(ResolvedDimensions.MAX_SIZE, resolved.height());
		assertEquals(2, resolved.notes().size());
	}

	@Test
	void forcingParityAtMaximumShrinksInstead() {
		ResolvedDimensions resolved = ResolvedDimensions.resolve(ShapeType.CIRCLE, ResolvedDimensions.MAX_SIZE, 0, CentreSize.ONE_BY_ONE);
		assertEquals(ResolvedDimensions.MAX_SIZE - 1, resolved.width());
	}
}
