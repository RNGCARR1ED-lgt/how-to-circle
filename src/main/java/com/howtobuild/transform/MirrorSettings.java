package com.howtobuild.transform;

/**
 * Mirror configuration.
 *
 * @param enabled     whether mirroring is applied
 * @param axis        mirror plane(s)
 * @param mode        preview, duplicate or replace
 * @param twoWideX    the mirror centre is 2 blocks wide along X (a block corner) instead of 1 block
 * @param twoWideZ    the mirror centre is 2 blocks wide along Z
 * @param alignX      for a 2-wide centre, it extends towards +X from the centre block
 * @param alignZ      for a 2-wide centre, it extends towards +Z from the centre block
 * @param offsetX     offset of the X mirror plane from the centre, in blocks
 * @param offsetZ     offset of the Z mirror plane from the centre, in blocks
 */
public record MirrorSettings(boolean enabled, MirrorAxis axis, MirrorMode mode, boolean twoWideX, boolean twoWideZ,
		boolean alignX, boolean alignZ, int offsetX, int offsetZ) {
	public static final MirrorSettings OFF = new MirrorSettings(false, MirrorAxis.X, MirrorMode.DUPLICATE, false, false, true, true, 0, 0);
}
