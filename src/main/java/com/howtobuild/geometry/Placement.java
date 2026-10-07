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
 */
public record Placement(int x, int y, int z, MaterialRole role, BlockShape shape, int variant, boolean mirrored) {
	public long key() {
		return Voxels.pack(x, y, z);
	}

	public Placement withRole(MaterialRole newRole) {
		return new Placement(x, y, z, newRole, shape, variant, mirrored);
	}

	public Placement withShape(BlockShape newShape) {
		return new Placement(x, y, z, role, newShape, variant, mirrored);
	}

	public Placement withVariant(int newVariant) {
		return new Placement(x, y, z, role, shape, newVariant, mirrored);
	}

	public Placement at(int nx, int ny, int nz) {
		return new Placement(nx, ny, nz, role, shape, variant, mirrored);
	}

	public Placement asMirror(int nx, int ny, int nz, BlockShape newShape) {
		return new Placement(nx, ny, nz, role, newShape, variant, true);
	}
}
