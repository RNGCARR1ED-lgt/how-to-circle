package com.howtobuild.tools.impl;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;

import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.GeometryCenter;
import com.howtobuild.geometry.Guide;
import com.howtobuild.geometry.Mask2D;
import com.howtobuild.geometry.MaterialRef;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.palette.Noise;
import com.howtobuild.palette.RandomPattern;
import com.howtobuild.palette.RandomSettings;
import com.howtobuild.palette.Randomiser;
import com.howtobuild.palette.WeightedPalette;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.HeightMap;
import com.howtobuild.tools.TerrainLayer;
import com.howtobuild.tools.ToolParameter;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.ValidationResult;
import com.howtobuild.tools.capability.CommandBuildable;
import com.howtobuild.tools.capability.Dimensionable;
import com.howtobuild.tools.capability.MaterialAssignable;
import com.howtobuild.tools.capability.Mirrorable;

/**
 * Terrain / terraforming: natural-looking ground inside an exact region, never touching a protected area.
 *
 * <pre>
 * region mask ─► protected mask ─► height field (template + variation + detail + erosion) ─► smoothing ─► slope
 * limiting ─► edge blending (outer boundary and protected area) ─► columns ─► material layers (randomised palettes)
 * </pre>
 *
 * <ul>
 *     <li><b>Heights</b> are relative to the anchor's Y (the base). The field is pure deterministic noise from the seed.</li>
 *     <li><b>Quality</b> decides smoothing passes and noise octaves; a slope limiter removes one-block spikes and
 *     impossible cliffs (unless the Cliff template or a cliff boundary is chosen).</li>
 *     <li><b>Blending</b>: near the outer boundary and around the protected area the surface eases towards the existing
 *     ground (sampled from the world before generation) or the base, so there are no walls or cut edges.</li>
 *     <li><b>Protected area</b>: its columns are declared protected; the validator guarantees no block is ever
 *     generated in them, at any height.</li>
 * </ul>
 */
public final class TerrainTool implements BuildTool, Mirrorable, MaterialAssignable, Dimensionable, CommandBuildable {
	public enum Template {
		FLAT,
		ROLLING_HILLS,
		HILL,
		TWIN_HILLS,
		LONG_RIDGE,
		LOW_ROLLING,
		MOUNTAIN,
		ROCKY_MOUNTAIN,
		JAGGED_MOUNTAIN,
		LAYERED_MOUNTAIN,
		VOLCANIC,
		ALPINE,
		MOUNTAIN_RANGE,
		FOOTHILLS,
		VALLEY,
		RIDGE,
		PLATEAU,
		CRATER,
		BASIN,
		ISLAND,
		CLIFF,
		CUSTOM
	}

	public enum Variation {
		NONE,
		ROLLING,
		NOISE,
		RIDGED
	}

	public enum Quality {
		LOW,
		MEDIUM,
		HIGH,
		VERY_HIGH
	}

	public enum Boundary {
		NATURAL,
		SMOOTH,
		CLIFF
	}

	public enum Blend {
		SHARP,
		SMOOTH,
		NATURAL,
		VERY_SMOOTH
	}

	@Override
	public String id() {
		return "terrain";
	}

