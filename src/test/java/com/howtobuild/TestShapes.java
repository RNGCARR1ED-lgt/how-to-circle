package com.howtobuild;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.geometry.Voxels;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.GeometryPipeline;
import com.howtobuild.tools.ToolRegistry;
import com.howtobuild.tools.ToolSettings;

/** Helpers shared by the tests. */
public final class TestShapes {
	private TestShapes() {
	}

	public static GeometryResult generate(String toolId, Object... keyValues) {
		return generate(toolId, GenerationContext.DEFAULT, keyValues);
	}

	public static GeometryResult generate(String toolId, GenerationContext ctx, Object... keyValues) {
		BuildTool tool = ToolRegistry.get(toolId);
		return GeometryPipeline.generate(tool, settings(toolId, keyValues), ctx);
	}

	public static ToolSettings settings(String toolId, Object... keyValues) {
		ToolSettings s = ToolRegistry.get(toolId).defaults();

		for (int i = 0; i < keyValues.length; i += 2) {
			s = s.with((String) keyValues[i], keyValues[i + 1]);
		}

		return s;
	}

	public static GenerationContext types(ShapeKind... kinds) {
		EnumSet<ShapeKind> set = EnumSet.noneOf(ShapeKind.class);
		java.util.Collections.addAll(set, kinds);
		return GenerationContext.DEFAULT.withMaterialTypes(set);
	}

	public static GenerationContext details(DetailFeature... features) {
		return GenerationContext.DEFAULT.withDetails(DetailSettings.of(features));
	}

	public static Set<Long> positions(GeometryResult r) {
		Set<Long> set = new HashSet<>();

		for (Placement p : r.placements()) {
			set.add(Voxels.pack(p.x(), p.y(), p.z()));
		}

		return set;
	}
}
