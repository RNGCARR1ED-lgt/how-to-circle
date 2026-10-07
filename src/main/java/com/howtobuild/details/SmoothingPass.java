package com.howtobuild.details;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.Box;
import com.howtobuild.geometry.Facing;
import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.geometry.Voxels;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.ToolSettings;

/**
 * Smooths stepped curves with stairs or slabs, using whichever of those material types the player selected.
 *
 * <ul>
 *     <li><b>Outside, facing up</b> (dome and sphere tops, the outside of arches): an empty outside cell resting on the
 *     structure, with the structure beside it, becomes a bottom stair climbing towards that side (or a bottom slab).</li>
 *     <li><b>Inside, facing down</b> (arch and vault ceilings): an enclosed empty cell under the structure, with the
 *     structure beside it, becomes an upside-down stair (or a top slab).</li>
 * </ul>
 *
 * "Outside" is found by flood-filling the empty space from the faces of the bounding box that the tool declares open
 * (e.g. not the ends of a corridor, not under a dome), so enclosed interiors are told apart from the exterior. The new
 * blocks use the role of the block they lean on, so they come from the same material family.
 */
public final class SmoothingPass {
	/** Larger bounding volumes are not smoothed (the flood fill would be too expensive). */
	public static final long MAX_VOLUME = 8_000_000L;

	public static final int OPEN_NEG_X = 1;
	public static final int OPEN_POS_X = 2;
	public static final int OPEN_NEG_Y = 4;
	public static final int OPEN_POS_Y = 8;
	public static final int OPEN_NEG_Z = 16;
	public static final int OPEN_POS_Z = 32;
	public static final int OPEN_ALL = 63;

	/** Tools that support {@link DetailFeature#SMOOTH_CURVES}. */
	public interface Smoothable {
		/** Which faces of the bounding box the outside air may enter from (bit mask of {@code OPEN_*}). */
		int smoothingOpenFaces(ToolSettings settings);
	}

	private SmoothingPass() {
	}

	public static void apply(GeometryBuilder builder, GenerationContext context, int openFaces) {
		boolean stairs = context.allows(ShapeKind.STAIRS);
		boolean slabs = context.allows(ShapeKind.SLABS);

		if ((!stairs && !slabs) || builder.isEmpty()) return;

		Box bounds = null;

		for (Placement p : builder.cells().values()) {
			bounds = bounds == null ? Box.of(p.x(), p.y(), p.z()) : bounds.include(p.x(), p.y(), p.z());
		}

		Box region = bounds.offset(0, 0, 0);
		region = new Box(region.minX() - 1, region.minY() - 1, region.minZ() - 1, region.maxX() + 1, region.maxY() + 1, region.maxZ() + 1);

		if (region.volume() > MAX_VOLUME) {
			builder.warn("Shape too large to smooth curves (" + region.volume() + " cells); smoothing skipped.");
			return;
		}

		LongOpenHashSet outside = floodOutside(builder, bounds, region, openFaces);
		List<Placement> additions = new ArrayList<>();

		for (int y = bounds.minY(); y <= bounds.maxY() + 1; y++) {
			for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
				for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
					if (builder.has(x, y, z)) continue;

					boolean isOutside = outside.contains(Voxels.pack(x, y, z));

					if (isOutside) {
						Placement below = builder.get(x, y - 1, z);

						if (below == null || !below.shape().isFull()) continue;

						Facing side = solidSide(builder, x, y, z, true);

						if (side == null) continue;

						BlockShape shape = stairs ? BlockShape.stairs(side, BlockShape.Half.BOTTOM) : BlockShape.BOTTOM_SLAB;
						additions.add(new Placement(x, y, z, below.role(), shape, 0, false));
					} else if (y <= bounds.maxY()) {
						Placement above = builder.get(x, y + 1, z);

						if (above == null || !above.shape().isFull()) continue;

						Facing side = solidSide(builder, x, y, z, false);

						if (side == null) continue;

						BlockShape shape = stairs ? BlockShape.stairs(side, BlockShape.Half.TOP) : BlockShape.TOP_SLAB;
						additions.add(new Placement(x, y, z, above.role(), shape, 0, false));
					}
				}
			}
		}

		for (Placement p : additions) {
			builder.put(p);
		}
	}

	/**
	 * The single horizontal side with a full block (and, for upward smoothing, empty space above it would be a cliff
	 * rather than a step, so require the block above that neighbour to also be solid or the cell above us to be empty).
	 * Cells enclosed on two opposite sides are skipped.
	 */
	private static Facing solidSide(GeometryBuilder builder, int x, int y, int z, boolean upward) {
		Facing found = null;
		int count = 0;

		for (Facing f : Facing.values()) {
			Placement n = builder.get(x + f.dx, y, z + f.dz);

			if (n != null && n.shape().isFull()) {
				if (found != null && found.opposite() == f) return null;
				if (found == null) found = f;
				count++;
			}
		}

		if (found == null || count > 2) return null;

		if (upward && builder.has(x, y + 1, z)) return null;

		return found;
	}

	private static LongOpenHashSet floodOutside(GeometryBuilder builder, Box bounds, Box region, int openFaces) {
		LongOpenHashSet outside = new LongOpenHashSet();
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

		for (int y = region.minY(); y <= region.maxY(); y++) {
			for (int z = region.minZ(); z <= region.maxZ(); z++) {
				for (int x = region.minX(); x <= region.maxX(); x++) {
					boolean onOpenFace = (x == region.minX() && (openFaces & OPEN_NEG_X) != 0)
							|| (x == region.maxX() && (openFaces & OPEN_POS_X) != 0)
							|| (y == region.minY() && (openFaces & OPEN_NEG_Y) != 0)
							|| (y == region.maxY() && (openFaces & OPEN_POS_Y) != 0)
							|| (z == region.minZ() && (openFaces & OPEN_NEG_Z) != 0)
							|| (z == region.maxZ() && (openFaces & OPEN_POS_Z) != 0);

					if (onOpenFace && !builder.has(x, y, z)) {
						long key = Voxels.pack(x, y, z);

						if (outside.add(key)) queue.enqueue(key);
					}
				}
			}
		}

		int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

		while (!queue.isEmpty()) {
			long key = queue.dequeueLong();
			int x = Voxels.x(key);
			int y = Voxels.y(key);
			int z = Voxels.z(key);

			for (int[] s : steps) {
				int nx = x + s[0];
				int ny = y + s[1];
				int nz = z + s[2];

				// Stay inside the shape's own bounds (plus nothing): the margin layer is only entered through open faces.
				if (nx < bounds.minX() || nx > bounds.maxX() || ny < bounds.minY() || ny > bounds.maxY() + 1
						|| nz < bounds.minZ() || nz > bounds.maxZ()) {
					continue;
				}

				if (builder.has(nx, ny, nz)) continue;

				long next = Voxels.pack(nx, ny, nz);

				if (outside.add(next)) queue.enqueue(next);
			}
		}

		return outside;
	}
}
