package com.howtobuild.tools.impl;

/**
 * Small helpers shared by the 3D tools.
 */
final class Shapes3D {
	private Shapes3D() {
	}

	/** Angle (0..2π) of a cell centre around the vertical axis, from doubled offsets. */
	static double angle(long doubledX, long doubledZ) {
		double a = Math.atan2(doubledZ, doubledX);
		return a < 0 ? a + 2 * Math.PI : a;
	}

	/** Whether a cell lies on one of {@code count} evenly spaced meridians (within half a block of arc). */
	static boolean onMeridian(long doubledX, long doubledZ, int count) {
		if (count <= 0) return false;

		double radius = Math.sqrt((double) doubledX * doubledX + (double) doubledZ * doubledZ) / 2.0;

		if (radius < 0.75) return true;

		double step = 2 * Math.PI / count;
		double a = angle(doubledX, doubledZ);
		double d = Math.abs(a - Math.round(a / step) * step);
		return d * radius < 0.55;
	}

	/** Sector index (0..count-1) of a cell around the vertical axis. */
	static int sector(long doubledX, long doubledZ, int count) {
		return Math.min(count - 1, (int) (angle(doubledX, doubledZ) / (2 * Math.PI) * count));
	}

	/** Number of meridians giving roughly {@code interval} blocks between them on a perimeter of the given diameter. */
	static int meridians(int diameter, int interval) {
		int n = (int) Math.round(Math.PI * diameter / Math.max(2, interval));
		return Math.max(4, n - n % 2);
	}
}
