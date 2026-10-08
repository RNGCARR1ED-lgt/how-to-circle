package com.howtobuild.palette;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of blocks with weights, shared by randomisation, terrain layers and anything else that mixes
 * materials.
 *
 * <p>Weights are percentages. When they do not add up to 100, {@link #totalWarning()} says so; with
 * {@code normalise} they are scaled proportionally (60 / 30 / 30 → 50 / 25 / 25). {@link #shares(int)} turns the
 * weights into exact block counts with the largest-remainder method, so counts always add up to the number of blocks
 * and differ from the ideal by less than one block.
 */
public record WeightedPalette(List<PaletteEntry> entries, boolean normalise) {
	public WeightedPalette {
		entries = List.copyOf(entries);
	}

	public static WeightedPalette of(PaletteEntry... entries) {
		return new WeightedPalette(List.of(entries), true);
	}

	public static WeightedPalette single(String block) {
		return new WeightedPalette(List.of(PaletteEntry.of(block, 100)), true);
	}

	/** Enabled entries with a positive weight, in order. */
	public List<PaletteEntry> active() {
		List<PaletteEntry> list = new ArrayList<>();

		for (PaletteEntry e : entries) {
			if (e.enabled() && e.weight() > 0 && e.block() != null && !e.block().isBlank()) list.add(e);
		}

		return list;
	}

	public boolean isEmpty() {
		return active().isEmpty();
	}

	/** Sum of the enabled weights. */
	public double total() {
		double total = 0;

		for (PaletteEntry e : active()) {
			total += e.weight();
		}

		return total;
	}

	/** A warning when the weights do not add up to 100 % and normalisation is off; otherwise {@code null}. */
	public String totalWarning() {
		double total = total();

		if (isEmpty()) return "⚠ The palette has no enabled blocks.";
		if (!normalise && Math.abs(total - 100) > 1e-6) {
			return String.format(java.util.Locale.ROOT, "⚠ The percentages add up to %.1f%%, not 100%%. Turn on Normalise automatically, or adjust them.", total);
		}

		return null;
	}

	/** Effective fraction of each active entry (always sums to 1; equal weights if {@code equal}). */
	public double[] fractions(boolean equal) {
		List<PaletteEntry> active = active();
		double[] f = new double[active.size()];
		double total = equal ? active.size() : total();

		for (int i = 0; i < f.length; i++) {
			f[i] = total <= 0 ? 0 : (equal ? 1 : active.get(i).weight()) / total;
		}

		return f;
	}

	/** Exact block counts for {@code n} blocks (largest remainder; sums to {@code n}). */
	public int[] shares(int n, boolean equal) {
		double[] f = fractions(equal);
		return largestRemainder(f, n);
	}

	public static int[] largestRemainder(double[] fractions, int n) {
		int[] counts = new int[fractions.length];

		if (fractions.length == 0) return counts;

		double[] remainders = new double[fractions.length];
		int assigned = 0;

		for (int i = 0; i < fractions.length; i++) {
			double exact = fractions[i] * n;
			counts[i] = (int) Math.floor(exact);
			remainders[i] = exact - counts[i];
			assigned += counts[i];
		}

		while (assigned < n) {
			int best = 0;

			for (int i = 1; i < remainders.length; i++) {
				if (remainders[i] > remainders[best]) best = i;
			}

			counts[best]++;
			remainders[best] = -1;
			assigned++;
		}

		return counts;
	}
}