	@Override
	public List<ToolParameter> parameters() {
		return List.of(
				ToolParameter.choice("region_shape", RegionShape.CIRCLE),
				ToolParameter.integer("width", 4, 512, 64),
				ToolParameter.integer("length", 4, 512, 64).visibleWhen(s -> !s.getEnum("region_shape", RegionShape.class).uniform()),
				ToolParameter.choice("template", Template.MOUNTAIN),
				ToolParameter.choice("variation", Variation.ROLLING),
				ToolParameter.integer("min_height", 0, 256, 0),
				ToolParameter.integer("max_height", 1, 320, 40),
				ToolParameter.integer("roughness", 0, 100, 40),
				ToolParameter.integer("slope", 1, 8, 2),
				ToolParameter.integer("variation_strength", 0, 100, 30),
				ToolParameter.choice("quality", Quality.HIGH),
				ToolParameter.integer("seed", 0, 999_999, 1),
				ToolParameter.integer("fill_depth", 0, 256, 0).advanced(),
				ToolParameter.integer("scale", 8, 512, 48).section("procedural").advanced(),
				ToolParameter.integer("octaves", 1, 8, 5).section("procedural").advanced(),
				ToolParameter.integer("persistence", 10, 90, 50).section("procedural").advanced(),
				ToolParameter.integer("lacunarity", 15, 40, 20).section("procedural").advanced(),
				ToolParameter.integer("smoothing", 0, 10, 2).section("procedural").advanced(),
				ToolParameter.integer("ridge_strength", 0, 100, 30).section("procedural").advanced(),
				ToolParameter.integer("erosion", 0, 100, 30).section("procedural").advanced(),
				ToolParameter.integer("valley_strength", 0, 100, 20).section("procedural").advanced(),
				ToolParameter.choice("boundary", Boundary.NATURAL).section("edges"),
				ToolParameter.integer("boundary_blend", 0, 64, 8).section("edges"),
				ToolParameter.bool("protect", false).section("protected"),
				ToolParameter.choice("protected_shape", RegionShape.CIRCLE).section("protected").visibleWhen(s -> s.getBool("protect")),
				ToolParameter.integer("protected_width", 1, 512, 20).section("protected").visibleWhen(s -> s.getBool("protect")),
				ToolParameter.integer("protected_length", 1, 512, 20).section("protected")
						.visibleWhen(s -> s.getBool("protect") && !s.getEnum("protected_shape", RegionShape.class).uniform()),
				ToolParameter.integer("protected_offset_x", -256, 256, 0).section("protected").visibleWhen(s -> s.getBool("protect")),
				ToolParameter.integer("protected_offset_z", -256, 256, 0).section("protected").visibleWhen(s -> s.getBool("protect")),
				ToolParameter.choice("blend", Blend.NATURAL).section("protected").visibleWhen(s -> s.getBool("protect")),
				ToolParameter.integer("blend_radius", 0, 32, 4).section("protected").visibleWhen(s -> s.getBool("protect")),
				ToolParameter.integer("contour_interval", 0, 64, 5).section("display"),
				ToolParameter.bool("height_labels", true).section("display"));
	}

	private static int length(ToolSettings s) {
		return s.getEnum("region_shape", RegionShape.class).length(s.getInt("width"), s.getInt("length"));
	}

	@Override
	public long estimateBlocks(ToolSettings s) {
		long columns = (long) s.getInt("width") * length(s);
		int depth = s.getInt("fill_depth");
		return columns * (depth > 0 ? Math.min(depth, s.getInt("max_height") + 1) : s.getInt("max_height") + 1);
	}

	@Override
	public ValidationResult validate(ToolSettings s, GenerationContext ctx) {
		ValidationResult r = new ValidationResult();
		r.errorIf(s.getInt("max_height") < s.getInt("min_height"), "Maximum height must not be below the minimum height.");

		for (TerrainLayer layer : ctx.palettes().terrainLayers()) {
			r.errorIf(layer.palette().isEmpty(), "Every terrain layer needs at least one block (Terrain tab).");
		}

		return r;
	}

	/** Terrain materials come from its layers, not from the global randomisation. */
	@Override
	public RandomSettings randomisation(ToolSettings settings, GenerationContext context) {
		return RandomSettings.OFF;
	}

	/** The region mask in footprint space and its centre. */
	public static Mask2D regionMask(ToolSettings s) {
		return s.getEnum("region_shape", RegionShape.class).mask(s.getInt("width"), length(s));
	}

