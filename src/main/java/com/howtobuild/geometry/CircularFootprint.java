package com.howtobuild.geometry;

/**
 * The exact block footprint of a circle or oval: the source of truth for every circular tool.
 *
 * <p>The cells are those of the circle generator for {@code width × length} (the same cells the Circle and Oval tools
 * build), laid out around the anchor by the shared {@link GeometryCenter} rule. A spiral that follows a circle uses this
 * very object, so its blocks are a subset of the circle's cells by construction, never an independently computed
 * radius.
 */
public final class CircularFootprint {
	private final GeometryCenter centre;
	private final Mask2D mask;

	private CircularFootprint(GeometryCenter centre, Mask2D mask) {
		this.centre = centre;
		this.mask = mask;
	}

	/** The filled circle / oval of exactly {@code width × length} blocks around the anchor. */
	public static CircularFootprint of(int width, int length, boolean alignX, boolean alignZ) {
		return new CircularFootprint(GeometryCenter.of(width, length, alignX, alignZ), Mask2D.ellipse(width, length));
	}

	/** A footprint with the same centre and bounds but different cells (e.g. a ring or an inset). */
	public CircularFootprint withMask(Mask2D newMask) {
		return new CircularFootprint(centre, newMask);
	}

	public GeometryCenter centre() {
		return centre;
	}

	/** Cells in footprint space ({@code u = x − minX}, {@code v = z − minZ}). */
	public Mask2D mask() {
		return mask;
	}

	public int width() {
		return centre.sizeX();
	}

	public int length() {
		return centre.sizeZ();
	}

	public int minX() {
		return centre.minX();
	}

	public int minZ() {
		return centre.minZ();
	}

	public int maxX() {
		return centre.maxX();
	}

	public int maxZ() {
		return centre.maxZ();
	}

	/** Whether block (x, z), relative to the anchor, is part of this footprint. */
	public boolean contains(int x, int z) {
		return mask.contains(x - minX(), z - minZ());
	}

	public int count() {
		return mask.count();
	}

	/** The one-block outer boundary (the classic circle outline). */
	public CircularFootprint boundary() {
		return withMask(mask.outline());
	}

	/** The footprint shrunk inwards by {@code blocks} (exact erosion; the outer boundary moves in, the centre stays). */
	public CircularFootprint inset(int blocks) {
		return withMask(mask.eroded(blocks, false));
	}

	/** A ring {@code thickness} blocks thick, growing inwards from the outer boundary. */
	public CircularFootprint ring(int thickness) {
		return withMask(mask.ring(thickness));
	}

	/** This footprint minus another one with the same centre rule (e.g. an inner hole). */
	public CircularFootprint minus(CircularFootprint other) {
		return withMask(mask.minus(other.mask, other.minX() - minX(), other.minZ() - minZ()));
	}

	/** Cells of this footprint that are not in {@code other} (same centre). */
	public CircularFootprint without(CircularFootprint other) {
		return minus(other);
	}

	/** Calls {@code action} for every cell with anchor-relative (x, z). */
	public void forEach(CellAction action) {
		for (int v = 0; v < mask.height(); v++) {
			for (int u = 0; u < mask.width(); u++) {
				if (mask.contains(u, v)) action.accept(u + minX(), v + minZ());
			}
		}
	}

	/** Bounds of the footprint over the layers {@code y0..y1}. */
	public Box bounds(int y0, int y1) {
		return new Box(minX(), y0, minZ(), maxX(), y1, maxZ());
	}

	/** Angle in degrees of block (x, z) around the true centre. */
	public double angleDegrees(int x, int z) {
		return centre.angleDegrees(x, z);
	}

	public interface CellAction {
		void accept(int x, int z);
	}
}
