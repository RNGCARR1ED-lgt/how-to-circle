package com.howtobuild.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.howtobuild.TestShapes;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.GeometryValidator;
import com.howtobuild.geometry.Guide;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.Voxels;
import com.howtobuild.palette.PaletteEntry;
import com.howtobuild.palette.WeightedPalette;
import com.howtobuild.tools.impl.TerrainTool;

class TerrainToolTest {
	/** Top block Y of every column. */
	private static Map<Long, Integer> tops(GeometryResult r) {
		Map<Long, Integer> tops = new HashMap<>();
		r.placements().forEach(p -> tops.merge(Voxels.pack(p.x(), 0, p.z()), p.y(), Math::max));
		return tops;
	}

	private static void assertValid(GeometryResult r, String what) {
		assertFalse(r.isEmpty(), what + ": " + r.warnings());
		assertFalse(r.warnings().stream().anyMatch(GeometryValidator::isBlocking), what + ": " + r.warnings());
	}

	@Test
	void everyTemplateAndVariationGeneratesValidTerrain() {
		for (TerrainTool.Template template : TerrainTool.Template.values()) {
			for (TerrainTool.Variation variation : TerrainTool.Variation.values()) {
				GeometryResult r = TestShapes.generate("terrain", "template", template.name(), "variation", variation.name(), "width", 40, "max_height", 30);
				assertValid(r, template + " " + variation);
				assertTrue(r.values().get("max_y") <= 30 && r.values().get("min_y") >= 0, template + ": heights within the range");
			}
		}
	}

	@Test
	void sameSeedSameTerrain() {
		GeometryResult a = TestShapes.generate("terrain", "seed", 42);
		GeometryResult b = TestShapes.generate("terrain", "seed", 42);
		GeometryResult c = TestShapes.generate("terrain", "seed", 43);
		assertEquals(a.placements(), b.placements());
		assertTrue(!tops(a).equals(tops(c)), "another seed gives another terrain");
	}

