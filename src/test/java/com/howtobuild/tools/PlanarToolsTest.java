package com.howtobuild.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.howtobuild.TestShapes;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.geometry.CircleShape;
import com.howtobuild.geometry.FillMode;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.OvalShape;
import com.howtobuild.geometry.Placement;

class PlanarToolsTest {
	/** The circle tool must reproduce How to Circle exactly (same cells, centred the same way, on the floor). */
	@ParameterizedTest(name = "circle {0}")
	@ValueSource(ints = {1, 3, 5, 8, 15, 16, 100})
	void circleToolMatchesLegacyGenerator(int size) {
		for (FillMode mode : FillMode.values()) {
			CircleShape legacy = CircleShape.circle(size, mode);
			GeometryResult result = TestShapes.generate("circle", "size", size, "fill", mode.name());
			assertEquals(legacy.blockCount(), result.blockCount(), "block count " + mode);
			int offset = com.howtobuild.geometry.Centring.minOffset(size, true);

			for (Placement p : result.placements()) {
				assertEquals(0, p.y());
				assertTrue(legacy.contains(p.x() - offset, p.z() - offset), "cell " + p);
			}

			assertEquals(size, result.width());
			assertEquals(size, result.length());
			assertEquals(1, result.height());
			assertEquals(1, result.planeNormalAxis(), "floor shapes are flat in Y");
		}
	}

	@ParameterizedTest(name = "oval {0}x{1}")
	@CsvSource({"7,3", "15,7", "20,11", "21,13", "20,12"})
	void ovalToolMatchesLegacyGenerator(int w, int l) {
		GeometryResult result = TestShapes.generate("oval", "width", w, "length", l, "fill", "FILLED");
		assertEquals(OvalShape.of(w, l, FillMode.FILLED).blockCount(), result.blockCount());
		assertEquals(w, result.width());
		assertEquals(l, result.length());
	}

	@ParameterizedTest(name = "centre {0}")
	@CsvSource({"15,1", "16,4", "1,1", "2,4"})
	void centreCellsFollowParity(int size, int expected) {
		GeometryResult result = TestShapes.generate("circle", "size", size);
		assertEquals(expected, result.centreCells().size());
		assertTrue(result.centreCells().stream().anyMatch(c -> c[0] == 0 && c[1] == 0 && c[2] == 0), "the anchor is a centre block");
	}

	@Test
	void mixedParityOvalHasOneByTwoCentre() {
		assertEquals(2, TestShapes.generate("oval", "width", 21, "length", 12).centreCells().size());
	}

	@Test
	void ringHasRequestedThickness() {
		GeometryResult outline = TestShapes.generate("circle", "size", 21, "fill", "OUTLINE");
		assertEquals(outline.blockCount(), TestShapes.generate("circle", "size", 21, "fill", "RING", "thickness", 1).blockCount(),
				"a 1-thick ring is the classic outline");
		com.howtobuild.geometry.Mask2D disc = com.howtobuild.geometry.Mask2D.ellipse(21, 21);
		GeometryResult ring = TestShapes.generate("circle", "size", 21, "fill", "RING", "thickness", 3);
		assertEquals(disc.count() - disc.eroded(3, false).count(), ring.blockCount(), "three outline layers");
		assertTrue(ring.blockCount() > 2 * outline.blockCount());
	}

	@Test
	void incompatibleForcedCentreIsReportedInsteadOfChangingTheSize() {
		GeometryResult result = TestShapes.generate("circle", "size", 16, "centre_size", "ONE_BY_ONE");
		assertTrue(result.isEmpty(), "no geometry is generated for a 1×1 centre on an even size");
		assertTrue(result.warnings().stream().anyMatch(w -> w.contains("1×1 centre needs odd")), result.warnings().toString());

		GeometryResult ok = TestShapes.generate("circle", "size", 16, "centre_size", "TWO_BY_TWO");
		assertEquals(16, ok.width());
		assertEquals(4, ok.centreCells().size());
	}

	@Test
	void wallPlaneStandsUpright() {
		GeometryResult result = TestShapes.generate("circle", "size", 9, "plane", "WALL");
		assertEquals(9, result.height());
		assertEquals(1, result.length());
		assertEquals(2, result.planeNormalAxis());
	}

	@Test
	void squaresAndRectangles() {
		assertEquals(25, TestShapes.generate("square", "size", 5, "fill", "FILLED").blockCount());
		assertEquals(16, TestShapes.generate("square", "size", 5, "fill", "OUTLINE").blockCount());
		GeometryResult rect = TestShapes.generate("rectangle", "width", 8, "length", 3, "fill", "FILLED");
		assertEquals(24, rect.blockCount());
		assertEquals(8, rect.width());
		assertEquals(3, rect.length());
		GeometryResult thick = TestShapes.generate("rectangle", "width", 10, "length", 8, "fill", "RING", "thickness", 2);
		assertEquals(80 - 6 * 4, thick.blockCount());
	}

	@Test
	void detailsAreRealBlocksWithRoles() {
		GeometryResult plain = TestShapes.generate("circle", "size", 15, "fill", "FILLED");
		GeometryResult detailed = TestShapes.generate("circle", TestShapes.details(DetailFeature.EDGE_TRIM, DetailFeature.OUTER_RING),
				"size", 15, "fill", "FILLED");
		assertTrue(detailed.count(MaterialRole.TRIM) > 0);
		assertTrue(detailed.blockCount() > plain.blockCount(), "the outer ring adds blocks outside");
		assertEquals(17, detailed.width(), "outer ring extends one block on each side");
		GeometryResult corners = TestShapes.generate("square", TestShapes.details(DetailFeature.CORNER_ACCENTS), "size", 6);
		assertEquals(4, corners.count(MaterialRole.ACCENT));
	}
}