	/**
	 * The height of every region column (relative to the base), {@code -1} for columns outside the region or inside
	 * the protected area. Index {@code v * width + u}.
	 */
	public static int[] heightField(ToolSettings s, GenerationContext ctx, GeometryCenter centre, Mask2D region, boolean[] protectedCells) {
		int w = region.width();
		int l = region.height();
		long seed = s.getInt("seed") * 0x2545F4914F6CDD1DL + 17;
		Template template = s.getEnum("template", Template.class);
		Quality quality = s.getEnum("quality", Quality.class);
		int octaves = Math.min(s.getInt("octaves"), switch (quality) {
			case LOW -> 2;
			case MEDIUM -> 4;
			case HIGH -> 6;
			case VERY_HIGH -> 8;
		});
		double persistence = s.getInt("persistence") / 100.0;
		double lacunarity = s.getInt("lacunarity") / 10.0;
		double scale = s.getInt("scale");
		double roughness = s.getInt("roughness") / 100.0;
		double variation = s.getInt("variation_strength") / 100.0;
		double ridge = s.getInt("ridge_strength") / 100.0;
		double erosion = s.getInt("erosion") / 100.0;
		double valley = s.getInt("valley_strength") / 100.0;
		int minH = s.getInt("min_height");
		int maxH = s.getInt("max_height");
		double[] field = new double[w * l];

		for (int v = 0; v < l; v++) {
			for (int u = 0; u < w; u++) {
				if (!region.contains(u, v)) continue;

				// Normalised coordinates: -1..1 across the region, so templates scale with its size.
				double nx = (2.0 * u + 1 - w) / w;
				double nz = (2.0 * v + 1 - l) / l;
				int x = centre.minX() + u;
				int z = centre.minZ() + v;
				double f = template(template, nx, nz, x, z, seed, scale, octaves, persistence, lacunarity, ridge);
				f += variation * switch (s.getEnum("variation", Variation.class)) {
					case NONE -> 0;
					case ROLLING -> 0.18 * Noise.fbm(seed + 11, x / (scale * 1.5), 0.5, z / (scale * 1.5), 2, 0.5, 2);
					case NOISE -> 0.15 * Noise.fbm(seed + 12, x / (scale * 0.6), 0.5, z / (scale * 0.6), octaves, persistence, lacunarity);
					case RIDGED -> 0.25 * (Noise.ridged(seed + 13, x / scale, z / scale, octaves, persistence, lacunarity) - 0.5);
				};
				f += roughness * 0.08 * Noise.fbm(seed + 21, x / 6.0, 0.5, z / 6.0, Math.max(1, octaves - 2), 0.5, 2);
				// Erosion-like channels and valleys: deepen where the noise crosses zero (natural drainage lines).
				double channel = 1 - Math.abs(Noise.perlin(seed + 31, x / (scale * 0.8), z / (scale * 0.8)));
				f -= erosion * 0.12 * Math.pow(channel, 8) * Math.min(1, f * 2);
				double vall = 1 - Math.abs(Noise.perlin(seed + 41, x / (scale * 2.0), z / (scale * 2.0)));
				f -= valley * 0.2 * Math.pow(vall, 6) * Math.min(1, f * 1.5);
				field[v * w + u] = minH + (maxH - minH) * Math.max(0, Math.min(1, f));
			}
		}

		int passes = s.getInt("smoothing") + switch (quality) {
			case LOW -> 0;
			case MEDIUM -> 1;
			case HIGH -> 2;
			case VERY_HIGH -> 3;
		};

		for (int i = 0; i < passes; i++) {
			field = smooth(field, region, w, l);
		}

		boolean cliffs = template == Template.CLIFF;

		if (!cliffs) limitSlopes(field, region, w, l, s.getInt("slope"));

		HeightMap ground = ctx.palettes().heights();
		blendBoundary(field, region, centre, w, l, s, ground, minH);
		blendProtected(field, region, centre, w, l, s, ground, protectedCells, minH);

		int[] heights = new int[w * l];

		for (int i = 0; i < heights.length; i++) {
			int u = i % w;
			int v = i / w;
			heights[i] = !region.contains(u, v) || protectedCells[i] ? -1 : (int) Math.round(Math.max(0, field[i]));
		}

		return heights;
	}

