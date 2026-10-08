package com.howtobuild.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.util.ARGB;

import com.howtobuild.client.BuildSession;
import com.howtobuild.client.ProgressTracker;
import com.howtobuild.config.HologramConfig;
import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.Facing;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.Voxels;
import com.howtobuild.materials.MaterialResolver;

/**
 * Cached, render-ready hologram geometry, rebuilt only when the preview, materials, appearance or build progress
 * change.
 *
 * <ul>
 *     <li>Full blocks contribute only faces that are not hidden by a neighbouring full block, and coplanar faces of the
 *     same colour are merged into rectangles (greedy), so even large shapes produce few quads.</li>
 *     <li>Slabs and stairs are drawn as their real shapes (half boxes, and the correct quarter/half steps for each
 *     stair facing, half and shape), so the preview shows the actual blocks that will be built.</li>
 *     <li>Colours come from each block's map colour; mirrored copies are tinted violet, built blocks green and blocks
 *     in conflict with the world red. Edges are coloured by material role.</li>
 * </ul>
 *
 * Coordinates are floats relative to the preview origin; the renderer translates once per frame and copies them.
 */
public final class RenderMesh {
	private static final float INSET = 0.004F;
	private static final float[] SHADE = {0.86F, 0.74F, 0.86F, 0.74F, 1.0F, 0.6F}; // N, E, S, W, up, down

	/** Quads: 12 floats each (four corners). */
	public final float[] quads;
	public final int[] quadColors;
	public final int quadCount;
	/** Edge segments: 6 floats each. */
	public final float[] edges;
	public final int[] edgeColors;
	public final int edgeCount;

	private RenderMesh(float[] quads, int[] quadColors, int quadCount, float[] edges, int[] edgeColors, int edgeCount) {
		this.quads = quads;
		this.quadColors = quadColors;
		this.quadCount = quadCount;
		this.edges = edges;
		this.edgeColors = edgeColors;
		this.edgeCount = edgeCount;
	}

	private record FaceGroup(int direction, int plane, int color) {
	}

	public static RenderMesh build(BuildSession.Resolved resolved, HologramConfig config, ProgressTracker progress) {
		Builder b = new Builder();
		List<Placement> placements = resolved.result().placements();
		LongOpenHashSet full = new LongOpenHashSet(placements.size());

		for (Placement p : placements) {
			if (p.shape().isFull()) full.add(p.key());
		}

		Map<FaceGroup, LongArrayList> faces = new HashMap<>();
		Map<FaceGroup, Integer> edgeColorByGroup = new HashMap<>();
		int alpha = Math.round(255 * config.opacity);

		for (int i = 0; i < placements.size(); i++) {
			Placement p = placements.get(i);
			int color = color(resolved, i, p, config, progress, alpha);
			int edge = edgeColor(p, config, progress.status(resolved, i));

			if (p.shape().isFull()) {
				for (int d = 0; d < 6; d++) {
					int[] n = NEIGHBOUR[d];

					if (full.contains(Voxels.pack(p.x() + n[0], p.y() + n[1], p.z() + n[2]))) continue;

					int plane = switch (d) {
						case 0 -> p.z();
						case 1 -> p.x() + 1;
						case 2 -> p.z() + 1;
						case 3 -> p.x();
						case 4 -> p.y() + 1;
						default -> p.y();
					};
					FaceGroup group = new FaceGroup(d, plane, shade(color, d));
					faces.computeIfAbsent(group, g -> new LongArrayList()).add(cellKey(p, d));
					edgeColorByGroup.putIfAbsent(group, edge);
				}
			} else {
				for (float[] box : boxes(p.shape())) {
					b.box(p.x() + box[0], p.y() + box[1], p.z() + box[2], p.x() + box[3], p.y() + box[4], p.z() + box[5], color, edge);
				}
			}
		}

		for (Map.Entry<FaceGroup, LongArrayList> entry : faces.entrySet()) {
			FaceGroup g = entry.getKey();
			mergeFaces(b, g.direction(), g.plane(), entry.getValue(), g.color(), edgeColorByGroup.get(g));
		}

		return b.build();
	}

	private static final int[][] NEIGHBOUR = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

	/** The two in-plane coordinates of a face, packed. */
	private static long cellKey(Placement p, int direction) {
		return switch (direction) {
			case 0, 2 -> pack2(p.x(), p.y());
			case 1, 3 -> pack2(p.z(), p.y());
			default -> pack2(p.x(), p.z());
		};
	}

	private static long pack2(int a, int b) {
		return ((long) a << 32) | (b & 0xFFFFFFFFL);
	}

