package com.howtobuild.saves;

import java.util.List;

/**
 * A saved build: the exact final blocks (positions and full block states) plus enough metadata to place and describe
 * it. Positions are relative to the origin (by default the build's centre block), so the saved centre (1×1 or 2×2) is
 * preserved exactly.
 *
 * @param name         display name
 * @param author       optional author
 * @param description  optional description
 * @param tool         the tool that generated it ("capture" for world captures)
 * @param created      creation time (epoch millis)
 * @param origin       what the origin is (centre, minimum corner, …)
 * @param centreCells  centre cells relative to the origin, {x, y, z} each
 * @param offset       the X / Y / Z offset that was set when saving
 * @param seed         randomisation seed (informational)
 * @param settings     generator settings as JSON (informational; the procedural preset when {@code procedural})
 * @param procedural   a procedural preset: {@code settings} is authoritative and there are no blocks
 * @param palette      distinct exact block states; blocks refer to them by index
 * @param blocks       x, y, z, palette index for each block (4 ints per block)
 */
public record BuildFile(String name, String author, String description, String tool, long created, Origin origin, List<int[]> centreCells,
		int[] offset, long seed, String settings, boolean procedural, List<String> palette, int[] blocks) {
	/** What a saved build's coordinates are relative to. */
	public enum Origin {
		CENTER,
		MIN_CORNER,
		SELECTED_POINT,
		CUSTOM
	}

	public BuildFile {
		name = name == null ? "" : name;
		author = author == null ? "" : author;
		description = description == null ? "" : description;
		tool = tool == null ? "" : tool;
		settings = settings == null ? "" : settings;
		centreCells = centreCells == null ? List.of() : List.copyOf(centreCells);
		palette = palette == null ? List.of() : List.copyOf(palette);
		blocks = blocks == null ? new int[0] : blocks;
		offset = offset == null || offset.length != 3 ? new int[3] : offset;
		origin = origin == null ? Origin.CENTER : origin;
	}

	public int blockCount() {
		return blocks.length / 4;
	}

	/** Bounds {minX, minY, minZ, maxX, maxY, maxZ} of the blocks, or zeros when empty. */
	public int[] bounds() {
		if (blockCount() == 0) return new int[6];

		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

		for (int i = 0; i < blocks.length; i += 4) {
			for (int a = 0; a < 3; a++) {
				b[a] = Math.min(b[a], blocks[i + a]);
				b[a + 3] = Math.max(b[a + 3], blocks[i + a]);
			}
		}

		return b;
	}

	public int[] size() {
		int[] b = bounds();
		return blockCount() == 0 ? new int[3] : new int[] {b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1};
	}
}
