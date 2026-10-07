package com.howtobuild.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import com.howtobuild.geometry.Box;
import com.howtobuild.geometry.BoxDecomposer;
import com.howtobuild.geometry.Voxels;

/**
 * Turns exact block placements into an optimised mix of {@code /fill} and {@code /setblock} commands.
 *
 * <ol>
 *     <li>Blocks are grouped by their <em>exact</em> state string (stairs facing different ways, or slabs of different
 *     types, are different groups and are never merged).</li>
 *     <li>Each group is split into maximal exact boxes ({@link BoxDecomposer}): every cell of a box is a planned block
 *     with that state, so a {@code /fill} never touches space that should stay empty.</li>
 *     <li>Boxes larger than the command block limit are split; boxes of one block become {@code /setblock}.</li>
 *     <li>Commands are ordered bottom-up so gravity blocks always have support.</li>
 * </ol>
 */
public final class CommandPlanner {
	/** Vanilla's default limit for blocks changed by one {@code /fill}. */
	public static final int DEFAULT_MAX_VOLUME = 32768;

	/**
	 * @param maxFillVolume largest box a single {@code /fill} may cover
	 * @param keepExisting  use {@code keep} so existing blocks are never replaced
	 */
	public record Options(int maxFillVolume, boolean keepExisting) {
		public static final Options DEFAULT = new Options(DEFAULT_MAX_VOLUME, false);
	}

	private record Op(Box box, String state) {
	}

	private CommandPlanner() {
	}

	public static CommandPlan plan(List<StatePlacement> blocks, Options options) {
		if (blocks.isEmpty()) return CommandPlan.EMPTY;

		Map<String, LongOpenHashSet> byState = new TreeMap<>();
		LongOpenHashSet all = new LongOpenHashSet();

		for (StatePlacement b : blocks) {
			long key = Voxels.pack(b.x(), b.y(), b.z());

			if (all.add(key)) {
				byState.computeIfAbsent(b.state(), s -> new LongOpenHashSet()).add(key);
			}
		}

		List<Op> ops = new ArrayList<>();

		for (Map.Entry<String, LongOpenHashSet> entry : byState.entrySet()) {
			for (Box box : BoxDecomposer.decompose(entry.getValue(), Math.max(1, options.maxFillVolume()))) {
				ops.add(new Op(box, entry.getKey()));
			}
		}

		ops.sort(Comparator.comparingInt((Op o) -> o.box().minY()).thenComparingInt(o -> o.box().minZ())
				.thenComparingInt(o -> o.box().minX()).thenComparing(Op::state));

		List<String> commands = new ArrayList<>(ops.size());
		int fills = 0;
		int setblocks = 0;
		String suffix = options.keepExisting() ? " keep" : "";

		for (Op op : ops) {
			Box b = op.box();

			if (b.volume() == 1) {
				commands.add("setblock " + b.minX() + " " + b.minY() + " " + b.minZ() + " " + op.state() + suffix);
				setblocks++;
			} else {
				commands.add(fill(b, op.state()) + suffix);
				fills++;
			}
		}

		List<String> undo = new ArrayList<>();

		for (Box b : BoxDecomposer.decompose(all, Math.max(1, options.maxFillVolume()))) {
			undo.add(b.volume() == 1 ? "setblock " + b.minX() + " " + b.minY() + " " + b.minZ() + " minecraft:air" : fill(b, "minecraft:air"));
		}

		return new CommandPlan(List.copyOf(commands), List.copyOf(undo), all.size(), fills, setblocks);
	}

	static String fill(Box b, String state) {
		return "fill " + b.minX() + " " + b.minY() + " " + b.minZ() + " " + b.maxX() + " " + b.maxY() + " " + b.maxZ() + " " + state;
	}

	/**
	 * Expands commands produced by this planner back into blocks (used by tests and for verifying plans). Returns
	 * packed positions mapped to state strings; unknown commands are ignored.
	 */
	public static Map<Long, String> expand(List<String> commands) {
		Map<Long, String> result = new java.util.HashMap<>();

		for (String command : commands) {
			String[] parts = command.split(" ");

			if (parts[0].equals("setblock")) {
				result.put(Voxels.pack(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])), parts[4]);
			} else if (parts[0].equals("fill")) {
				int[] c = new int[6];

				for (int i = 0; i < 6; i++) {
					c[i] = Integer.parseInt(parts[i + 1]);
				}

				for (int y = c[1]; y <= c[4]; y++) {
					for (int z = c[2]; z <= c[5]; z++) {
						for (int x = c[0]; x <= c[3]; x++) {
							result.put(Voxels.pack(x, y, z), parts[7]);
						}
					}
				}
			}
		}

		return result;
	}
}