	@Test
	void columnsAreSolidAndSlopesLimited() {
		for (int slope : new int[] {1, 2, 4}) {
			GeometryResult r = TestShapes.generate("terrain", "template", "JAGGED_MOUNTAIN", "slope", slope, "width", 48, "boundary_blend", 0);
			Map<Long, Integer> tops = tops(r);

			for (Map.Entry<Long, Integer> e : tops.entrySet()) {
				int x = Voxels.x(e.getKey());
				int z = Voxels.z(e.getKey());

				for (int y = 0; y <= e.getValue(); y++) {
					assertTrue(r.at(x, y, z) != null, "no holes in column " + x + ", " + z);
				}

				for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
					Integer n = tops.get(Voxels.pack(x + d[0], 0, z + d[1]));

					if (n != null) assertTrue(Math.abs(n - e.getValue()) <= slope, "no spikes: slope " + slope + " at " + x + ", " + z);
				}
			}
		}
	}

	@Test
	void everyBlockIsInsideTheRegion() {
		for (String shape : List.of("CIRCLE", "OVAL", "SQUARE", "RECTANGLE")) {
			GeometryResult r = TestShapes.generate("terrain", "region_shape", shape, "width", 31, "length", 20);
			assertValid(r, shape);
			r.placements().forEach(p -> assertTrue(r.region().contains(Voxels.pack(p.x(), 0, p.z()))));
			int[] w = {Integer.MAX_VALUE, Integer.MIN_VALUE};
			r.placements().forEach(p -> {
				w[0] = Math.min(w[0], p.x());
				w[1] = Math.max(w[1], p.x());
			});
			assertEquals(31, w[1] - w[0] + 1, shape + ": exact width");
		}
	}

	@Test
	void protectedAreaIsNeverTouched() {
		for (String shape : List.of("CIRCLE", "SQUARE", "RECTANGLE")) {
			for (int offset : new int[] {0, 9, -13}) {
				GeometryResult r = TestShapes.generate("terrain", "width", 64, "protect", true, "protected_shape", shape, "protected_width", 20,
						"protected_length", 12, "protected_offset_x", offset, "protected_offset_z", -offset / 2, "blend", "VERY_SMOOTH", "blend_radius", 8);
				assertValid(r, shape + " " + offset);
				assertTrue(r.protectedColumns().size() > 100);
				r.placements().forEach(p -> assertFalse(r.protectedColumns().contains(Voxels.pack(p.x(), 0, p.z())),
						"block in the protected area at " + p));
				assertTrue(r.guides().stream().anyMatch(g -> g[3] == Guide.PROTECTED), "the protected area is outlined");
				assertEquals(r.values().get("area"), r.values().get("protected_columns") + r.values().get("terrain_columns"));
			}
		}
	}

	@Test
	void terrainBlendsIntoTheGroundAroundTheProtectedArea() {
		HeightMap ground = HeightMap.flat(-40, -40, 81, 81, 6);
		GenerationContext ctx = GenerationContext.DEFAULT.withPalettes(GenerationContext.DEFAULT.palettes().withHeights(ground));

		for (String blend : List.of("SMOOTH", "NATURAL", "VERY_SMOOTH")) {
			GeometryResult r = TestShapes.generate("terrain", ctx, "width", 64, "template", "MOUNTAIN", "min_height", 0, "max_height", 40, "protect", true,
					"protected_width", 16, "blend", blend, "blend_radius", 6);
			Map<Long, Integer> tops = tops(r);

			for (long column : r.protectedColumns()) {
				int x = Voxels.x(column);
				int z = Voxels.z(column);

				for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					Integer h = tops.get(Voxels.pack(x + d[0], 0, z + d[1]));

					if (h != null) assertTrue(Math.abs(h - 6) <= 2, blend + ": terrain next to the protected area meets the ground (Y " + h + ")");
				}
			}
		}
	}

	@Test
	void naturalBoundaryMeetsTheSurroundingGround() {
		HeightMap ground = HeightMap.flat(-40, -40, 81, 81, 3);
		GenerationContext ctx = GenerationContext.DEFAULT.withPalettes(GenerationContext.DEFAULT.palettes().withHeights(ground));
		GeometryResult r = TestShapes.generate("terrain", ctx, "width", 60, "template", "MOUNTAIN", "max_height", 40, "boundary", "NATURAL", "boundary_blend", 8);
		Map<Long, Integer> tops = tops(r);

		for (int[] g : r.guides()) {
			if (g[3] != Guide.BOUNDARY) continue;

			Integer h = tops.get(Voxels.pack(g[0], 0, g[2]));

			if (h != null) assertTrue(Math.abs(h - 3) <= 2, "boundary column at " + g[0] + ", " + g[2] + " is Y " + h);
		}

		GeometryResult cliff = TestShapes.generate("terrain", ctx, "width", 60, "template", "PLATEAU", "min_height", 20, "max_height", 30, "boundary", "CLIFF");
		assertTrue(cliff.guides().stream().filter(g -> g[3] == Guide.BOUNDARY).mapToInt(g -> tops(cliff).getOrDefault(Voxels.pack(g[0], 0, g[2]), 0)).max()
				.orElse(0) >= 15, "a cliff keeps its height at the edge");
	}

	@Test
	void layersFollowTheSurface() {
		List<TerrainLayer> layers = List.of(new TerrainLayer(WeightedPalette.single("minecraft:grass_block"), 1),
				new TerrainLayer(WeightedPalette.single("minecraft:dirt"), 3),
				new TerrainLayer(WeightedPalette.of(PaletteEntry.of("minecraft:stone", 75), PaletteEntry.of("minecraft:andesite", 25)), 0));
		GenerationContext ctx = GenerationContext.DEFAULT.withPalettes(GenerationContext.DEFAULT.palettes().withTerrainLayers(layers));
		GeometryResult r = TestShapes.generate("terrain", ctx, "width", 32, "template", "HILL", "min_height", 6, "max_height", 20);
		Map<Long, Integer> tops = tops(r);
		long deep = 0;
		long stone = 0;

		for (Placement p : r.placements()) {
			int depth = tops.get(Voxels.pack(p.x(), 0, p.z())) - p.y();
			String block = r.material(p).value();

			if (depth == 0) assertEquals("minecraft:grass_block", block);
			else if (depth <= 3) assertEquals("minecraft:dirt", block);
			else {
				deep++;

				if (block.equals("minecraft:stone")) stone++;
				else assertEquals("minecraft:andesite", block);
			}
		}

		assertEquals(Math.round(deep * 0.75), stone, "the deep layer has exact shares");
	}

	@Test
	void contoursAndHeightLabels() {
		GeometryResult r = TestShapes.generate("terrain", "width", 48, "template", "MOUNTAIN", "contour_interval", 5, "height_labels", true);
		assertTrue(r.guides().stream().anyMatch(g -> g[3] == Guide.CONTOUR));
		assertTrue(r.annotations().stream().allMatch(a -> a.text().equals("Y={y}")) && !r.annotations().isEmpty());
		GeometryResult none = TestShapes.generate("terrain", "width", 48, "template", "MOUNTAIN", "contour_interval", 0);
		assertTrue(none.guides().stream().noneMatch(g -> g[3] == Guide.CONTOUR) && none.annotations().isEmpty());
	}
}
