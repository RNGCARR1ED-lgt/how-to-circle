package com.howtobuild.palette;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRef;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.Voxels;

/**
 * Assigns palette blocks to placements, changing <em>only</em> their material (never their position, shape or count).
 *
 * <h2>Exact, rank-based assignment</h2>
 * <ol>
 *     <li>Every candidate (a block, or a group of blocks such as one spiral step) gets a scalar from the chosen
 *     {@link RandomPattern}: hashed white noise, clustered cells, fractal noise, gradients, stripes, rings, distance to
 *     the edge or centre.</li>
 *     <li>Candidates are sorted by that value (ties by a hash) and cut into consecutive runs whose sizes are the
 *     palette's exact shares ({@link WeightedPalette#shares}).</li>
 * </ol>
 * So the counts match the percentages exactly (to the nearest block), the arrangement keeps the pattern's spatial
 * structure, and the same seed always gives the same result.
 */
public final class Randomiser {
	private Randomiser() {
	}

	/**
	 * Randomises the matching placements of a builder in place.
	 *
	 * @param planeNormalAxis axis perpendicular to a flat shape (for edge detection in its plane), or −1 for volumes
	 */
	public static void apply(GeometryBuilder builder, RandomSettings settings, int planeNormalAxis) {
		if (!settings.active()) return;

		List<PaletteEntry> active = settings.palette().active();
		List<Placement> candidates = new ArrayList<>();

		for (Placement p : builder.snapshot()) {
			MaterialRef ref = p.material() >= 0 && p.material() < builder.materials().size() ? builder.materials().get(p.material()) : null;

			// Exact saved states are never changed; only the selected roles are randomised.
			if ((ref == null || !ref.exact()) && settings.roles().contains(p.role())) candidates.add(p);
		}

		if (candidates.isEmpty()) return;

		candidates.sort(com.howtobuild.geometry.GeometryResult.ORDER);
		long seed = settings.seed();
		List<Placement> pool = new ArrayList<>();

		if (settings.protectEdge()) {
			String edge = settings.edgeBlock().isBlank() ? active.getFirst().block() : settings.edgeBlock();
			int edgeIndex = builder.material(MaterialRef.block(edge));

			for (Placement p : candidates) {
				boolean onEdge = isEdge(builder, p, planeNormalAxis);
				boolean varied = Noise.hash01(seed ^ 0x5EED, p.x(), p.y(), p.z()) * 100 < settings.edgeVariation();

				if (onEdge && !varied) builder.put(p.withMaterial(edgeIndex));
				else pool.add(p);
			}
		} else {
			pool = candidates;
		}

		assign(builder, pool, settings, planeNormalAxis);
	}

	/**
	 * Mirror randomisation <i>Independent</i>: the mirrored copies of randomised blocks get their own arrangement (a
	 * derived seed) instead of repeating the original's. With <i>Mirrored</i> (default) the result is returned as is.
	 */
	public static GeometryResult randomiseMirrored(GeometryResult mirrored, RandomSettings settings) {
		if (!settings.active() || settings.mirror() != MirrorRandomisation.INDEPENDENT) return mirrored;

		GeometryBuilder builder = new GeometryBuilder();
		builder.replaceMaterials(mirrored.materials());
		mirrored.placements().forEach(builder::put);
		builder.groups().putAll(mirrored.groups());
		List<Placement> pool = new ArrayList<>();

		for (Placement p : mirrored.placements()) {
			MaterialRef ref = mirrored.material(p);

			if (p.mirrored() && ref != null && !ref.exact() && settings.roles().contains(p.role())) pool.add(p);
		}

		if (pool.isEmpty()) return mirrored;

		pool.sort(GeometryResult.ORDER);
		assign(builder, pool, settings.withSeed(settings.seed() * 31 + 0x4D49), mirrored.planeNormalAxis());
		return new GeometryResult(mirrored.toolId(), builder.snapshot(), mirrored.centreCells(), mirrored.values(), mirrored.warnings(),
				mirrored.planeNormalAxis()).withGuides(mirrored.guides()).withMaterials(builder.materials()).withGroups(mirrored.groups())
				.withAnnotations(mirrored.annotations());
	}