	private static double template(Template t, double nx, double nz, int x, int z, long seed, double scale, int octaves, double persistence,
			double lacunarity, double ridgeStrength) {
		double r = Math.sqrt(nx * nx + nz * nz);
		double bell = bell(r);
		double fbm = Noise.fbm(seed, x / scale, 0.5, z / scale, octaves, persistence, lacunarity);
		double ridged = Noise.ridged(seed + 5, x / scale, z / scale, octaves, persistence, lacunarity);

		return switch (t) {
			case FLAT -> 0.02 + 0.02 * fbm;
			case ROLLING_HILLS -> 0.35 + 0.3 * fbm + 0.1 * Noise.fbm(seed + 2, x / (scale * 0.5), 0.5, z / (scale * 0.5), 2, 0.5, 2);
			case HILL -> bell * (0.9 + 0.1 * fbm);
			case TWIN_HILLS -> Math.max(bell(Math.hypot(nx + 0.4, nz) / 0.6), bell(Math.hypot(nx - 0.4, nz) / 0.6)) * (0.85 + 0.15 * fbm);
			case LONG_RIDGE -> bell(Math.abs(nz) / 0.6) * (1 - Math.pow(Math.abs(nx), 4)) * (0.85 + 0.15 * fbm);
			case LOW_ROLLING -> 0.15 + 0.15 * fbm;
			case MOUNTAIN -> Math.pow(bell, 1.4) * (0.85 + 0.15 * fbm);
			case ROCKY_MOUNTAIN -> bell * (0.7 + 0.3 * ridged + 0.1 * fbm);
			case JAGGED_MOUNTAIN -> bell * (0.55 + 0.45 * Noise.ridged(seed + 6, x / (scale * 0.5), z / (scale * 0.5), octaves, persistence, lacunarity));
			case LAYERED_MOUNTAIN -> Math.floor(Math.pow(bell, 1.2) * (0.9 + 0.1 * fbm) * 8) / 8.0;
			case VOLCANIC -> {
				double cone = Math.pow(bell, 1.1);
				yield r < 0.18 ? cone - (0.18 - r) * 2.5 : cone * (0.92 + 0.08 * fbm);
			}
			case ALPINE -> Math.pow(bell, 1.8) * (0.8 + ridgeStrength * 0.4 * ridged);
			case MOUNTAIN_RANGE -> bell(Math.abs(nz) / 0.7) * (0.45 + 0.55 * Noise.ridged(seed + 7, x / (scale * 0.7), z / (scale * 1.4), octaves, persistence,
					lacunarity));
			case FOOTHILLS -> 0.3 * bell + 0.22 * (fbm + 1) * 0.5 + 0.1;
			case VALLEY -> Math.min(1, Math.pow(Math.abs(nz), 1.3)) * (0.85 + 0.15 * fbm);
			case RIDGE -> Math.pow(Math.max(0, 1 - Math.abs(nz)), 1.6) * (0.75 + 0.25 * ridged);
			case PLATEAU -> smoothstep(0.85, 0.55, r) * (0.92 + 0.08 * fbm);
			case CRATER -> Math.exp(-Math.pow((r - 0.6) / 0.22, 2)) * (0.9 + 0.1 * fbm) + (r < 0.6 ? 0.08 : 0);
			case BASIN -> Math.min(1, r * r) * (0.85 + 0.15 * fbm);
			case ISLAND -> Math.max(0, Math.pow(Math.max(0, 1 - r), 1.2) + 0.15 * fbm);
			case CLIFF -> smoothstep(-0.08, 0.08, nx) * (0.85 + 0.1 * fbm) + 0.05;
			case CUSTOM -> Math.max(0, 0.5 + 0.5 * fbm * (1 - ridgeStrength) + ridgeStrength * (ridged - 0.3));
		};
	}

	/** A smooth bump: 1 in the middle, 0 at r = 1 and beyond. */
	private static double bell(double r) {
		if (r >= 1) return 0;
		double t = 1 - r * r;
		return t * t;
	}

	private static double smoothstep(double edge0, double edge1, double x) {
		double t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
		return t * t * (3 - 2 * t);
	}

	private static double[] smooth(double[] field, Mask2D region, int w, int l) {
		double[] out = field.clone();

		for (int v = 0; v < l; v++) {
			for (int u = 0; u < w; u++) {
				if (!region.contains(u, v)) continue;

				double sum = 0;
				double weight = 0;

				for (int dv = -1; dv <= 1; dv++) {
					for (int du = -1; du <= 1; du++) {
						if (!region.contains(u + du, v + dv)) continue;

						double k = du == 0 && dv == 0 ? 4 : (du == 0 || dv == 0 ? 2 : 1);
						sum += field[(v + dv) * w + u + du] * k;
						weight += k;
					}
				}

				out[v * w + u] = sum / weight;
			}
		}

		return out;
	}

