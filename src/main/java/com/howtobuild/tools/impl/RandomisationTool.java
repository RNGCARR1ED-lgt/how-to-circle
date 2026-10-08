package com.howtobuild.tools.impl;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.GeometryCenter;
import com.howtobuild.geometry.Guide;
import com.howtobuild.geometry.Mask2D;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.palette.RandomSettings;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.Plane;
import com.howtobuild.tools.ToolParameter;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.ValidationResult;
import com.howtobuild.tools.capability.CommandBuildable;
import com.howtobuild.tools.capability.Dimensionable;
import com.howtobuild.tools.capability.MaterialAssignable;
import com.howtobuild.tools.capability.Mirrorable;
import com.howtobuild.tools.capability.Rotatable;

/**
 * Randomisation: a floor, wall or block of any region shape filled with the randomisation palette (Randomise tab).
 *
 * <p>The region is exact (circles and ovals are the Circle tool's own cells); randomisation then only chooses each
 * block's material, so the shape and the block count never change. Floors lie on the anchor layer; walls stand on it.
 */
public final class RandomisationTool implements BuildTool, Mirrorable, Rotatable, MaterialAssignable, Dimensionable, CommandBuildable {
	@Override
	public String id() {
		return "randomise";
	}

	@Override
	public List<ToolParameter> parameters() {
		return List.of(
				ToolParameter.choice("region_shape", RegionShape.RECTANGLE),
				ToolParameter.integer("width", 1, 512, 20),
				ToolParameter.integer("length", 1, 512, 20).visibleWhen(s -> !s.getEnum("region_shape", RegionShape.class).uniform()),
				ToolParameter.integer("thickness", 1, 64, 1),
				ToolParameter.choice("plane", Plane.FLOOR).section("centre"));
	}

	private static int length(ToolSettings s) {
		return s.getEnum("region_shape", RegionShape.class).length(s.getInt("width"), s.getInt("length"));
	}

	@Override
	public long estimateBlocks(ToolSettings s) {
		return (long) s.getInt("width") * length(s) * s.getInt("thickness");
	}

	@Override
	public ValidationResult validate(ToolSettings s, GenerationContext ctx) {
		ValidationResult r = new ValidationResult();
		RandomSettings random = randomisation(s, ctx);
		r.errorIf(random.palette().isEmpty(), "Add at least one block to the randomisation palette (Randomise tab).");

		if (random.palette().totalWarning() != null) r.warn(random.palette().totalWarning());

		return r;
	}

	/** This tool always randomises every block with the palette. */
	@Override
	public RandomSettings randomisation(ToolSettings settings, GenerationContext context) {
		RandomSettings global = context.palettes().random();
		return new RandomSettings(true, global.palette(), global.mode(), global.pattern(), global.seed(), global.clusterSize(), global.noiseScale(),
				global.noiseStrength(), global.octaves(), global.contrast(), global.threshold(), EnumSet.allOf(MaterialRole.class), global.protectEdge(),
				global.edgeBlock(), global.edgeVariation(), global.symmetry(), global.mirror());
	}

	@Override
	public int planeNormalAxis(ToolSettings s, GenerationContext ctx) {
		if (s.getInt("thickness") > 1) return -1;
		return s.getEnum("plane", Plane.class) == Plane.FLOOR ? 1 : 2;
	}

	@Override
	public void generate(ToolSettings s, GenerationContext ctx, GeometryBuilder out) {
		RegionShape shape = s.getEnum("region_shape", RegionShape.class);
		int w = s.getInt("width");
		int l = length(s);
		int t = s.getInt("thickness");
		boolean floor = s.getEnum("plane", Plane.class) == Plane.FLOOR;
		Mask2D mask = shape.mask(w, l);
		// Floor: the region lies in X × Z, layers go up. Wall: the region stands in X × Y, layers go south.
		GeometryCenter centre = floor ? GeometryCenter.of(w, l, ctx.alignX(), ctx.alignZ()) : GeometryCenter.of(w, t, ctx.alignX(), ctx.alignZ());

		for (int v = 0; v < l; v++) {
			for (int u = 0; u < w; u++) {
				if (!mask.contains(u, v)) continue;

				int x = centre.minX() + u;

				for (int layer = 0; layer < t; layer++) {
					if (floor) {
						int z = centre.minZ() + v;
						out.set(x, layer, z, MaterialRole.PRIMARY);
						out.regionColumn(x, z);
					} else {
						int z = centre.minZ() + layer;
						out.set(x, v, z, MaterialRole.PRIMARY);
						out.regionColumn(x, z);
					}
				}

				if (floor && (!mask.contains(u - 1, v) || !mask.contains(u + 1, v) || !mask.contains(u, v - 1) || !mask.contains(u, v + 1))) {
					out.guide(x, 0, centre.minZ() + v, Guide.BOUNDARY);
				}
			}
		}

		centre.addCentreCells(out, 0);
		out.value("width", w);
		out.value("length", floor ? l : t);
		out.value("height", floor ? t : l);
	}

	@Override
	public Set<MaterialRole> roles() {
		return EnumSet.of(MaterialRole.PRIMARY);
	}

	@Override
	public List<String> dimensionKeys() {
		return List.of("width", "length", "height");
	}
}
