package com.howtobuild.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.howtobuild.TestShapes;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailPreset;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.details.MaterialPattern;
import com.howtobuild.details.MaterialVariation;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.tools.capability.Detailable;

class PipelineTest {
	@Test
	void everyToolGeneratesDeterministicallyWithEveryPreset() {
		for (BuildTool tool : ToolRegistry.all()) {
			for (DetailPreset preset : DetailPreset.values()) {
				Set<DetailFeature> features = tool instanceof Detailable d ? d.presetDetails(preset) : Set.of();

				if (tool instanceof Detailable d) {
					assertTrue(d.supportedDetails().containsAll(features), tool.id() + " " + preset + " only uses supported details");
				}

				GenerationContext ctx = GenerationContext.DEFAULT.withMaterialTypes(EnumSet.allOf(ShapeKind.class))
						.withDetails(DetailSettings.NONE.withFeatures(features.isEmpty() ? EnumSet.noneOf(DetailFeature.class) : EnumSet.copyOf(features)));
				GeometryResult a = GeometryPipeline.generate(tool, tool.defaults(), ctx);
				GeometryResult b = GeometryPipeline.generate(tool, tool.defaults(), ctx);
				assertFalse(a.isEmpty(), tool.id() + " " + preset + ": " + a.warnings());
				assertEquals(a.placements(), b.placements(), "deterministic");
				Set<Long> keys = new HashSet<>();
				a.placements().forEach(p -> assertTrue(keys.add(p.key()), "no duplicate positions"));
			}
		}
	}

	@Test
	void parametersHaveTranslationsAndValidDefaults() {
		for (BuildTool tool : ToolRegistry.all()) {
			ToolSettings defaults = tool.defaults();

			for (ToolParameter p : tool.parameters()) {
				assertEquals(defaults.raw(p.id()), p.sanitize(defaults.raw(p.id())));
			}
		}

		assertEquals("5", ToolParameter.integer("x", 1, 5, 3).sanitize("99"));
		assertEquals("3", ToolParameter.integer("x", 1, 5, 3).sanitize("abc"));
	}

	@Test
	void rotationTurnsStairsWithTheGeometry() {
		GeometryResult base = TestShapes.generate("spiral", TestShapes.types(ShapeKind.STAIRS));
		GeometryResult turned = TestShapes.generate("spiral", TestShapes.types(ShapeKind.STAIRS).withRotation(1));
		assertEquals(base.blockCount(), turned.blockCount());

		for (Placement p : base.placements()) {
			int[] r = GeometryPipeline.rotateXZ(p.x(), p.z(), 1);
			Placement q = turned.at(r[0], p.y(), r[1]);
			assertEquals(p.shape().rotated(1).facing(), q.shape().facing());
		}
	}

	@Test
	void patternsAndVariationAreDeterministic() {
		DetailSettings d = new DetailSettings(EnumSet.noneOf(DetailFeature.class), 4, MaterialPattern.CHECKER, 2, MaterialVariation.MEDIUM, 1234);
		GenerationContext ctx = GenerationContext.DEFAULT.withDetails(d);
		GeometryResult a = TestShapes.generate("sphere", ctx, "style", "FILLED");
		GeometryResult b = TestShapes.generate("sphere", ctx, "style", "FILLED");
		assertEquals(a.placements(), b.placements());
		assertTrue(a.count(MaterialRole.TRIM) > 0, "checker pattern uses the trim material");
		long varied = a.placements().stream().filter(p -> p.variant() > 0).count();
		assertTrue(varied > a.blockCount() * 0.15 && varied < a.blockCount() * 0.45, "medium variation ≈ 30 %: " + varied);
		DetailSettings other = new DetailSettings(EnumSet.noneOf(DetailFeature.class), 4, MaterialPattern.CHECKER, 2, MaterialVariation.MEDIUM, 99);
		GeometryResult c = TestShapes.generate("sphere", GenerationContext.DEFAULT.withDetails(other), "style", "FILLED");
		assertFalse(a.placements().equals(c.placements()), "a different seed gives a different variation");
	}

	@Test
	void tooLargeShapesAreRejectedNotGenerated() {
		GeometryResult r = TestShapes.generate("sphere", "diameter_x", 512, "diameter_y", 512, "diameter_z", 512, "style", "FILLED");
		assertTrue(r.isEmpty());
		assertTrue(r.warnings().getFirst().contains("limit"));
	}
}