	/**
	 * Assigns the palette of {@code settings} to exactly these placements (exact shares, the pattern's spatial order).
	 * Used by global randomisation and by terrain layers.
	 */
	public static void assign(GeometryBuilder builder, List<Placement> pool, RandomSettings settings, int planeNormalAxis) {
		List<PaletteEntry> active = settings.palette().active();

		if (pool.isEmpty() || active.isEmpty()) return;

		long seed = settings.seed();

		// Group candidates into units (single blocks, or spiral steps / revolutions for symmetric arrangements).
		List<IntArrayList> units = new ArrayList<>();
		List<Placement> representatives = new ArrayList<>();
		Long2IntOpenHashMap unitOf = new Long2IntOpenHashMap();
		long period = period(builder, settings.symmetry());

		for (int i = 0; i < pool.size(); i++) {
			Placement p = pool.get(i);
			long key;

			if (settings.symmetry() != RandomSymmetry.INDEPENDENT && builder.groups().containsKey(p.key())) {
				int group = builder.groups().get(p.key());
				key = Long.MIN_VALUE + (period > 0 ? Math.floorMod(group, period) : group);
			} else {
				key = p.key();
			}

			int unit = unitOf.getOrDefault(key, -1);

			if (unit < 0) {
				unit = units.size();
				unitOf.put(key, unit);
				units.add(new IntArrayList());
				representatives.add(p);
			}

			units.get(unit).add(i);
		}

		double[] field = field(builder, pool, representatives, settings, planeNormalAxis);
		Integer[] order = new Integer[units.size()];

		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}

		Arrays.sort(order, (a, b) -> {
			int c = Double.compare(field[a], field[b]);
			if (c != 0) return c;
			Placement pa = representatives.get(a);
			Placement pb = representatives.get(b);
			return Long.compare(Noise.hash(seed, pa.x(), pa.y(), pa.z()), Noise.hash(seed, pb.x(), pb.y(), pb.z()));
		});

		boolean equal = settings.mode() == RandomMode.FULLY_RANDOM;
		int[] shares = settings.palette().shares(pool.size(), equal);
		int[] materialOf = new int[active.size()];

		for (int i = 0; i < active.size(); i++) {
			materialOf[i] = builder.material(MaterialRef.block(active.get(i).block()));
		}

		// Walk the sorted units; a unit goes to the palette entry whose cumulative range contains its middle block.
		long[] cumulative = new long[shares.length];
		long running = 0;

		for (int i = 0; i < shares.length; i++) {
			running += shares[i];
			cumulative[i] = running;
		}

		long position = 0;
		int entry = 0;

