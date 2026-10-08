package com.howtobuild.geometry;

/**
 * One block of generated geometry, relative to the anchor (the selected centre block).
 *
 * @param x        block x offset
 * @param y        block y offset
 * @param z        block z offset
 * @param role     what the block is for; the material profile maps it to a block
 * @param shape    full block, slab or stairs with its exact state
 * @param variant  material variation index (0 = the role's main block)
 * @param mirrored whether this block is the mirrored copy
 * @param material index into {@link GeometryResult#materials()} overriding the role's material (a randomisation palette
 *                 entry, a terrain layer block, or an exact saved block state), or {@code -1} to use the role's material
 */
public record Placement(int x, int y, int z, MaterialRole role, BlockShape shape, int variant, boolean mirrored, int material) {
	public static final int NO_MATERIAL = -1;

	public Placement(int x, int y, int z, MaterialRole role, BlockShape shape, int variant, boolean mirrored) {
		this(x, y, z, role, shape, variant, mirrored, NO_MATERIAL);
	}

	public long key() {
		return Voxels.pack(x, y, z);
	}

	public boolean hasMaterial() {
		return material >= 0;
	}

	public Placement withRole(MaterialRole newRole) {
		return new Placement(x, y, z, newRole, shape, variant, mirrored, material);
	}

	public Placement withShape(BlockShape newShape) {
		return new Placement(x, y, z, role, newShape, variant, mirrored, material);
	}

	public Placement withVariant(int newVariant) {
		return new Placement(x, y, z, role, shape, newVariant, mirrored, material);
	}

	public Placement withMaterial(int newMaterial) {
		return new Placement(x, y, z, role, shape, variant, mirrored, newMaterial);
	}

	public Placement at(int nx, int ny, int nz) {
		return new Placement(nx, ny, nz, role, shape, variant, mirrored, material);
	}

	public Placement asMirror(int nx, int ny, int nz, BlockShape newShape) {
		return new Placement(nx, ny, nz, role, newShape, variant, true, material);
	}
}
