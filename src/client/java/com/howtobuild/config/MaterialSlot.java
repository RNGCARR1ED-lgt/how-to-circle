package com.howtobuild.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The blocks used for one material role: a full block, its slab and stairs, plus optional variation blocks.
 * Values are registry ids such as {@code minecraft:stone_bricks}; they are validated against the block registry when
 * resolved, so missing or wrong-type ids fall back safely.
 */
public final class MaterialSlot {
	public String block;
	public String slab;
	public String stairs;
	public List<String> variants = new ArrayList<>();

	public MaterialSlot() {
	}

	public MaterialSlot(String block, String slab, String stairs) {
		this.block = block;
		this.slab = slab;
		this.stairs = stairs;
	}

	public MaterialSlot copy() {
		MaterialSlot copy = new MaterialSlot(block, slab, stairs);
		copy.variants = new ArrayList<>(variants == null ? List.of() : variants);
		return copy;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof MaterialSlot m && java.util.Objects.equals(block, m.block) && java.util.Objects.equals(slab, m.slab)
				&& java.util.Objects.equals(stairs, m.stairs) && java.util.Objects.equals(variants, m.variants);
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(block, slab, stairs, variants);
	}
}
