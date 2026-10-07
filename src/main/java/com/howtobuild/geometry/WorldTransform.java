package com.howtobuild.geometry;

/**
 * The only place where generated geometry meets world coordinates.
 *
 * <pre>
 * placement (relative to the centre) ─► + anchor (the selected centre block) ─► + offset (X, Y, Z) ─► world block
 * </pre>
 * Rotation and mirroring happen before this, in shape space, so every tool, the hologram, labels, progress tracking and
 * the command builder share exactly the same transform. There is no hidden adjustment: with offset (0, 0, 0) a
 * placement at relative y = 0 lands exactly on the anchor's layer.
 */
public record WorldTransform(int anchorX, int anchorY, int anchorZ, int offsetX, int offsetY, int offsetZ) {
	public int originX() {
		return anchorX + offsetX;
	}

	public int originY() {
		return anchorY + offsetY;
	}

	public int originZ() {
		return anchorZ + offsetZ;
	}

	public int x(int relativeX) {
		return originX() + relativeX;
	}

	public int y(int relativeY) {
		return originY() + relativeY;
	}

	public int z(int relativeZ) {
		return originZ() + relativeZ;
	}

	/** World position of a placement as {x, y, z}. */
	public int[] apply(Placement p) {
		return new int[] {x(p.x()), y(p.y()), z(p.z())};
	}
}
