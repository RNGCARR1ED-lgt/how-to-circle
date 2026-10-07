package com.howtobuild.geometry;

/**
 * A horizontal (cardinal) direction in Minecraft world space: +X is east, +Z is south.
 */
public enum Facing {
	NORTH(0, -1),
	EAST(1, 0),
	SOUTH(0, 1),
	WEST(-1, 0);

	public final int dx;
	public final int dz;

	Facing(int dx, int dz) {
		this.dx = dx;
		this.dz = dz;
	}

	/** Clockwise when viewed from above: north → east → south → west. */
	public Facing clockwise() {
		return values()[(ordinal() + 1) & 3];
	}

	public Facing counterClockwise() {
		return values()[(ordinal() + 3) & 3];
	}

	public Facing opposite() {
		return values()[(ordinal() + 2) & 3];
	}

	public Facing rotateClockwise(int quarterTurns) {
		return values()[(ordinal() + (quarterTurns & 3)) & 3];
	}

	public boolean alongX() {
		return dx != 0;
	}

	/** The cardinal direction closest to the vector {@code (vx, vz)}; ties prefer the X axis. */
	public static Facing nearest(double vx, double vz) {
		if (Math.abs(vx) >= Math.abs(vz)) {
			return vx >= 0 ? EAST : WEST;
		}

		return vz >= 0 ? SOUTH : NORTH;
	}

	public String serializedName() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
