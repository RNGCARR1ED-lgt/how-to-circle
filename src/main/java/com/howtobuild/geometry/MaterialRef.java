package com.howtobuild.geometry;

/**
 * A material a placement uses instead of its role's material.
 *
 * <ul>
 *     <li>A <b>block</b> reference ({@code exact = false}) names a block, e.g. {@code minecraft:mossy_stone_bricks}. The
 *     placement's {@link BlockShape} still decides the form: a stair placement becomes that block family's stairs. Used
 *     by randomisation palettes and terrain layers.</li>
 *     <li>An <b>exact</b> reference is a complete block state string as Minecraft writes it, e.g.
 *     {@code minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]}. Used by saved builds, where
 *     the saved state is authoritative.</li>
 * </ul>
 */
public record MaterialRef(String value, boolean exact) {
	public static MaterialRef block(String id) {
		return new MaterialRef(id, false);
	}

	public static MaterialRef exactState(String state) {
		return new MaterialRef(state, true);
	}

	/** The block id without properties. */
	public String blockId() {
		int bracket = value.indexOf('[');
		return bracket < 0 ? value : value.substring(0, bracket);
	}
}