	/** Greedy rectangle merge of unit faces in one plane. */
	private static void mergeFaces(Builder b, int direction, int plane, LongArrayList cells, int color, int edge) {
		LongOpenHashSet remaining = new LongOpenHashSet(cells);
		cells.sort((x, y) -> {
			int c = Integer.compare((int) x, (int) y);
			return c != 0 ? c : Integer.compare((int) (x >> 32), (int) (y >> 32));
		});

		for (int i = 0; i < cells.size(); i++) {
			long start = cells.getLong(i);

			if (!remaining.contains(start)) continue;

			int a0 = (int) (start >> 32);
			int b0 = (int) start;
			int a1 = a0;

			while (remaining.contains(pack2(a1 + 1, b0))) a1++;

			int b1 = b0;

			outer:
			while (true) {
				for (int a = a0; a <= a1; a++) {
					if (!remaining.contains(pack2(a, b1 + 1))) break outer;
				}

				b1++;
			}

			for (int bb = b0; bb <= b1; bb++) {
				for (int a = a0; a <= a1; a++) {
					remaining.remove(pack2(a, bb));
				}
			}

			b.face(direction, plane, a0, b0, a1 + 1, b1 + 1, color, edge);
		}
	}

	/** Sub-boxes (x0, y0, z0, x1, y1, z1 within the block) of a partial block shape. */
	static List<float[]> boxes(BlockShape shape) {
		List<float[]> list = new ArrayList<>();

		if (shape.isSlab()) {
			switch (shape.slabType()) {
				case BOTTOM -> list.add(new float[] {0, 0, 0, 1, 0.5F, 1});
				case TOP -> list.add(new float[] {0, 0.5F, 0, 1, 1, 1});
				case DOUBLE -> list.add(new float[] {0, 0, 0, 1, 1, 1});
			}

			return list;
		}

		boolean top = shape.half() == BlockShape.Half.TOP;
		float baseY0 = top ? 0.5F : 0;
		float stepY0 = top ? 0 : 0.5F;
		list.add(new float[] {0, baseY0, 0, 1, baseY0 + 0.5F, 1});
		Facing f = shape.facing();
		Facing left = f.counterClockwise();

		switch (shape.stairShape()) {
			case STRAIGHT -> list.add(half(f, stepY0));
			case OUTER_LEFT -> list.add(quarter(f, left, stepY0));
			case OUTER_RIGHT -> list.add(quarter(f, left.opposite(), stepY0));
			case INNER_LEFT -> {
				list.add(half(f, stepY0));
				list.add(quarter(f.opposite(), left, stepY0));
			}
			case INNER_RIGHT -> {
				list.add(half(f, stepY0));
				list.add(quarter(f.opposite(), left.opposite(), stepY0));
			}
		}

		return list;
	}

	/** The half of the block on the {@code side} side, at the given step height. */
	private static float[] half(Facing side, float y0) {
		float x0 = side == Facing.EAST ? 0.5F : 0;
		float x1 = side == Facing.WEST ? 0.5F : 1;
		float z0 = side == Facing.SOUTH ? 0.5F : 0;
		float z1 = side == Facing.NORTH ? 0.5F : 1;
		return new float[] {x0, y0, z0, x1, y0 + 0.5F, z1};
	}

	/** The quarter of the block in the corner between two perpendicular sides. */
	private static float[] quarter(Facing a, Facing b, float y0) {
		float[] h1 = half(a, y0);
		float[] h2 = half(b, y0);
		return new float[] {Math.max(h1[0], h2[0]), y0, Math.max(h1[2], h2[2]), Math.min(h1[3], h2[3]), y0 + 0.5F, Math.min(h1[5], h2[5])};
	}

	private static int color(BuildSession.Resolved r, int index, Placement p, HologramConfig config, ProgressTracker progress, int alpha) {
		int rgb = config.colorByMaterial ? lighten(MaterialResolver.tint(r.states()[index]), 0.18F) : roleColor(p.role(), config.color);
		byte status = progress.status(r, index);

		if (status == ProgressTracker.COMPLETED) return ARGB.color(alpha / 3, 0x50, 0xE0, 0x70);
		if (status == ProgressTracker.CONFLICT) return ARGB.color(Math.min(255, alpha + 60), 0xFF, 0x40, 0x40);

		if (p.mirrored()) {
			rgb = blend(rgb, 0xB070FF, 0.45F);

			if (!r.buildable()[index]) alpha = alpha / 2;
		}

		return ARGB.color(alpha, ARGB.red(rgb), ARGB.green(rgb), ARGB.blue(rgb));
	}

	private static int edgeColor(Placement p, HologramConfig config, byte status) {
		if (status == ProgressTracker.COMPLETED) return ARGB.color(120, 0x50, 0xE0, 0x70);
		if (status == ProgressTracker.CONFLICT) return ARGB.color(255, 0xFF, 0x50, 0x50);

		int rgb = roleColor(p.role(), config.color);

		if (p.mirrored()) rgb = blend(rgb, 0xC890FF, 0.6F);

		return ARGB.color(230, ARGB.red(lighten(rgb, 0.35F)), ARGB.green(lighten(rgb, 0.35F)), ARGB.blue(lighten(rgb, 0.35F)));
	}

