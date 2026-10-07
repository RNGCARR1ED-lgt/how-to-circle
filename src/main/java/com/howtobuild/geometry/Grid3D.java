package com.howtobuild.geometry;

import java.util.BitSet;

/**
 * A dense {@code w × h × l} bit grid used to build solids and shells. Shells are made by erosion: a cell is interior
 * if all six neighbours are solid, so a shell of thickness {@code t} is the solid minus the solid eroded {@code t}
 * times. That gives exactly {@code t} layers everywhere and a watertight (gap-free) shell, matching the classic
 * one-block outline when {@code t = 1}.
 */
public final class Grid3D {
	private final int w;
	private final int h;
	private final int l;
	private final BitSet bits;

	public Grid3D(int w, int h, int l) {
		this.w = w;
		this.h = h;
		this.l = l;
		this.bits = new BitSet(Math.max(0, w * h * l));
	}

	private Grid3D(int w, int h, int l, BitSet bits) {
		this.w = w;
		this.h = h;
		this.l = l;
		this.bits = bits;
	}

	public int width() {
		return w;
	}

	public int height() {
		return h;
	}

	public int length() {
		return l;
	}

	private int index(int i, int j, int k) {
		return (j * l + k) * w + i;
	}

	public void set(int i, int j, int k) {
		bits.set(index(i, j, k));
	}

	public boolean get(int i, int j, int k) {
		return i >= 0 && j >= 0 && k >= 0 && i < w && j < h && k < l && bits.get(index(i, j, k));
	}

	/**
	 * One erosion step. {@code solidBelow} treats the space under the bottom layer as solid, so the base of a dome or
	 * an arch stays open instead of becoming a floor.
	 */
	public Grid3D erode(boolean solidBelow) {
		BitSet out = new BitSet(bits.size());

		for (int index = bits.nextSetBit(0); index >= 0; index = bits.nextSetBit(index + 1)) {
			int i = index % w;
			int k = (index / w) % l;
			int j = index / (w * l);

			if (get(i - 1, j, k) && get(i + 1, j, k) && get(i, j, k - 1) && get(i, j, k + 1) && get(i, j + 1, k)
					&& (get(i, j - 1, k) || (solidBelow && j == 0))) {
				out.set(index);
			}
		}

		return new Grid3D(w, h, l, out);
	}

	/** The cells of this grid that are not in {@code other} (same size). */
	public Grid3D minus(Grid3D other) {
		BitSet out = (BitSet) bits.clone();
		out.andNot(other.bits);
		return new Grid3D(w, h, l, out);
	}

	/** Solid minus its {@code t}-times erosion: a watertight shell exactly {@code t} cells thick. */
	public Grid3D shell(int thickness, boolean openBottom) {
		Grid3D inner = this;

		for (int n = 0; n < thickness; n++) {
			inner = inner.erode(openBottom);
		}

		return minus(inner);
	}

	public int count() {
		return bits.cardinality();
	}
}
