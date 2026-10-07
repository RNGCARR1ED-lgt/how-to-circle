package com.howtobuild.materials;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

import com.howtobuild.config.MaterialSlot;
import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.Facing;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;

/**
 * Turns placements (role + shape + variant) into real block states using the player's material profile.
 *
 * <p>Full blocks use the role's block (or one of its variants), slabs its slab block with {@code SlabBlock.TYPE}, and
 * stairs its stair block with {@code StairBlock.FACING / HALF / SHAPE}. Each id is validated: a slab slot that does not
 * hold a real {@code SlabBlock} falls back to the family's slab (found by {@link MaterialFamilies}) and finally to the
 * full block, so a resolved state is always valid. Results are cached per (role, shape, variant).
 */
public final class MaterialResolver {
	private record Key(MaterialRole role, BlockShape shape, int variant) {
	}

	private final Map<MaterialRole, MaterialSlot> profile;
	private final Map<Key, BlockState> cache = new HashMap<>();
	private final Map<BlockState, String> commandStrings = new HashMap<>();

	public MaterialResolver(Map<MaterialRole, MaterialSlot> profile) {
		this.profile = profile;
	}

	public BlockState resolve(Placement placement) {
		return cache.computeIfAbsent(new Key(placement.role(), placement.shape(), placement.variant()), this::compute);
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
		final MaterialSlot slot = found != null ? found : profile.get(MaterialRole.PRIMARY);
		BlockShape shape = key.shape();

		return switch (shape.kind()) {
			case FULL -> fullBlock(slot, key.variant()).defaultBlockState();
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