	/** A distinct hue per role, so details are recognisable even with similar materials. */
	public static int roleColor(MaterialRole role, int primary) {
		return switch (role) {
			case PRIMARY -> primary;
			case TRIM -> 0xFF9F3A;
			case ACCENT -> 0xFFD24D;
			case STEP -> 0x4DE0C8;
			case SUPPORT -> 0x8FA3B8;
			case CAP -> 0xC07CFF;
			case INNER -> 0x5C8CFF;
			case RAIL -> 0xFF5C8A;
			case FLOOR -> 0x9BD45A;
		};
	}

	private static int shade(int argb, int direction) {
		float s = SHADE[direction];
		return ARGB.color(ARGB.alpha(argb), Math.round(ARGB.red(argb) * s), Math.round(ARGB.green(argb) * s), Math.round(ARGB.blue(argb) * s));
	}

	private static int lighten(int rgb, float amount) {
		return blend(rgb, 0xFFFFFF, amount);
	}

	private static int blend(int a, int b, float t) {
		int r = Math.round(ARGB.red(a) + (ARGB.red(b) - ARGB.red(a)) * t);
		int g = Math.round(ARGB.green(a) + (ARGB.green(b) - ARGB.green(a)) * t);
		int bl = Math.round(ARGB.blue(a) + (ARGB.blue(b) - ARGB.blue(a)) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static final class Builder {
		float[] quads = new float[12 * 256];
		int[] quadColors = new int[256];
		int quadCount;
		float[] edges = new float[6 * 512];
		int[] edgeColors = new int[512];
		int edgeCount;

		void quad(float[] c, int color) {
			if (quadCount == quadColors.length) {
				quads = java.util.Arrays.copyOf(quads, quads.length * 2);
				quadColors = java.util.Arrays.copyOf(quadColors, quadColors.length * 2);
			}

			System.arraycopy(c, 0, quads, quadCount * 12, 12);
			quadColors[quadCount++] = color;
		}

		void edge(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
			if (edgeCount == edgeColors.length) {
				edges = java.util.Arrays.copyOf(edges, edges.length * 2);
				edgeColors = java.util.Arrays.copyOf(edgeColors, edgeColors.length * 2);
			}

			int o = edgeCount * 6;
			edges[o] = x0;
			edges[o + 1] = y0;
			edges[o + 2] = z0;
			edges[o + 3] = x1;
			edges[o + 4] = y1;
			edges[o + 5] = z1;
			edgeColors[edgeCount++] = color;
		}

		/** A merged face rectangle [a0, a1] × [b0, b1] in the plane of a face direction. */
		void face(int d, int plane, int a0, int b0, int a1, int b1, int color, int edge) {
			float p = plane + (d == 0 || d == 3 || d == 5 ? INSET : -INSET);
			float[] c;

			switch (d) {
				case 0, 2 -> c = new float[] {a0, b0, p, a1, b0, p, a1, b1, p, a0, b1, p};
				case 1, 3 -> c = new float[] {p, b0, a0, p, b0, a1, p, b1, a1, p, b1, a0};
				default -> c = new float[] {a0, p, b0, a1, p, b0, a1, p, b1, a0, p, b1};
			}

			quad(c, color);

			for (int k = 0; k < 4; k++) {
				int n = (k + 1) & 3;
				edge(c[k * 3], c[k * 3 + 1], c[k * 3 + 2], c[n * 3], c[n * 3 + 1], c[n * 3 + 2], edge);
			}
		}

		void box(float x0, float y0, float z0, float x1, float y1, float z1, int color, int edge) {
			x0 += INSET;
			y0 += INSET;
			z0 += INSET;
			x1 -= INSET;
			y1 -= INSET;
			z1 -= INSET;
			quad(new float[] {x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0}, shade(color, 0));
			quad(new float[] {x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0}, shade(color, 1));
			quad(new float[] {x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1}, shade(color, 2));
			quad(new float[] {x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1}, shade(color, 3));
			quad(new float[] {x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1}, shade(color, 4));
			quad(new float[] {x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0}, shade(color, 5));
			float[][] corners = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};

			for (int k = 0; k < 4; k++) {
				float[] a = corners[k];
				float[] n = corners[(k + 1) & 3];
				edge(a[0], y0, a[2], n[0], y0, n[2], edge);
				edge(a[0], y1, a[2], n[0], y1, n[2], edge);
				edge(a[0], y0, a[2], a[0], y1, a[2], edge);
			}
		}

		RenderMesh build() {
			return new RenderMesh(quads, quadColors, quadCount, edges, edgeColors, edgeCount);
		}
	}
}
