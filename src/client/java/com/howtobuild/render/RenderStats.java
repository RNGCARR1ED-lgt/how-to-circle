package com.howtobuild.render;

/**
 * Lightweight CPU timing of the hologram render code, used to verify that large shapes stay cheap.
 */
public final class RenderStats {
	private static long totalNanos;
	private static long frames;

	private RenderStats() {
	}

	static void add(long nanos) {
		totalNanos += nanos;
	}

	/** Records the time spent in the level render callback and counts one frame. */
	public static void record(long nanos) {
		totalNanos += nanos;
		frames++;
	}

	public static void reset() {
		totalNanos = 0;
		frames = 0;
	}

	public static long frames() {
		return frames;
	}

	/** Average CPU milliseconds spent building hologram and label geometry per frame since the last reset. */
	public static double averageMillisPerFrame() {
		return frames == 0 ? 0 : totalNanos / 1.0e6 / frames;
	}
}
