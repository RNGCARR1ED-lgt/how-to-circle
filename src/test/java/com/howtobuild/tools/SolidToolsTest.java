package com.howtobuild.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.howtobuild.TestShapes;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.geometry.CircleShape;
import com.howtobuild.geometry.FillMode;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.Voxels;

class SolidToolsTest {
	@ParameterizedTest(name = "cylinder {0}")
	@ValueSource(ints = {5, 10, 20})
	void cylinders(int d) {
		int height = 7;
		GeometryResult filled = TestShapes.generate("cylinder", "width", d, "length", d, "height", height, "style", "FILLED");
		int section = CircleShape.circle(d, FillMode.FILLED).blockCount();
		assertEquals(section * height, filled.blockCount(), "filled = cross-section × height");
		assertEquals(d, filled.width());
		assertEquals(d, filled.length());
		assertEquals(height, filled.height());

		GeometryResult hollow = TestShapes.generate("cylinder", "width", d, "length", d, "height", height, "style", "HOLLOW", "thickness", 1);
		assertEquals(CircleShape.circle(d, FillMode.OUTLINE).blockCount() * height, hollow.blockCount(), "1-thick wall = outline per layer");
		GeometryResult capped = TestShapes.generate("cylinder", "width", d, "length", d, "height", height, "style", "HOLLOW", "caps", "BOTH");
		assertTrue(capped.count(MaterialRole.CAP) > 0 || d <= 2);
		assertSymmetric(filled, true, false, true);
	}

	@Test
	void horizontalCylinderLiesAlongTheAxis() {
		GeometryResult r = TestShapes.generate("cylinder", "width", 7, "length", 7, "height", 12, "orientation", "ALONG_X");
		assertEquals(12, r.width());
		assertEquals(7, r.height());
		assertEquals(7, r.length());
		assertEquals(0, r.bounds().minY(), "rests on the anchor level");
	}

	@Test
	void hollowCylinderWithDetailedTrim() {
		GeometryResult r = TestShapes.generate("cylinder", TestShapes.details(DetailFeature.EDGE_TRIM, DetailFeature.TOP_RIM,
				DetailFeature.HORIZONTAL_BANDS, DetailFeature.VERTICAL_BANDS), "width", 11, "length", 11, "height", 12, "style", "HOLLOW");
		assertTrue(r.count(MaterialRole.TRIM) > 20);
		assertEquals(13, r.width(), "top rim sticks out one block");
	}

	@ParameterizedTest(name = "sphere {0}")
	@ValueSource(ints = {5, 10, 20})
	void spheres(int d) {
		GeometryResult filled = TestShapes.generate("sphere", "diameter_x", d, "diameter_y", d, "diameter_z", d, "style", "FILLED");
		assertEquals(d, filled.width());
		assertEquals(d, filled.height());
		assertEquals(d, filled.length());
		assertSymmetric(filled, true, true, true);
		GeometryResult hollow = TestShapes.generate("sphere", "diameter_x", d, "diameter_y", d, "diameter_z", d, "style", "HOLLOW");
		assertTrue(hollow.blockCount() < filled.blockCount() || d <= 2);
		// Shell: no hollow block is fully enclosed by six other hollow blocks.
		Set<Long> cells = TestShapes.positions(hollow);

		for (Placement p : hollow.placements()) {
			boolean enclosed = cells.contains(Voxels.pack(p.x() + 1, p.y(), p.z())) && cells.contains(Voxels.pack(p.x() - 1, p.y(), p.z()))
					&& cells.contains(Voxels.pack(p.x(), p.y() + 1, p.z())) && cells.contains(Voxels.pack(p.x(), p.y() - 1, p.z()))
					&& cells.contains(Voxels.pack(p.x(), p.y(), p.z() + 1)) && cells.contains(Voxels.pack(p.x(), p.y(), p.z() - 1));
			assertFalse(enclosed && d > 4, "shell blocks touch the inside or outside at " + p);
		}
	}

	@Test
	void ellipsoidUsesThreeDiameters() {
		GeometryResult r = TestShapes.generate("sphere", "diameter_x", 21, "diameter_y", 9, "diameter_z", 13);
		assertEquals(21, r.width());
		assertEquals(9, r.height());
		assertEquals(13, r.length());
	}

	@Test
	void hollowDetailedSphere() {
		GeometryResult r = TestShapes.generate("sphere", TestShapes.details(DetailFeature.LATITUDE_RINGS, DetailFeature.LONGITUDE_RINGS,
				DetailFeature.EQUATOR_BAND, DetailFeature.POLE_CAPS), "diameter_x", 21, "diameter_y", 21, "diameter_z", 21);
		assertTrue(r.count(MaterialRole.TRIM) > 0);
		assertTrue(r.count(MaterialRole.ACCENT) > 0);
		assertTrue(r.count(MaterialRole.CAP) > 0);
	}

	@ParameterizedTest(name = "dome {0}")
	@ValueSource(ints = {5, 10, 20})
	void domes(int d) {
		int h = d / 2;
		GeometryResult r = TestShapes.generate("dome", "width", d, "length", d, "height", Math.max(1, h), "style", "FILLED");
		assertEquals(d, r.width());
		assertEquals(d, r.length());
		assertEquals(Math.max(1, h), r.height());
		assertEquals(0, r.bounds().minY());
		assertSymmetric(r, true, false, true);
		// Filled domes have the full footprint on the base layer.
		long base = r.placements().stream().filter(p -> p.y() == 0).count();
		assertTrue(base >= CircleShape.circle(d, FillMode.FILLED).blockCount() - 4 * d / 3, "base " + base);
	}

	@Test
	void domeWithRibsAndCrown() {
		GeometryResult r = TestShapes.generate("dome", TestShapes.details(DetailFeature.RADIAL_RIBS, DetailFeature.CROWN),
				"width", 21, "length", 21, "height", 11);
		assertTrue(r.count(MaterialRole.TRIM) > 10, "ribs");
		assertTrue(r.count(MaterialRole.CAP) > 0, "crown");
		assertNotNull(r.at(0, 11, 0), "finial on top");
		assertEquals(MaterialRole.ACCENT, r.at(0, 11, 0).role());
	}

	/** Checks the result is unchanged by mirroring about the anchor's centre on the given axes (odd sizes). */
	static void assertSymmetric(GeometryResult r, boolean x, boolean y, boolean z) {
		Set<Long> cells = TestShapes.positions(r);
		var b = r.bounds();

		for (Placement p : r.placements()) {
			int mx = x ? b.minX() + b.maxX() - p.x() : p.x();
			int my = y ? b.minY() + b.maxY() - p.y() : p.y();
			int mz = z ? b.minZ() + b.maxZ() - p.z() : p.z();
			assertTrue(cells.contains(Voxels.pack(mx, my, mz)), "symmetric partner of " + p);
		}
	}
}
