package com.howtocircle.geometry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CircleGeneratorTest {
	private static int[] rowProfile(CircleShape shape) {
		int[] rows = new int[shape.height()];

		for (int v = 0; v < shape.height(); v++) {
			rows[v] = shape.rowCount(v);
		}

		return rows;
	}

	@Test
	void oneByOne() {
		CircleShape shape = CircleShape.circle(1, FillMode.FILLED);
		assertEquals(1, shape.blockCount());
		assertTrue(shape.contains(0, 0));
		assertTrue(shape.isCentreCell(0, 0));
		assertEquals(shape.cellsCopy(), CircleShape.circle(1, FillMode.OUTLINE).cellsCopy());
	}

	@Test
	void twoByTwo() {
		CircleShape shape = CircleShape.circle(2, FillMode.FILLED);
		assertEquals(4, shape.blockCount());

		for (int v = 0; v < 2; v++) {
			for (int u = 0; u < 2; u++) {
				assertTrue(shape.isCentreCell(u, v), "all four blocks form the 2x2 centre");
			}
		}
	}

	@Test
	void threeByThree() {
		// Every block centre of a 3x3 grid lies inside the circle of diameter 3 (corner centre: 1² + 1² = 2 ≤ 1.5² = 2.25).
		assertArrayEquals(new int[] {3, 3, 3}, rowProfile(CircleShape.circle(3, FillMode.FILLED)));
		assertEquals(8, CircleShape.circle(3, FillMode.OUTLINE).blockCount());
	}

	@Test
	void fiveByFive() {
		CircleShape shape = CircleShape.circle(5, FillMode.FILLED);
		assertArrayEquals(new int[] {3, 5, 5, 5, 3}, rowProfile(shape));
		assertEquals(21, shape.blockCount());
		assertEquals(
				".###.\n"
				+ "#####\n"
				+ "#####\n"
				+ "#####\n"
				+ ".###.\n", shape.toAscii());
	}

	@Test
	void sevenBySeven() {
		assertArrayEquals(new int[] {3, 5, 7, 7, 7, 5, 3}, rowProfile(CircleShape.circle(7, FillMode.FILLED)));
		assertEquals(
				"..###..\n"
				+ ".#...#.\n"
				+ "#.....#\n"
				+ "#.....#\n"
				+ "#.....#\n"
				+ ".#...#.\n"
				+ "..###..\n", CircleShape.circle(7, FillMode.OUTLINE).toAscii());
	}

	@Test
	void tenByTen() {
		CircleShape shape = CircleShape.circle(10, FillMode.FILLED);
		assertArrayEquals(new int[] {4, 8, 8, 10, 10, 10, 10, 8, 8, 4}, rowProfile(shape));
		assertEquals(80, shape.blockCount());
	}

	@Test
	void fifteenByFifteen() {
		CircleShape shape = CircleShape.circle(15, FillMode.FILLED);
		assertArrayEquals(new int[] {5, 9, 11, 13, 13, 15, 15, 15, 15, 15, 13, 13, 11, 9, 5}, rowProfile(shape));
		assertEquals(177, shape.blockCount());
	}

	@Test
	void twentyByTwelveOval() {
		CircleShape shape = OvalShape.of(20, 12, FillMode.FILLED);
		assertArrayEquals(new int[] {8, 14, 16, 18, 20, 20, 20, 20, 18, 16, 14, 8}, rowProfile(shape));
		assertEquals(192, shape.blockCount());
	}

	@Test
	void twentyOneByThirteenOval() {
		CircleShape shape = OvalShape.of(21, 13, FillMode.FILLED);
		assertArrayEquals(new int[] {9, 13, 17, 19, 19, 21, 21, 21, 19, 19, 17, 13, 9}, rowProfile(shape));
		assertEquals(217, shape.blockCount());
	}

	@ParameterizedTest(name = "{0}x{1}")
	@CsvSource({
			"1,1", "2,2", "3,3", "5,5", "7,7", "10,10", "15,15", "20,12", "21,13", "12,20", "13,21",
			"1,9", "9,1", "2,20", "20,2", "4,31", "64,64", "99,40", "100,100", "101,101", "128,17"
	})
	void invariants(int width, int height) {
		for (FillMode mode : FillMode.values()) {
			CircleShape shape = OvalShape.of(width, height, mode);
			assertSymmetric(shape);
			assertBoundingBox(shape);
			assertEquals(shape, OvalShape.of(width, height, mode), "deterministic");
		}

		CircleShape filled = OvalShape.of(width, height, FillMode.FILLED);
		CircleShape outline = OvalShape.of(width, height, FillMode.OUTLINE);
		assertConvexRows(filled);
		assertConvexColumns(filled);
		assertOutlineIsBoundary(filled, outline);
		assertEightConnected(outline);
		assertEquals(OvalShape.of(height, width, FillMode.FILLED).blockCount(), filled.blockCount(), "transposing preserves block count");
	}

	@Test
	void centreCellsMatchParity() {
		assertEquals(1, countCentre(OvalShape.of(15, 15, FillMode.FILLED)));
		assertEquals(4, countCentre(OvalShape.of(16, 16, FillMode.FILLED)));
		assertEquals(1, countCentre(OvalShape.of(21, 13, FillMode.FILLED)));
		assertEquals(4, countCentre(OvalShape.of(20, 12, FillMode.FILLED)));
		assertEquals(2, countCentre(OvalShape.of(21, 12, FillMode.FILLED)));
	}

	@Test
	void largeShapesAreFast() {
		long start = System.nanoTime();
		CircleShape shape = CircleShape.circle(1024, FillMode.FILLED);
		long millis = (System.nanoTime() - start) / 1_000_000;
		assertTrue(shape.blockCount() > 800_000);
		assertTrue(millis < 2_000, "1024x1024 took " + millis + " ms");
	}

	@Test
	void rejectsInvalidDimensions() {
		assertThrows(IllegalArgumentException.class, () -> OvalShape.of(0, 5, FillMode.FILLED));
		assertThrows(IllegalArgumentException.class, () -> OvalShape.of(5, ResolvedDimensions.MAX_SIZE + 1, FillMode.FILLED));
	}

	private static int countCentre(CircleShape shape) {
		int count = 0;

		for (int v = 0; v < shape.height(); v++) {
			for (int u = 0; u < shape.width(); u++) {
				if (shape.isCentreCell(u, v)) count++;
			}
		}

		return count;
	}

	private static void assertSymmetric(CircleShape shape) {
		for (int v = 0; v < shape.height(); v++) {
			for (int u = 0; u < shape.width(); u++) {
				boolean c = shape.contains(u, v);
				assertEquals(c, shape.contains(shape.width() - 1 - u, v), "mirror u at " + u + "," + v);
				assertEquals(c, shape.contains(u, shape.height() - 1 - v), "mirror v at " + u + "," + v);
			}
		}
	}

	private static void assertBoundingBox(CircleShape shape) {
		boolean firstRow = false;
		boolean lastRow = false;
		boolean firstColumn = false;
		boolean lastColumn = false;

		for (int u = 0; u < shape.width(); u++) {
			firstRow |= shape.contains(u, 0);
			lastRow |= shape.contains(u, shape.height() - 1);
		}

		for (int v = 0; v < shape.height(); v++) {
			firstColumn |= shape.contains(0, v);
			lastColumn |= shape.contains(shape.width() - 1, v);
		}

		assertTrue(firstRow && lastRow && firstColumn && lastColumn, "shape touches all four sides of " + shape);
		int midV = (shape.height() - 1) / 2;
		int midU = (shape.width() - 1) / 2;
		assertTrue(shape.contains(0, midV) && shape.contains(shape.width() - 1, midV), "full width on the centre row");
		assertTrue(shape.contains(midU, 0) && shape.contains(midU, shape.height() - 1), "full height on the centre column");
	}

	private static void assertConvexRows(CircleShape shape) {
		int previous = 0;
		int middle = (shape.height() - 1) / 2;

		for (int v = 0; v < shape.height(); v++) {
			int runs = 0;

			for (int u = 0; u < shape.width(); u++) {
				if (shape.contains(u, v) && !shape.contains(u - 1, v)) runs++;
			}

			assertEquals(1, runs, "row " + v + " is one straight run");
			int count = shape.rowCount(v);

			if (v <= middle) {
				assertTrue(count >= previous, "rows widen towards the centre");
			}

			previous = count;
		}
	}

	private static void assertConvexColumns(CircleShape shape) {
		for (int u = 0; u < shape.width(); u++) {
			int runs = 0;

			for (int v = 0; v < shape.height(); v++) {
				if (shape.contains(u, v) && !shape.contains(u, v - 1)) runs++;
			}

			assertEquals(1, runs, "column " + u + " is one straight run");
		}
	}

	private static void assertOutlineIsBoundary(CircleShape filled, CircleShape outline) {
		for (int v = 0; v < filled.height(); v++) {
			for (int u = 0; u < filled.width(); u++) {
				boolean inFilled = filled.contains(u, v);
				boolean boundary = inFilled && !(filled.contains(u - 1, v) && filled.contains(u + 1, v)
						&& filled.contains(u, v - 1) && filled.contains(u, v + 1));
				assertEquals(boundary, outline.contains(u, v), "outline cell " + u + "," + v);
			}
		}
	}

	private static void assertEightConnected(CircleShape shape) {
		BitSet seen = new BitSet();
		Deque<int[]> queue = new ArrayDeque<>();
		int reached = 0;

		outer:
		for (int v = 0; v < shape.height(); v++) {
			for (int u = 0; u < shape.width(); u++) {
				if (shape.contains(u, v)) {
					queue.add(new int[] {u, v});
					seen.set(v * shape.width() + u);
					break outer;
				}
			}
		}

		while (!queue.isEmpty()) {
			int[] cell = queue.poll();
			reached++;

			for (int dv = -1; dv <= 1; dv++) {
				for (int du = -1; du <= 1; du++) {
					int u = cell[0] + du;
					int v = cell[1] + dv;

					if (shape.contains(u, v) && !seen.get(v * shape.width() + u)) {
						seen.set(v * shape.width() + u);
						queue.add(new int[] {u, v});
					}
				}
			}
		}

		assertEquals(shape.blockCount(), reached, "outline has no gaps: " + shape);
		assertFalse(shape.blockCount() == 0);
	}
}
