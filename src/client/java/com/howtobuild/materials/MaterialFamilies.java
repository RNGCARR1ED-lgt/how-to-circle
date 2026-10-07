package com.howtobuild.materials;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;

/**
 * Finds the slab and stairs that belong to a base block (e.g. {@code stone_bricks} → {@code stone_brick_slab},
 * {@code stone_brick_stairs}). Candidate ids follow vanilla naming conventions, but a candidate is only accepted if
 * the registry block really is a {@link SlabBlock} or {@link StairBlock}, so the result is always usable.
 */
public final class MaterialFamilies {
	private MaterialFamilies() {
	}

	public static Optional<String> slabFor(String baseId) {
		return find(baseId, "_slab", SlabBlock.class);
	}

	public static Optional<String> stairsFor(String baseId) {
		return find(baseId, "_stairs", StairBlock.class);
	}

	private static Optional<String> find(String baseId, String suffix, Class<? extends Block> type) {
		Identifier base = parse(baseId);

		if (base == null) return Optional.empty();

		for (String path : candidates(base.getPath())) {
			Identifier id = Identifier.fromNamespaceAndPath(base.getNamespace(), path + suffix);
			Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);

			if (block.isPresent() && type.isInstance(block.get())) return Optional.of(id.toString());
		}

		return Optional.empty();
	}

	static List<String> candidates(String path) {
		List<String> list = new ArrayList<>();
		list.add(path);

		if (path.endsWith("_planks")) list.add(path.substring(0, path.length() - "_planks".length()));
		if (path.endsWith("_block")) list.add(path.substring(0, path.length() - "_block".length()));
		if (path.endsWith("bricks")) list.add(path.substring(0, path.length() - 1));
		if (path.endsWith("tiles")) list.add(path.substring(0, path.length() - 1));
		if (path.endsWith("s")) list.add(path.substring(0, path.length() - 1));
		if (path.startsWith("smooth_") || path.startsWith("polished_") || path.startsWith("cut_")) list.add(path);
		if (path.endsWith("_log") || path.endsWith("_wood")) list.add(path.substring(0, path.lastIndexOf('_')));

		return list;
	}

	public static Identifier parse(String id) {
		try {
			return id == null || id.isBlank() ? null : Identifier.parse(id);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
