package com.howtobuild.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.howtobuild.geometry.StateTransform;

/**
 * Exact material accounting from the final block states (after details, randomisation, mirror and material
 * overrides), so the Build tab always describes what will actually be placed.
 */
public final class MaterialCounter {
	/**
	 * One block type: its total, and the count of every exact state (e.g. each stair facing).
	 */
	public record Count(String block, long count, Map<String, Long> states) {
		public double percent(long total) {
			return total == 0 ? 0 : 100.0 * count / total;
		}
	}

	private MaterialCounter() {
	}

	/** Counts final states (one entry per block placed), most used first. */
	public static List<Count> count(Iterable<String> finalStates) {
		Map<String, Map<String, Long>> byBlock = new LinkedHashMap<>();

		for (String state : finalStates) {
			byBlock.computeIfAbsent(StateTransform.blockId(state), k -> new TreeMap<>()).merge(state, 1L, Long::sum);
		}

		List<Count> counts = new ArrayList<>();

		for (Map.Entry<String, Map<String, Long>> e : byBlock.entrySet()) {
			long total = 0;

			for (long n : e.getValue().values()) {
				total += n;
			}

			counts.add(new Count(e.getKey(), total, e.getValue()));
		}

		counts.sort(Comparator.comparingLong(Count::count).reversed().thenComparing(Count::block));
		return counts;
	}

	public static long total(List<Count> counts) {
		long total = 0;

		for (Count c : counts) {
			total += c.count();
		}

		return total;
	}

	/** Counts per value of one property (e.g. {@code facing}) for a block's states, for the state summary. */
	public static Map<String, Long> byProperty(Count count, String property) {
		Map<String, Long> map = new TreeMap<>();

		for (Map.Entry<String, Long> e : count.states().entrySet()) {
			String value = StateTransform.properties(e.getKey()).get(property);

			if (value != null) map.merge(value, e.getValue(), Long::sum);
		}

		return map;
	}
}
