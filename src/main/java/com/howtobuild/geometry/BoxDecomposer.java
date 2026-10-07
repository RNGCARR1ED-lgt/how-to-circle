package com.howtobuild.geometry;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Splits a set of block positions into exact, non-overlapping boxes ("connected sections" in 3D).
 *
 * <p>Greedy: take the lowest unvisited cell in (y, z, x) order, grow along X while every cell is present, then along Z
 * while every full row is present, then along Y while every full layer is present. Every box contains only cells from
 * the input and every input cell is in exactly one box, so a box can safely become a single {@code /fill}. The result
 * is deterministic.
 */
public final class BoxDecomposer {
	private BoxDecomposer() {
	}

	public static List<Box> decompose(LongOpenHashSet cells) {
		return decompose(cells, Long.MAX_VALUE);
	}

	/** Same as {@link #decompose(LongOpenHashSet)} but never produces a box larger than {@code maxVolume} blocks. */
	public static List<Box> decompose(LongOpenHashSet cells, long maxVolume) {
		LongArrayList order = new LongArrayList(cells);
		order.sort((a, b) -> {
			int c = Integer.compare(Voxels.y(a), Voxels.y(b));
			if (c != 0) return c;
			c = Integer.compare(Voxels.z(a), Voxels.z(b));
			return c != 0 ? c : Integer.compare(Voxels.x(a), Voxels.x(b));
		});

		LongOpenHashSet remaining = new LongOpenHashSet(cells);
		List<Box> boxes = new ArrayList<>();

		for (int i = 0; i < order.size(); i++) {
			long start = order.getLong(i);

			if (!remaining.contains(start)) continue;

			int x0 = Voxels.x(start);
			int y0 = Voxels.y(start);
			int z0 = Voxels.z(start);
			int x1 = x0;

			while (remaining.contains(Voxels.pack(x1 + 1, y0, z0)) && volume(x0, x1 + 1, y0, y0, z0, z0) <= maxVolume) {
				x1++;
			}

			int z1 = z0;

			while (rowPresent(remaining, x0, x1, y0, z1 + 1) && volume(x0, x1, y0, y0, z0, z1 + 1) <= maxVolume) {
				z1++;
			}

			int y1 = y0;

			while (layerPresent(remaining, x0, x1, y1 + 1, z0, z1) && volume(x0, x1, y0, y1 + 1, z0, z1) <= maxVolume) {
				y1++;
			}

			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					for (int x = x0; x <= x1; x++) {
						remaining.remove(Voxels.pack(x, y, z));
					}
				}
			}

			boxes.add(new Box(x0, y0, z0, x1, y1, z1));
		}

		return boxes;
	}

	private static long volume(int x0, int x1, int y0, int y1, int z0, int z1) {
		return (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
	}

	private static boolean rowPresent(LongOpenHashSet cells, int x0, int x1, int y, int z) {
		for (int x = x0; x <= x1; x++) {
			if (!cells.contains(Voxels.pack(x, y, z))) return false;
		}

		return true;
	}

	private static boolean layerPresent(LongOpenHashSet cells, int x0, int x1, int y, int z0, int z1) {
		for (int z = z0; z <= z1; z++) {
			if (!rowPresent(cells, x0, x1, y, z)) return false;
		}

		return true;
	}
}