		for (Integer unit : order) {
			IntArrayList members = units.get(unit);
			long middle = position + members.size() / 2;

			while (entry < cumulative.length - 1 && middle >= cumulative[entry]) entry++;

			for (int m = 0; m < members.size(); m++) {
				Placement p = pool.get(members.getInt(m));
				builder.put(p.withMaterial(materialOf[entry]));
			}

			position += members.size();
		}
	}

	private static long period(GeometryBuilder builder, RandomSymmetry symmetry) {
		if (symmetry != RandomSymmetry.PER_REVOLUTION) return 0;

		Double steps = builder.values().get("steps");
		Double revolutions = builder.values().get("revolutions");

		if (steps == null || revolutions == null || revolutions <= 0) return 0;

		return Math.max(1, Math.round(steps / revolutions));
	}

	/** Whether a placement is on the outer edge of its shape (in the shape's plane for flat shapes). */
	static boolean isEdge(GeometryBuilder builder, Placement p, int planeNormalAxis) {
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

		for (int[] d : dirs) {
			int axis = d[0] != 0 ? 0 : d[1] != 0 ? 1 : 2;

			// Flat shapes: only neighbours within the plane count. Volumes: horizontal neighbours (outer walls).
			if (axis == planeNormalAxis || (planeNormalAxis < 0 && axis == 1)) continue;

			if (!builder.has(p.x() + d[0], p.y() + d[1], p.z() + d[2])) return true;
		}

		return false;
	}

	/** The pattern's scalar for each unit (lower values get the earlier palette entries). */
	private static double[] field(GeometryBuilder builder, List<Placement> pool, List<Placement> reps, RandomSettings s, int normal) {
		long seed = s.seed();
		int size = s.clusterSize();
		double[] field = new double[reps.size()];
		int[] bounds = bounds(pool);
		double cx = (bounds[0] + bounds[3] + 1) / 2.0;
		double cy = (bounds[1] + bounds[4] + 1) / 2.0;
		double cz = (bounds[2] + bounds[5] + 1) / 2.0;
		double maxR = Math.max(1, Math.hypot(bounds[3] - bounds[0] + 1, bounds[5] - bounds[2] + 1) / 2);
		Long2IntOpenHashMap depth = s.pattern() == RandomPattern.EDGE_WEIGHTED ? edgeDepth(builder, pool, normal) : null;
		int longAxis = bounds[3] - bounds[0] >= bounds[5] - bounds[2] ? 0 : 2;

		if (normal == longAxis) longAxis = 1;

		for (int i = 0; i < reps.size(); i++) {
			Placement p = reps.get(i);
			int x = p.x();
			int y = p.y();
			int z = p.z();
			double white = Noise.hash01(seed, x, y, z);

			field[i] = switch (s.pattern()) {
				case COMPLETELY_RANDOM -> white;
				case SUBTLE -> 0.35 * norm(Noise.fbm(seed, x / 3.0, y / 3.0, z / 3.0, 2, 0.5, 2)) + 0.65 * white;
				case NATURAL -> 0.75 * norm(Noise.fbm(seed, x / 6.0, y / 6.0, z / 6.0, 3, 0.5, 2)) + 0.25 * white;
				case CLUSTERED -> Noise.cellular(seed, x, y, z, size) + 0.001 * white;
				case PATCHY -> contrast(norm(Noise.fbm(seed, x / (size * 1.7), y / (size * 1.7), z / (size * 1.7), 2, 0.45, 2.1)), 2.5) + 0.05 * white;
				case GRADIENT -> {
					double coord = longAxis == 0 ? x : longAxis == 1 ? y : z;
					double lo = bounds[longAxis];
					double hi = bounds[longAxis + 3];
					yield (coord - lo) / Math.max(1, hi - lo) + 0.25 * (white - 0.5) + 0.15 * Noise.fbm(seed, x / 5.0, y / 5.0, z / 5.0, 2, 0.5, 2);
				}
				case EDGE_WEIGHTED -> -depth.getOrDefault(p.key(), 0) + 1.5 * white;
				case CENTER_WEIGHTED -> Math.hypot(x + 0.5 - cx, z + 0.5 - cz) / maxR + 0.3 * white;
				case STRIPED -> Noise.hash01(seed, Math.floorDiv(x + z, size), 0, 0) + 0.001 * white;
				case RADIAL -> Noise.hash01(seed, (int) Math.floor(Math.hypot(x + 0.5 - cx, z + 0.5 - cz) / size), 7, 0) + 0.001 * white;
				case NOISE, CUSTOM -> {
					double n = norm(Noise.fbm(seed, x / s.noiseScale(), y / s.noiseScale(), z / s.noiseScale(), s.octaves(), 0.5, 2));
					n = contrast(n, s.contrast()) - s.threshold() * 0.5;
					yield s.noiseStrength() * n + (1 - s.noiseStrength()) * white;
				}
			};
		}

		return field;
	}

	private static double norm(double v) {
		return 0.5 + 0.5 * Math.max(-1, Math.min(1, v * 1.4));
	}

	private static double contrast(double v, double c) {
		return 0.5 + (v - 0.5) * c;
	}

	private static int[] bounds(List<Placement> pool) {
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

		for (Placement p : pool) {
			b[0] = Math.min(b[0], p.x());
			b[1] = Math.min(b[1], p.y());
			b[2] = Math.min(b[2], p.z());
			b[3] = Math.max(b[3], p.x());
			b[4] = Math.max(b[4], p.y());
			b[5] = Math.max(b[5], p.z());
		}

		return b;
	}

	/** Distance (in blocks, through the shape) from each block to the nearest edge block. */
	private static Long2IntOpenHashMap edgeDepth(GeometryBuilder builder, List<Placement> pool, int normal) {
		Long2IntOpenHashMap depth = new Long2IntOpenHashMap();
		depth.defaultReturnValue(-1);
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

		for (Placement p : pool) {
			if (isEdge(builder, p, normal)) {
				depth.put(p.key(), 0);
				queue.enqueue(p.key());
			}
		}

		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

		while (!queue.isEmpty()) {
			long key = queue.dequeueLong();
			int d = depth.get(key);
			int x = Voxels.x(key);
			int y = Voxels.y(key);
			int z = Voxels.z(key);

			for (int[] dir : dirs) {
				long next = Voxels.pack(x + dir[0], y + dir[1], z + dir[2]);

				if (builder.cells().containsKey(next) && depth.get(next) < 0) {
					depth.put(next, d + 1);
					queue.enqueue(next);
				}
			}
		}

		return depth;
	}
}
