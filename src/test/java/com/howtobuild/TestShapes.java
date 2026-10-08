package com.howtobuild;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.geometry.Voxels;
import com.howtobuild.palette.PaletteEntry;
import com.howtobuild.palette.RandomPattern;
import com.howtobuild.palette.RandomSettings;
import com.howtobuild.palette.WeightedPalette;
import com.howtobuild.saves.BuildFile;
import com.howtobuild.saves.SavedBuilds;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.GeometryPipeline;
import com.howtobuild.tools.ToolRegistry;
import com.howtobuild.tools.ToolSettings;

/** Helpers shared by the tests. */
public final class TestShapes {
	/** A small saved build kept in memory (no files) for tests that place saved builds. */
	public static final String SAVED_FIXTURE = "test fixture";

	/** A 60 / 40 stone / andesite palette. */
	public static final WeightedPalette TWO_BLOCKS = WeightedPalette.of(PaletteEntry.of("minecraft:stone", 60), PaletteEntry.of("minecraft:andesite", 40));

	static {
		SavedBuilds.put(SAVED_FIXTURE, sampleBuild());
	}

	private TestShapes() {
	}

	/**
	 * A 4 × 4 stone floor with a 2×2 centre, two oak stairs, a top slab, an oak log on its side and a door above it, so
	 * every kind of state transform is exercised.
	 */
	public static BuildFile sampleBuild() {
		List<String> palette = List.of("minecraft:stone", "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
				"minecraft:oak_slab[type=top,waterlogged=false]", "minecraft:oak_log[axis=x]",
				"minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]");
		java.util.List<Integer> b = new java.util.ArrayList<>();

		for (int x = -1; x <= 2; x++) {
			for (int z = -1; z <= 2; z++) {
				add(b, x, 0, z, 0);
			}
		}

		add(b, 0, 1, -1, 1);
		add(b, 1, 1, -1, 1);
		add(b, 0, 1, 2, 2);
		add(b, 1, 2, 0, 3);
		add(b, 2, 1, 1, 4);
		List<int[]> centre = List.of(new int[] {0, 0, 0}, new int[] {1, 0, 0}, new int[] {0, 0, 1}, new int[] {1, 0, 1});
		return new BuildFile("Test fixture", "tests", "", "capture", 0, BuildFile.Origin.CENTER, centre, new int[3], 0, "", false, palette,
				b.stream().mapToInt(Integer::intValue).toArray());
	}

	private static void add(List<Integer> b, int x, int y, int z, int state) {
		b.add(x);
		b.add(y);
		b.add(z);
		b.add(state);
	}

	/** A context whose global randomisation palette is {@link #TWO_BLOCKS} (natural pattern, fixed seed). */
	public static GenerationContext randomised(GenerationContext ctx) {
		return ctx.withPalettes(ctx.palettes().withRandom(RandomSettings.of(TWO_BLOCKS, RandomPattern.NATURAL, 42)));
	}

	/** Default settings that generate something for every tool (the saved-build tool gets the fixture). */
	public static ToolSettings defaults(BuildTool tool) {
		return tool.id().equals("saved_build") ? tool.defaults().with("build", SAVED_FIXTURE) : tool.defaults();
	}

	public static GeometryResult generate(String toolId, Object... keyValues) {
		return generate(toolId, GenerationContext.DEFAULT, keyValues);
	}

	public static GeometryResult generate(String toolId, GenerationContext ctx, Object... keyValues) {
		BuildTool tool = ToolRegistry.get(toolId);
		return GeometryPipeline.generate(tool, settings(toolId, keyValues), ctx);
	}

	public static ToolSettings settings(String toolId, Object... keyValues) {
		ToolSettings s = defaults(ToolRegistry.get(toolId));

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
