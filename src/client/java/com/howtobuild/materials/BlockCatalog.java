package com.howtobuild.materials;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.howtobuild.geometry.ShapeKind;

/**
 * Every placeable block in the registry (vanilla and modded), classified for the block picker.
 *
 * <p>Classification inspects real block types: stairs are {@code StairBlock}s, slabs are {@code SlabBlock}s, walls are
 * {@code WallBlock}s, pillars are {@code RotatedPillarBlock}s, functional blocks have block entities, and full blocks
 * have a full-cube collision shape and none of those partial types. Names are only used for searching.
 */
public final class BlockCatalog {
	public record Entry(Block block, String id, String namespace, String name, ItemStack icon, boolean full, boolean slab,
			boolean stairs, boolean wall, boolean pillar, boolean functional, String searchText) {
		public boolean modded() {
			return !namespace.equals("minecraft");
		}

		public boolean building() {
			return full || slab || stairs || wall || pillar;
		}

		public boolean in(MaterialCategory category) {
			return switch (category) {
				case ALL -> true;
				case BUILDING_BLOCKS -> building();
				case FULL_BLOCKS -> full;
				case SLABS -> slab;
				case STAIRS -> stairs;
				case WALLS -> wall;
				case PILLARS -> pillar;
				case DECORATIVE -> !building() && !functional;
				case FUNCTIONAL -> functional;
				case MODDED -> modded();
			};
		}

		/** Whether the block can be used as the given material type (Blocks / Slabs / Stairs). */
		public boolean is(ShapeKind kind) {
			return switch (kind) {
				case BLOCKS -> full;
				case SLABS -> slab;
				case STAIRS -> stairs;
			};
		}

		/** True if the block matches any of the selected material types (union). */
		public boolean matchesAny(Set<ShapeKind> kinds) {
			for (ShapeKind kind : kinds) {
				if (is(kind)) return true;
			}

			return false;
		}

		public String category() {
			if (stairs) return "Stairs";
			if (slab) return "Slab";
			if (wall) return "Wall";
			if (pillar) return "Pillar";
			if (full) return "Full block";
			if (functional) return "Functional";
			return "Decorative";
		}
	}

	private static List<Entry> entries;

	private BlockCatalog() {
	}

	/** All entries, built lazily once the registries are complete. */
	public static synchronized List<Entry> all() {
		if (entries == null) entries = Collections.unmodifiableList(scan());
		return entries;
	}

	private static List<Entry> scan() {
		List<Entry> list = new ArrayList<>();

		for (Block block : BuiltInRegistries.BLOCK.stream().toList()) {
			if (block.asItem() == Items.AIR) continue;

			Identifier key = BuiltInRegistries.BLOCK.getKey(block);
			String id = key.toString();
			String name = block.getName().getString();
			BlockState state = block.defaultBlockState();
			boolean slab = block instanceof SlabBlock;
			boolean stairs = block instanceof StairBlock;
			boolean wall = block instanceof WallBlock;
			boolean pillar = block instanceof RotatedPillarBlock;
			boolean functional = block instanceof EntityBlock;
			boolean full = !slab && !stairs && !wall && !functional && fullCube(state);
			StringBuilder search = new StringBuilder(name.toLowerCase(Locale.ROOT)).append(' ').append(id);

			if (state.is(BlockTags.LOGS)) search.append(" #logs");
			if (state.is(BlockTags.PLANKS)) search.append(" #planks");
			if (state.is(BlockTags.WOOL)) search.append(" #wool");
			if (state.is(BlockTags.LEAVES)) search.append(" #leaves");
			if (state.is(BlockTags.STAIRS)) search.append(" #stairs");
			if (state.is(BlockTags.SLABS)) search.append(" #slabs");
			if (state.is(BlockTags.WALLS)) search.append(" #walls");

			Entry entry = new Entry(block, id, key.getNamespace(), name, new ItemStack(block), full, slab, stairs, wall, pillar,
					functional, "");
			list.add(new Entry(block, id, key.getNamespace(), name, entry.icon(), full, slab, stairs, wall, pillar, functional,
					search.append(' ').append(entry.category().toLowerCase(Locale.ROOT)).toString()));
		}

		list.sort((a, b) -> {
			int ns = Boolean.compare(a.modded(), b.modded());
			return ns != 0 ? ns : a.name().compareToIgnoreCase(b.name());
		});
		return list;
	}

	private static boolean fullCube(BlockState state) {
		try {
			return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Filters the catalog.
	 *
	 * @param query    text to search for in names, ids, namespaces, categories and tags (e.g. {@code stone},
	 *                 {@code minecraft:}, {@code #logs}); empty matches everything
	 * @param category category filter
	 * @param kinds    material types to allow (union); empty allows everything
	 */
	public static List<Entry> filter(String query, MaterialCategory category, Set<ShapeKind> kinds) {
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<Entry> result = new ArrayList<>();

		for (Entry e : all()) {
			if (!e.in(category)) continue;
			if (!kinds.isEmpty() && !e.matchesAny(kinds)) continue;
			if (!q.isEmpty() && !matches(e, q)) continue;

			result.add(e);
		}

		return result;
	}

	private static boolean matches(Entry e, String query) {
		for (String term : query.split("\\s+")) {
			if (!e.searchText().contains(term)) return false;
		}

		return true;
	}

	public static Optional<Entry> byId(String id) {
		for (Entry e : all()) {
			if (e.id().equals(id)) return Optional.of(e);
		}

		return Optional.empty();
	}

	public static Set<ShapeKind> kinds(Entry e) {
		EnumSet<ShapeKind> set = EnumSet.noneOf(ShapeKind.class);

		for (ShapeKind kind : ShapeKind.values()) {
			if (e.is(kind)) set.add(kind);
		}

		return set;
	}
}
