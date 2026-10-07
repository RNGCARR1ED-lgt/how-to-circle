package com.howtobuild.geometry;

/**
 * An inclusive, axis-aligned block box.
 */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public static Box of(int x, int y, int z) {
		return new Box(x, y, z, x, y, z);
	}

	public int sizeX() {
		return maxX - minX + 1;
	}

	public int sizeY() {
		return maxY - minY + 1;
	}

	public int sizeZ() {
		return maxZ - minZ + 1;
	}

	public long volume() {
		return (long) sizeX() * sizeY() * sizeZ();
	}

	public int size(int axis) {
		return switch (axis) {
			case 0 -> sizeX();
			case 1 -> sizeY();
			default -> sizeZ();
		};
	}

	public int longestSide() {
		return Math.max(sizeX(), Math.max(sizeY(), sizeZ()));
	}

	public boolean contains(int x, int y, int z) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}

	public Box include(int x, int y, int z) {
		return new Box(Math.min(minX, x), Math.min(minY, y), Math.min(minZ, z), Math.max(maxX, x), Math.max(maxY, y), Math.max(maxZ, z));
	}

	public Box offset(int dx, int dy, int dz) {
		return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
	}
}
