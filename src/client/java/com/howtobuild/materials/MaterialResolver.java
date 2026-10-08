package com.howtobuild.materials;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

import com.howtobuild.config.MaterialSlot;
import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.Facing;
import com.howtobuild.geometry.MaterialRef;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.StateTransform;

/**
 * Turns placements (role + shape + variant) into real block states using the player's material profile.
 *
 * <p>Full blocks use the role's block (or one of its variants), slabs its slab block with {@code SlabBlock.TYPE}, and
 * stairs its stair block with {@code StairBlock.FACING / HALF / SHAPE}. Each id is validated: a slab slot that does not
 * hold a real {@code SlabBlock} falls back to the family's slab (found by {@link MaterialFamilies}) and finally to the
 * full block, so a resolved state is always valid. Results are cached per (role, shape, variant).
 *
 * <p>A placement with a material reference uses it instead of its role: an exact state (saved builds) is parsed
 * property by property, a block id (randomisation palettes, terrain layers) is shaped like a role's block. Blocks that
 * do not exist in this game (e.g. from a mod that is not installed) become a visible placeholder and are reported, never
 * thrown. Finally, material overrides (Build tab → replace) swap a block for another, keeping every property both
 * blocks share (facing, half, shape, type, waterlogged, axis, …).
 */
public final class MaterialResolver {
	/** Shown in place of a block that does not exist in this game. */
	public static final BlockState MISSING = Blocks.PURPUR_BLOCK.defaultBlockState();

	private record Key(MaterialRole role, BlockShape shape, int variant) {
	}

	private record RefKey(MaterialRef ref, BlockShape shape) {
	}

	private final Map<MaterialRole, MaterialSlot> profile;
	private final Map<String, String> overrides;
	private final Map<Key, BlockState> cache = new HashMap<>();
	private final Map<RefKey, BlockState> refCache = new HashMap<>();
	private final Map<BlockState, String> commandStrings = new HashMap<>();
	private final Set<String> missing = new TreeSet<>();

	public MaterialResolver(Map<MaterialRole, MaterialSlot> profile) {
		this(profile, Map.of());
	}

	public MaterialResolver(Map<MaterialRole, MaterialSlot> profile, Map<String, String> overrides) {
		this.profile = profile;
		this.overrides = overrides == null ? Map.of() : overrides;
	}

	public BlockState resolve(Placement placement) {
		return cache.computeIfAbsent(new Key(placement.role(), placement.shape(), placement.variant()), k -> override(compute(k)));
	}

	/** Resolves a placement of a result whose material table is {@code materials}. */
	public BlockState resolve(Placement placement, List<MaterialRef> materials) {
		if (!placement.hasMaterial() || placement.material() >= materials.size()) return resolve(placement);

		return refCache.computeIfAbsent(new RefKey(materials.get(placement.material()), placement.shape()), k -> override(computeRef(k)));
	}

	/** Whether this placement's material is a block that does not exist in this game (call after resolving it). */
	public boolean isMissing(Placement placement, List<MaterialRef> materials) {
		if (!placement.hasMaterial() || placement.material() >= materials.size()) return false;

		MaterialRef ref = materials.get(placement.material());
		return missing.contains(ref.exact() ? StateTransform.blockId(ref.value()) : ref.value());
	}

	/** Block ids that were requested but do not exist in this game. */
	public Set<String> missing() {
		return missing;
	}

	private BlockState computeRef(RefKey key) {
		MaterialRef ref = key.ref();

		if (ref.exact()) return parseState(ref.value()).orElseGet(() -> {
			missing.add(StateTransform.blockId(ref.value()));
			return MISSING;
		});

		Optional<Block> block = block(ref.value());

		if (block.isEmpty()) {
			missing.add(ref.value());
			return MISSING;
		}

		// A full block uses the palette block as it is (even a slab or stairs block the player picked on purpose).
		if (key.shape().isFull()) return block.get().defaultBlockState();

		return compute(new MaterialSlot(ref.value(), "", ""), key.shape(), 0);
	}

	/**
	 * Parses {@code namespace:block[key=value,…]} against the block registry, property by property. Unknown properties
	 * or values are ignored (the block's default is kept); an unknown block gives an empty result.
	 */
	public static Optional<BlockState> parseState(String text) {
		Optional<Block> block = block(StateTransform.blockId(text));

		if (block.isEmpty()) return Optional.empty();

		BlockState state = block.get().defaultBlockState();

		for (Map.Entry<String, String> e : StateTransform.properties(text).entrySet()) {
			Property<?> property = block.get().getStateDefinition().getProperty(e.getKey());

			if (property != null) state = withValue(state, property, e.getValue());
		}

		return Optional.of(state);
	}

