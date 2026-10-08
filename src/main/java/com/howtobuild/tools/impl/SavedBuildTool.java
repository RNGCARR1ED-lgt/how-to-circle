package com.howtobuild.tools.impl;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.Facing;
import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.MaterialRef;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.StateTransform;
import com.howtobuild.palette.RandomSettings;
import com.howtobuild.saves.BuildFile;
import com.howtobuild.saves.SavedBuilds;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.ToolParameter;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.ValidationResult;
import com.howtobuild.tools.VerticalAnchor;
import com.howtobuild.tools.capability.CommandBuildable;
import com.howtobuild.tools.capability.Dimensionable;
import com.howtobuild.tools.capability.Mirrorable;
import com.howtobuild.tools.capability.Rotatable;

/**
 * Places a saved build ({@code .hwb}) as a hologram: the exact saved block states at their saved positions relative to
 * the saved origin, so what was saved is exactly what is placed. The shared pipeline rotates it (Y, quarter turns),
 * mirrors it, offsets it and builds it; this tool adds the flips (X, Z, upside down), which also transform each block
 * state (stairs, slabs, logs, doors, …).
 */
public final class SavedBuildTool implements BuildTool, Mirrorable, Rotatable, Dimensionable, CommandBuildable {
	@Override
	public String id() {
		return "saved_build";
	}

	@Override
	public List<ToolParameter> parameters() {
		return List.of(
				ToolParameter.text("build", ""),
				ToolParameter.bool("flip_x", false),
				ToolParameter.bool("flip_z", false),
				ToolParameter.bool("upside_down", false));
	}

	private static Optional<BuildFile> file(ToolSettings s) {
		return SavedBuilds.get(s.raw("build"));
	}

	@Override
	public long estimateBlocks(ToolSettings s) {
		return file(s).map(BuildFile::blockCount).orElse(0);
	}

	@Override
	public VerticalAnchor verticalAnchor(ToolSettings s) {
		// Saved at or above the origin layer: the origin layer is the base. Builds reaching below it keep their offset.
		return file(s).map(f -> f.blockCount() == 0 || f.bounds()[1] >= 0 ? VerticalAnchor.BASE : VerticalAnchor.CENTRE).orElse(VerticalAnchor.BASE);
	}

	/** Saved blocks keep their exact states; randomisation never changes them. */
	@Override
	public RandomSettings randomisation(ToolSettings settings, GenerationContext context) {
		return RandomSettings.OFF;
	}

	@Override
	public ValidationResult validate(ToolSettings s, GenerationContext ctx) {
		ValidationResult r = new ValidationResult();
		String name = s.raw("build");

		if (name.isBlank()) {
			r.error("Choose a saved build (Builds section).");
			return r;
		}

		Optional<BuildFile> file = file(s);

		if (file.isEmpty()) {
			r.error(SavedBuilds.error(name).map(e -> "Cannot load \"" + name + "\": " + e).orElse("Saved build \"" + name + "\" was not found."));
			return r;
		}

		r.errorIf(file.get().procedural(), "\"" + name + "\" is a procedural preset: load it from the Builds section to restore its tool and settings.");
		r.errorIf(!file.get().procedural() && file.get().blockCount() == 0, "\"" + name + "\" contains no blocks.");
		return r;
	}

	@Override
	public void generate(ToolSettings s, GenerationContext ctx, GeometryBuilder out) {
		Optional<BuildFile> loaded = file(s);

		if (loaded.isEmpty()) return;

		BuildFile f = loaded.get();
		boolean flipX = s.getBool("flip_x");
		boolean flipZ = s.getBool("flip_z");
		boolean upsideDown = s.getBool("upside_down");
		int[] b = f.bounds();
		// Flips are about the saved centre (doubled coordinates keep a 2×2 centre exact) and, vertically, about the
		// middle of the build so it stays on the same layers.
		int[] doubled = doubledCentre(f);
		int sumY = b[1] + b[4];
		List<String> palette = f.palette();
		int[] ids = new int[palette.size()];
		String[] transformed = new String[palette.size()];
		BlockShape[] shapes = new BlockShape[palette.size()];

		for (int i = 0; i < palette.size(); i++) {
			String state = transform(palette.get(i), flipX, flipZ, upsideDown);
			transformed[i] = state;
			ids[i] = out.material(MaterialRef.exactState(state));
			shapes[i] = shapeOf(state);
		}

		int[] blocks = f.blocks();

		for (int i = 0; i < blocks.length; i += 4) {
			int x = flipX ? doubled[0] - blocks[i] : blocks[i];
			int y = upsideDown ? sumY - blocks[i + 1] : blocks[i + 1];
			int z = flipZ ? doubled[1] - blocks[i + 2] : blocks[i + 2];
			int p = blocks[i + 3];
			out.put(new Placement(x, y, z, MaterialRole.PRIMARY, shapes[p], 0, false, ids[p]));
		}

		for (int[] c : f.centreCells()) {
			out.centreCell(flipX ? doubled[0] - c[0] : c[0], upsideDown ? sumY - c[1] : c[1], flipZ ? doubled[1] - c[2] : c[2]);
		}

		int[] size = f.size();
		out.value("width", size[0]);
		out.value("length", size[2]);
		out.value("height", size[1]);
		out.value("blocks", f.blockCount());
		out.value("block_types", palette.size());
	}

	/** Twice the saved centre's X and Z (the sum of the centre cells' minimum and maximum), or 0 without centre cells. */
	static int[] doubledCentre(BuildFile f) {
		if (f.centreCells().isEmpty()) return new int[2];

		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (int[] c : f.centreCells()) {
			minX = Math.min(minX, c[0]);
			maxX = Math.max(maxX, c[0]);
			minZ = Math.min(minZ, c[2]);
			maxZ = Math.max(maxZ, c[2]);
		}

		return new int[] {minX + maxX, minZ + maxZ};
	}

	/** A saved state after the flips (X: east ↔ west, Z: north ↔ south, upside down: top ↔ bottom). */
	public static String transform(String state, boolean flipX, boolean flipZ, boolean upsideDown) {
		String result = state;

		if (flipX) result = StateTransform.mirror(result, true);
		if (flipZ) result = StateTransform.mirror(result, false);
		if (upsideDown) result = StateTransform.flipVertical(result);

		return result;
	}

	/** The geometric shape of a block state, for the hologram mesh and stair handling: slab, stairs or full block. */
	public static BlockShape shapeOf(String state) {
		String id = StateTransform.blockId(state);
		Map<String, String> p = StateTransform.properties(state);

		if (id.endsWith("_slab")) {
			return switch (p.getOrDefault("type", "bottom")) {
				case "top" -> BlockShape.TOP_SLAB;
				case "double" -> BlockShape.DOUBLE_SLAB;
				default -> BlockShape.BOTTOM_SLAB;
			};
		}

		if (id.endsWith("_stairs")) {
			Facing facing;

			try {
				facing = Facing.valueOf(p.getOrDefault("facing", "north").toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				facing = Facing.NORTH;
			}

			BlockShape.Half half = "top".equals(p.get("half")) ? BlockShape.Half.TOP : BlockShape.Half.BOTTOM;
			BlockShape.StairShape shape;

			try {
				shape = BlockShape.StairShape.valueOf(p.getOrDefault("shape", "straight").toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				shape = BlockShape.StairShape.STRAIGHT;
			}

			return BlockShape.stairs(facing, half, shape);
		}

		return BlockShape.FULL;
	}

	@Override
	public List<String> dimensionKeys() {
		return List.of("width", "length", "height", "blocks", "block_types");
	}
}