	/** Lowers any column more than {@code maxStep} above a neighbour (removes spikes and impossible cliffs). */
	private static void limitSlopes(double[] field, Mask2D region, int w, int l, int maxStep) {
		for (int iteration = 0; iteration < 64; iteration++) {
			boolean changed = false;

			for (int v = 0; v < l; v++) {
				for (int u = 0; u < w; u++) {
					if (!region.contains(u, v)) continue;

					double lowest = Double.MAX_VALUE;

					for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						if (region.contains(u + d[0], v + d[1])) lowest = Math.min(lowest, field[(v + d[1]) * w + u + d[0]]);
					}

					int i = v * w + u;

					if (lowest != Double.MAX_VALUE && field[i] > lowest + maxStep) {
						field[i] = lowest + maxStep;
						changed = true;
					}
				}
			}

			if (!changed) return;
		}
	}

	/** Eases the surface towards the existing ground (or the base) near the region's outer edge. */
	private static void blendBoundary(double[] field, Mask2D region, GeometryCenter centre, int w, int l, ToolSettings s, HeightMap ground, int minH) {
		Boundary boundary = s.getEnum("boundary", Boundary.class);
		int width = s.getInt("boundary_blend");

		if (boundary == Boundary.CLIFF || width <= 0) return;

		int[] distance = distanceFromOutside(region, w, l);

		for (int i = 0; i < field.length; i++) {
			int u = i % w;
			int v = i / w;

			if (!region.contains(u, v) || distance[i] > width) continue;

			double t = Math.max(0, Math.min(1, (distance[i] - 0.5) / width));
			double k = boundary == Boundary.SMOOTH ? t * t * t * (t * (t * 6 - 15) + 10) : t * t * (3 - 2 * t);
			double target = groundAt(ground, centre.minX() + u, centre.minZ() + v, minH);
			field[i] = target + (field[i] - target) * k;
		}
	}

	/** Shapes the terrain around the protected area: heights ease towards the protected boundary's existing ground. */
	private static void blendProtected(double[] field, Mask2D region, GeometryCenter centre, int w, int l, ToolSettings s, HeightMap ground,
			boolean[] protectedCells, int minH) {
		int radius = s.getInt("blend_radius");
		Blend blend = s.getEnum("blend", Blend.class);

		if (!s.getBool("protect") || radius <= 0 || blend == Blend.SHARP) return;

		int reach = blend == Blend.VERY_SMOOTH ? radius * 2 : radius;
		// Multi-source BFS from the protected cells: distance and the ground height at the nearest protected boundary.
		int[] distance = new int[w * l];
		double[] target = new double[w * l];
		java.util.Arrays.fill(distance, Integer.MAX_VALUE);
		IntArrayFIFOQueue queue = new IntArrayFIFOQueue();

		for (int i = 0; i < protectedCells.length; i++) {
			if (!protectedCells[i]) continue;

			distance[i] = 0;
			target[i] = groundAt(ground, centre.minX() + i % w, centre.minZ() + i / w, minH);
			queue.enqueue(i);
		}

		while (!queue.isEmpty()) {
			int i = queue.dequeueInt();
			int u = i % w;
			int v = i / w;

			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int nu = u + d[0];
				int nv = v + d[1];

				if (nu < 0 || nv < 0 || nu >= w || nv >= l) continue;

				int n = nv * w + nu;

				if (distance[n] > distance[i] + 1) {
					distance[n] = distance[i] + 1;
					target[n] = target[i];

					if (distance[n] < reach) queue.enqueue(n);
				}
			}
		}

		long seed = s.getInt("seed") * 31L + 7;

		for (int i = 0; i < field.length; i++) {
			if (protectedCells[i] || !region.contains(i % w, i / w) || distance[i] > reach) continue;

			double t = Math.max(0, Math.min(1, (distance[i] - 0.5) / reach));

			if (blend == Blend.NATURAL) {
				t = Math.max(0, Math.min(1, t + 0.15 * Noise.perlin(seed, (centre.minX() + i % w) / 5.0, (centre.minZ() + i / w) / 5.0)));
			}

			double k = blend == Blend.SMOOTH ? t : t * t * (3 - 2 * t);
			field[i] = target[i] + (field[i] - target[i]) * k;
		}
	}

	private static double groundAt(HeightMap ground, int x, int z, int minH) {
		int h = ground.heightAt(x, z);
		return h == HeightMap.UNKNOWN ? minH : Math.max(0, h);
	}

	/** Steps (4-neighbour) from each region cell to the nearest cell outside the region. */
	private static int[] distanceFromOutside(Mask2D region, int w, int l) {
		int[] distance = new int[w * l];
		java.util.Arrays.fill(distance, Integer.MAX_VALUE);
		IntArrayFIFOQueue queue = new IntArrayFIFOQueue();

		for (int v = 0; v < l; v++) {
			for (int u = 0; u < w; u++) {
				if (!region.contains(u, v)) continue;

				if (!region.contains(u - 1, v) || !region.contains(u + 1, v) || !region.contains(u, v - 1) || !region.contains(u, v + 1)) {
					distance[v * w + u] = 1;
					queue.enqueue(v * w + u);
				}
			}
		}

		while (!queue.isEmpty()) {
			int i = queue.dequeueInt();
			int u = i % w;
			int v = i / w;

			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int nu = u + d[0];
				int nv = v + d[1];

				if (!region.contains(nu, nv)) continue;

				int n = nv * w + nu;

				if (distance[n] > distance[i] + 1) {
					distance[n] = distance[i] + 1;
					queue.enqueue(n);
				}
			}
		}

		return distance;
	}

	/** Protected cells in region space ({@code v * width + u}). */
	public static boolean[] protectedCells(ToolSettings s, GeometryCenter centre, int w, int l) {
		boolean[] cells = new boolean[w * l];

		if (!s.getBool("protect")) return cells;

		RegionShape shape = s.getEnum("protected_shape", RegionShape.class);
		int pw = s.getInt("protected_width");
		int pl = shape.length(pw, s.getInt("protected_length"));
		Mask2D mask = shape.mask(pw, pl);
		GeometryCenter pc = GeometryCenter.of(pw, pl, centre.alignX(), centre.alignZ());
		int ox = s.getInt("protected_offset_x");
		int oz = s.getInt("protected_offset_z");

		for (int v = 0; v < pl; v++) {
			for (int u = 0; u < pw; u++) {
				if (!mask.contains(u, v)) continue;

				int ru = pc.minX() + u + ox - centre.minX();
				int rv = pc.minZ() + v + oz - centre.minZ();

				if (ru >= 0 && rv >= 0 && ru < w && rv < l) cells[rv * w + ru] = true;
			}
		}

		return cells;
	}

	@Override
	public void generate(ToolSettings s, GenerationContext ctx, GeometryBuilder out) {
		Mask2D region = regionMask(s);
		int w = region.width();
		int l = region.height();
		GeometryCenter centre = GeometryCenter.of(w, l, ctx.alignX(), ctx.alignZ());
		boolean[] protectedCells = protectedCells(s, centre, w, l);
		int[] heights = heightField(s, ctx, centre, region, protectedCells);
		List<TerrainLayer> layers = ctx.palettes().terrainLayers();
		List<List<Placement>> byLayer = new ArrayList<>();

		for (int i = 0; i < layers.size(); i++) {
			byLayer.add(new ArrayList<>());
		}

		int depthLimit = s.getInt("fill_depth");
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		long protectedCount = 0;

		for (int v = 0; v < l; v++) {
			for (int u = 0; u < w; u++) {
				if (!region.contains(u, v)) continue;

				int x = centre.minX() + u;
				int z = centre.minZ() + v;
				out.regionColumn(x, z);

				if (protectedCells[v * w + u]) {
					out.protectColumn(x, z);
					protectedCount++;

					if (isBorder(protectedCells, region, u, v, w, l)) out.guide(x, Math.max(0, groundHeight(ctx, x, z)) + 1, z, Guide.PROTECTED);

					continue;
				}

				if (!region.contains(u - 1, v) || !region.contains(u + 1, v) || !region.contains(u, v - 1) || !region.contains(u, v + 1)) {
					out.guide(x, 0, z, Guide.BOUNDARY);
				}

				int top = heights[v * w + u];
				int bottom = depthLimit > 0 ? Math.max(0, top - depthLimit + 1) : 0;
				minY = Math.min(minY, top);
				maxY = Math.max(maxY, top);

				for (int y = bottom; y <= top; y++) {
					out.set(x, y, z, MaterialRole.PRIMARY);
					byLayer.get(layerAt(layers, top - y)).add(out.get(x, y, z));
				}
			}
		}

		// Each layer's palette is assigned exactly (shares match its percentages), with a natural, clustered look.
		long seed = s.getInt("seed");

		for (int i = 0; i < layers.size(); i++) {
			WeightedPalette palette = layers.get(i).palette();
			List<Placement> pool = byLayer.get(i);

			if (palette.active().size() == 1) {
				int material = out.material(MaterialRef.block(palette.active().getFirst().block()));

				for (Placement p : pool) {
					out.put(p.withMaterial(material));
				}
			} else {
				Randomiser.assign(out, pool, RandomSettings.of(palette, RandomPattern.NATURAL, seed * 977 + i), -1);
			}
		}

		contours(out, s, centre, region, heights, protectedCells, w, l);
		centre.addCentreCells(out, 0);
		out.value("width", w);
		out.value("length", l);
		out.value("area", region.count());
		out.value("protected_columns", protectedCount);
		out.value("terrain_columns", region.count() - protectedCount);
		out.value("min_y", minY == Integer.MAX_VALUE ? 0 : minY);
		out.value("max_y", maxY == Integer.MIN_VALUE ? 0 : maxY);
	}

	private static int groundHeight(GenerationContext ctx, int x, int z) {
		int h = ctx.palettes().heights().heightAt(x, z);
		return h == HeightMap.UNKNOWN ? -1 : h;
	}

	private static boolean isBorder(boolean[] cells, Mask2D region, int u, int v, int w, int l) {
		for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			int nu = u + d[0];
			int nv = v + d[1];

			if (nu < 0 || nv < 0 || nu >= w || nv >= l || !cells[nv * w + nu]) return true;
		}

		return false;
	}

	/** The layer a block {@code depth} blocks below the surface belongs to (the last layer reaches the base). */
	static int layerAt(List<TerrainLayer> layers, int depth) {
		int remaining = depth;

		for (int i = 0; i < layers.size(); i++) {
			int t = layers.get(i).thickness();

			if (i == layers.size() - 1 || t == 0 || remaining < t) return i;

			remaining -= t;
		}

		return layers.size() - 1;
	}

	/** Contour lines every {@code contour_interval} blocks (guides on the surface) and optional {@code Y=…} labels. */
	private static void contours(GeometryBuilder out, ToolSettings s, GeometryCenter centre, Mask2D region, int[] heights, boolean[] protectedCells,
			int w, int l) {
		int interval = s.getInt("contour_interval");

		if (interval <= 0) return;

		java.util.Set<Integer> labelled = new java.util.HashSet<>();
		boolean labels = s.getBool("height_labels");

		for (int v = 0; v < l; v++) {
			for (int u = 0; u < w; u++) {
				int i = v * w + u;
				int h = heights[i];

				if (h < 0) continue;

				int level = Math.floorDiv(h, interval);
				boolean edge = false;

				for (int[] d : new int[][] {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
					int nu = u + d[0];
					int nv = v + d[1];

					if (nu < 0 || nv < 0 || nu >= w || nv >= l) continue;

					int nh = heights[nv * w + nu];

					if (nh >= 0 && Math.floorDiv(nh, interval) < level) edge = true;
				}

				if (!edge || level == 0) continue;

				int x = centre.minX() + u;
				int z = centre.minZ() + v;
				out.guide(x, h + 1, z, Guide.CONTOUR);

				if (labels && labelled.add(level)) out.annotate(x, h + 1, z, "Y={y}");
			}
		}
	}

	@Override
	public Set<MaterialRole> roles() {
		return EnumSet.of(MaterialRole.PRIMARY);
	}

	@Override
	public List<String> dimensionKeys() {
		return List.of("width", "length", "area", "min_y", "max_y");
	}
}
