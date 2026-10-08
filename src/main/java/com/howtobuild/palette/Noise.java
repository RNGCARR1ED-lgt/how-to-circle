package com.howtobuild.palette;

/**
 * Deterministic gradient (Perlin) noise and fractal sums, in pure Java. The same seed and coordinates always give the
 * same value on every machine, so randomised materials and terrain are reproducible.
 */
public final class Noise {
	private static final double[][] GRADIENTS = {
			{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
			{0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}, {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}};

	private Noise() {
	}

	/** A well-mixed 64-bit hash of a seed and integer coordinates. */
	public static long hash(long seed, int x, int y, int z) {
		long h = seed * 0x9E3779B97F4A7C15L + x * 0xBF58476D1CE4E5B9L + y * 0x94D049BB133111EBL + z * 0xD6E8FEB86659FD93L;
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		return h ^ (h >>> 31);
	}

	/** Uniform value in [0, 1) for integer coordinates. */
	public static double hash01(long seed, int x, int y, int z) {
		return (hash(seed, x, y, z) >>> 11) * 0x1.0p-53;
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static double grad(long seed, int x, int y, int z, double dx, double dy, double dz) {
		double[] g = GRADIENTS[(int) (hash(seed, x, y, z) & 15)];
		return g[0] * dx + g[1] * dy + g[2] * dz;
	}

	/** 3D gradient noise, roughly in [-1, 1]. */
	public static double perlin(long seed, double x, double y, double z) {
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		int z0 = (int) Math.floor(z);
		double fx = x - x0;
		double fy = y - y0;
		double fz = z - z0;
		double u = fade(fx);
		double v = fade(fy);
		double w = fade(fz);
		double x00 = lerp(grad(seed, x0, y0, z0, fx, fy, fz), grad(seed, x0 + 1, y0, z0, fx - 1, fy, fz), u);
		double x10 = lerp(grad(seed, x0, y0 + 1, z0, fx, fy - 1, fz), grad(seed, x0 + 1, y0 + 1, z0, fx - 1, fy - 1, fz), u);
		double x01 = lerp(grad(seed, x0, y0, z0 + 1, fx, fy, fz - 1), grad(seed, x0 + 1, y0, z0 + 1, fx - 1, fy, fz - 1), u);
		double x11 = lerp(grad(seed, x0, y0 + 1, z0 + 1, fx, fy - 1, fz - 1), grad(seed, x0 + 1, y0 + 1, z0 + 1, fx - 1, fy - 1, fz - 1), u);
		return lerp(lerp(x00, x10, v), lerp(x01, x11, v), w);
	}

	/** 2D gradient noise (z = 0 slice), roughly in [-1, 1]. */
	public static double perlin(long seed, double x, double z) {
		return perlin(seed, x, 0.5, z);
	}

	/**
	 * Fractal Brownian motion: {@code octaves} layers of noise, each {@code lacunarity} times finer and
	 * {@code persistence} times weaker. Normalised to roughly [-1, 1].
	 */
	public static double fbm(long seed, double x, double y, double z, int octaves, double persistence, double lacunarity) {
		double sum = 0;
		double amplitude = 1;
		double norm = 0;
		double frequency = 1;

		for (int i = 0; i < Math.max(1, octaves); i++) {
			sum += amplitude * perlin(seed + i * 1013L, x * frequency, y * frequency, z * frequency);
			norm += amplitude;
			amplitude *= persistence;
			frequency *= lacunarity;
		}

		return sum / norm;
	}

	/** Ridged fractal noise in [0, 1]: sharp crests (mountain ridges) where plain noise crosses zero. */
	public static double ridged(long seed, double x, double z, int octaves, double persistence, double lacunarity) {
		double sum = 0;
		double amplitude = 1;
		double norm = 0;
		double frequency = 1;
		double weight = 1;

		for (int i = 0; i < Math.max(1, octaves); i++) {
			double n = 1 - Math.abs(perlin(seed + i * 7919L, x * frequency, 0.5, z * frequency));
			n = n * n * weight;
			weight = Math.min(1, n * 2);
			sum += n * amplitude;
			norm += amplitude;
			amplitude *= persistence;
			frequency *= lacunarity;
		}

		return sum / norm;
	}

	/**
	 * Cellular value: every cell of {@code size} blocks holds one jittered feature point; a position takes the value of
	 * the nearest point. Gives organic clusters with irregular borders.
	 */
	public static double cellular(long seed, int x, int y, int z, int size) {
		int s = Math.max(1, size);
		int cx = Math.floorDiv(x, s);
		int cy = Math.floorDiv(y, s);
		int cz = Math.floorDiv(z, s);
		double best = Double.MAX_VALUE;
		double value = 0;

		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					int gx = cx + dx;
					int gy = cy + dy;
					int gz = cz + dz;
					double px = (gx + hash01(seed, gx, gy, gz)) * s;
					double py = (gy + hash01(seed + 1, gx, gy, gz)) * s;
					double pz = (gz + hash01(seed + 2, gx, gy, gz)) * s;
					double d = sq(x + 0.5 - px) + sq(y + 0.5 - py) + sq(z + 0.5 - pz);

					if (d < best) {
						best = d;
						value = hash01(seed + 3, gx, gy, gz);
					}
				}
			}
		}

		return value;
	}

	private static double sq(double v) {
		return v * v;
	}
}
