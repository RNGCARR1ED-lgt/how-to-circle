package com.howtobuild.materials;

import java.util.EnumMap;
import java.util.Map;

import com.howtobuild.config.MaterialSlot;
import com.howtobuild.geometry.MaterialRole;

/**
 * Material themes: one click sets every role (primary, secondary, trim, accent, highlight, steps, supports, edges, …)
 * to a matching family of blocks. Ids are checked when resolved, so a theme can never produce an invalid block.
 */
public enum MaterialThemes {
	STONE(slot("stone_bricks", "stone_brick_slab", "stone_brick_stairs"), slot("cobblestone", "cobblestone_slab", "cobblestone_stairs"),
			slot("polished_andesite", "polished_andesite_slab", "polished_andesite_stairs"), slot("chiseled_stone_bricks", "smooth_stone_slab", "stone_stairs")),
	DEEPSLATE(slot("deepslate_bricks", "deepslate_brick_slab", "deepslate_brick_stairs"), slot("deepslate_tiles", "deepslate_tile_slab", "deepslate_tile_stairs"),
			slot("polished_deepslate", "polished_deepslate_slab", "polished_deepslate_stairs"),
			slot("chiseled_deepslate", "cobbled_deepslate_slab", "cobbled_deepslate_stairs")),
	SANDSTONE(slot("sandstone", "sandstone_slab", "sandstone_stairs"), slot("smooth_sandstone", "smooth_sandstone_slab", "smooth_sandstone_stairs"),
			slot("cut_sandstone", "cut_sandstone_slab", "sandstone_stairs"), slot("chiseled_sandstone", "sandstone_slab", "sandstone_stairs")),
	OAK(slot("oak_planks", "oak_slab", "oak_stairs"), slot("spruce_planks", "spruce_slab", "spruce_stairs"),
			slot("stripped_oak_log", "oak_slab", "oak_stairs"), slot("dark_oak_planks", "dark_oak_slab", "dark_oak_stairs")),
	QUARTZ(slot("quartz_block", "quartz_slab", "quartz_stairs"), slot("smooth_quartz", "smooth_quartz_slab", "smooth_quartz_stairs"),
			slot("quartz_bricks", "quartz_slab", "quartz_stairs"), slot("chiseled_quartz_block", "quartz_slab", "quartz_stairs")),
	BLACKSTONE(slot("polished_blackstone_bricks", "polished_blackstone_brick_slab", "polished_blackstone_brick_stairs"),
			slot("blackstone", "blackstone_slab", "blackstone_stairs"), slot("polished_blackstone", "polished_blackstone_slab", "polished_blackstone_stairs"),
			slot("gilded_blackstone", "polished_blackstone_slab", "polished_blackstone_stairs")),
	PRISMARINE(slot("prismarine_bricks", "prismarine_brick_slab", "prismarine_brick_stairs"), slot("prismarine", "prismarine_slab", "prismarine_stairs"),
			slot("dark_prismarine", "dark_prismarine_slab", "dark_prismarine_stairs"), slot("sea_lantern", "prismarine_slab", "prismarine_stairs")),
	BRICK(slot("bricks", "brick_slab", "brick_stairs"), slot("mud_bricks", "mud_brick_slab", "mud_brick_stairs"),
			slot("smooth_stone", "smooth_stone_slab", "stone_stairs"), slot("terracotta", "brick_slab", "brick_stairs"));

	private final MaterialSlot main;
	private final MaterialSlot secondary;
	private final MaterialSlot trim;
	private final MaterialSlot accent;

	MaterialThemes(MaterialSlot main, MaterialSlot secondary, MaterialSlot trim, MaterialSlot accent) {
		this.main = main;
		this.secondary = secondary;
		this.trim = trim;
		this.accent = accent;
	}

	private static MaterialSlot slot(String block, String slab, String stairs) {
		return new MaterialSlot("minecraft:" + block, "minecraft:" + slab, "minecraft:" + stairs);
	}

	/** The theme's block for every role. */
	public Map<MaterialRole, MaterialSlot> materials() {
		Map<MaterialRole, MaterialSlot> m = new EnumMap<>(MaterialRole.class);

		for (MaterialRole role : MaterialRole.values()) {
			MaterialSlot source = switch (role) {
				case PRIMARY, STEP, FLOOR -> main;
				case SECONDARY, SUPPORT, INNER -> secondary;
				case TRIM, CAP, RAIL, OUTER_EDGE, INNER_EDGE -> trim;
				case ACCENT, HIGHLIGHT -> accent;
			};
			m.put(role, source.copy());
		}

		return m;
	}
}
