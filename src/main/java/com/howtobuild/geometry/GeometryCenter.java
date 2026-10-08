package com.howtobuild.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * The one centre rule shared by every tool, the hologram's centre marker, rotation and the mirror.
 *
 * <p>A horizontal footprint of {@code sizeX × sizeZ} blocks is laid out around the anchor block (the selected centre):
 * <ul>
 *     <li>along an <b>odd</b> extent the anchor is the middle block (a 1-block centre);</li>
 *     <li>along an <b>even</b> extent the true centre is a block boundary, so the two middle blocks are the anchor and its
 *     neighbour towards {@code +axis} ({@code align = true}) or {@code −axis}.</li>
 * </ul>
 * This gives 1×1 centres for odd sizes, 2×2 for even sizes and 1×2 for mixed parity. Every coordinate is exact
 * integer arithmetic; the "doubled" centre is twice the centre plane's coordinate, so block {@code x} has its middle at
 * {@code 2x + 1} and comparisons with the centre never need fractions.
 *
 * @param sizeX  footprint extent along X
 * @param sizeZ  footprint extent along Z
 * @param alignX for an even X extent, the centre extends towards +X
 * @param alignZ for an even Z extent, the centre extends towards +Z
 */
public record GeometryCenter(int sizeX, int sizeZ, boolean alignX, boolean alignZ) {
	public GeometryCenter {
		sizeX = Math.max(1, sizeX);
		sizeZ = Math.max(1, sizeZ);
	}

	public static GeometryCenter of(int sizeX, int sizeZ, boolean alignX, boolean alignZ) {
		return new GeometryCenter(sizeX, sizeZ, alignX, alignZ);
	}

	/** Offset from the anchor of the footprint's lowest X block. */
	public int minX() {
		return Centring.minOffset(sizeX, alignX);
	}

	public int minZ() {
		return Centring.minOffset(sizeZ, alignZ);
	}

	public int maxX() {
		return minX() + sizeX - 1;
	}

	public int maxZ() {
		return minZ() + sizeZ - 1;
	}

	/** Twice the X coordinate of the centre plane, relative to the anchor block's west face. */
	public int doubledX() {
		return 2 * minX() + sizeX;
	}

	public int doubledZ() {
		return 2 * minZ() + sizeZ;
	}

	/** Centre width along X (1 or 2 blocks). */
	public int centreWidthX() {
		return sizeX % 2 == 1 ? 1 : 2;
	}

	public int centreWidthZ() {
		return sizeZ % 2 == 1 ? 1 : 2;
	}

	/** "1×1", "2×2" or "1×2". */
	public String label() {
		return centreWidthX() + "×" + centreWidthZ();
	}

	/** The 1, 2 or 4 centre cells (x, z) relative to the anchor. */
	public List<int[]> cells() {
		List<int[]> cells = new ArrayList<>();

		for (int x : Centring.centreOffsets(sizeX, alignX)) {
			for (int z : Centring.centreOffsets(sizeZ, alignZ)) {
				cells.add(new int[] {x, z});
			}
		}

		return cells;
	}

	/** Adds the centre cells at layer {@code y} to a builder. */
	public void addCentreCells(GeometryBuilder out, int y) {
		for (int[] c : cells()) {
			out.centreCell(c[0], y, c[1]);
		}
	}

	/** Doubled offset of block {@code x}'s middle from the centre plane (0 at the exact centre of a 1-wide centre). */
	public int dx(int x) {
		return 2 * x + 1 - doubledX();
	}

	public int dz(int z) {
		return 2 * z + 1 - doubledZ();
	}

	/** Angle in degrees of block (x, z) around the true centre, measured from +X towards +Z. */
	public double angleDegrees(int x, int z) {
		return Math.toDegrees(Math.atan2(dz(z), dx(x)));
	}

	/**
	 * Why a forced centre size does not fit these extents, or {@code null} if it does. Incompatible settings are reported
	 * instead of silently changing the size or producing an off-centre shape.
	 */
	public static String incompatibility(CentreSize mode, int sizeX, int sizeZ) {
		if (mode == null || mode == CentreSize.AUTO) return null;

		boolean wantOdd = mode == CentreSize.ONE_BY_ONE;
		StringBuilder bad = new StringBuilder();

		if ((sizeX % 2 == 1) != wantOdd) bad.append(sizeX);
		if (sizeZ != sizeX && (sizeZ % 2 == 1) != wantOdd) bad.append(bad.isEmpty() ? "" : " and ").append(sizeZ);

		if (bad.isEmpty()) return null;

		String need = wantOdd ? "odd" : "even";
		return "⚠ A " + (wantOdd ? "1×1" : "2×2") + " centre needs " + need + " sizes, but " + bad + (bad.indexOf("and") > 0 ? " are " : " is ")
				+ (wantOdd ? "even" : "odd") + ". Use Automatic, or change the size by one block.";
	}
}