	/** Applies the material overrides: the replacement block with every property it shares with the original. */
	public BlockState override(BlockState state) {
		if (overrides.isEmpty()) return state;

		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		String replacement = overrides.get(id);

		if (replacement == null || replacement.equals(id)) return state;

		Optional<Block> target = block(replacement);

		if (target.isEmpty()) return state;

		return copyProperties(state, target.get().defaultBlockState());
	}

	/** {@code to} with every property of {@code from} that it also has (same name, valid value). */
	public static BlockState copyProperties(BlockState from, BlockState to) {
		BlockState result = to;

		for (Property<?> property : from.getProperties()) {
			Property<?> target = to.getBlock().getStateDefinition().getProperty(property.getName());

			if (target != null) result = withValue(result, target, valueName(from, property));
		}

		return result;
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
	}

	/** The state in command syntax, exactly as Minecraft serialises it (e.g. {@code minecraft:oak_stairs[facing=east,…]}). */
	public String commandString(BlockState state) {
		return commandStrings.computeIfAbsent(state, BlockStateParser::serialize);
	}

	/** The map colour of the state, for hologram tinting. */
	public static int tint(BlockState state) {
		try {
			return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		} catch (RuntimeException e) {
			return 0x9E9E9E;
		}
	}

	private BlockState compute(Key key) {
		MaterialSlot found = profile.get(key.role());
		return compute(found != null ? found : profile.get(MaterialRole.PRIMARY), key.shape(), key.variant());
	}

	private static BlockState compute(MaterialSlot slot, BlockShape shape, int variant) {
		return switch (shape.kind()) {
			case FULL -> fullBlock(slot, variant).defaultBlockState();
			case SLAB -> {
				Optional<Block> slab = typed(slot.slab, SlabBlock.class).or(() -> MaterialFamilies.slabFor(slot.block).flatMap(id -> typed(id, SlabBlock.class)));

				if (slab.isEmpty()) yield fullBlock(slot, 0).defaultBlockState();

				yield slab.get().defaultBlockState().setValue(SlabBlock.TYPE, switch (shape.slabType()) {
					case BOTTOM -> SlabType.BOTTOM;
					case TOP -> SlabType.TOP;
					case DOUBLE -> SlabType.DOUBLE;
				});
			}
			case STAIRS -> {
				Optional<Block> stairs = typed(slot.stairs, StairBlock.class).or(() -> MaterialFamilies.stairsFor(slot.block).flatMap(id -> typed(id, StairBlock.class)));

				if (stairs.isEmpty()) yield fullBlock(slot, 0).defaultBlockState();

				yield stairs.get().defaultBlockState()
						.setValue(StairBlock.FACING, direction(shape.facing()))
						.setValue(StairBlock.HALF, shape.half() == BlockShape.Half.TOP ? Half.TOP : Half.BOTTOM)
						.setValue(StairBlock.SHAPE, switch (shape.stairShape()) {
							case STRAIGHT -> StairsShape.STRAIGHT;
							case INNER_LEFT -> StairsShape.INNER_LEFT;
							case INNER_RIGHT -> StairsShape.INNER_RIGHT;
							case OUTER_LEFT -> StairsShape.OUTER_LEFT;
							case OUTER_RIGHT -> StairsShape.OUTER_RIGHT;
						});
			}
		};
	}

	private static Block fullBlock(MaterialSlot slot, int variant) {
		List<String> variants = slot.variants;

		if (variant > 0 && variants != null && !variants.isEmpty()) {
			Optional<Block> v = block(variants.get((variant - 1) % variants.size()));

			if (v.isPresent()) return v.get();
		}

		return block(slot.block).filter(b -> !(b instanceof SlabBlock) && !(b instanceof StairBlock)).orElse(Blocks.STONE);
	}

	public static Optional<Block> block(String id) {
		Identifier identifier = MaterialFamilies.parse(id);
		return identifier == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(identifier).filter(b -> b != Blocks.AIR);
	}

	private static Optional<Block> typed(String id, Class<? extends Block> type) {
		return block(id).filter(type::isInstance);
	}

	public static Direction direction(Facing facing) {
		return switch (facing) {
			case NORTH -> Direction.NORTH;
			case EAST -> Direction.EAST;
			case SOUTH -> Direction.SOUTH;
			case WEST -> Direction.WEST;
		};
	}
}
