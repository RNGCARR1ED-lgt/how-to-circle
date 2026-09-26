package com.howtocircle.dimensions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.howtocircle.geometry.CircleShape;

/**
 * Groups the blocks of a shape into straight-line / rectangular {@link ConnectedSection}s.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *     <li>Split every row into <em>runs</em>: maximal sequences of blocks that touch along an edge in a straight line.
 *     Diagonal neighbours are never part of the same run.</li>
 *     <li>Merge runs in consecutive rows that have <em>exactly</em> the same start and end into one rectangle. Runs
 *     that only partially overlap are never merged, so every section is a solid rectangle made only of shape blocks
 *     and separate sections are never combined.</li>
 *     <li>Do the same with columns as the primary direction, and keep whichever result has fewer sections. On a tie the
 *     lines follow the shape's longer axis (rows for wide shapes, columns for tall ones). Fewer sections means fewer,
 *     larger labels.</li>
 * </ol>
 *
 * For a filled circle this yields horizontal strips where equal-width rows are stacked into rectangles
 * (e.g. a 7×7 circle becomes 3 by 1, 5 by 1, 7 by 3, 5 by 1, 3 by 1). For an outline, the flat top and bottom become
 * horizontal lines and the steep sides become vertical lines (single blocks stacked in a column merge vertically).
 *
 * <p>The result is deterministic and exactly partitions the shape: every block is in exactly one section.
 */
public final class SectionDetector {
	private SectionDetector() {
	}

	public static List<ConnectedSection> detect(CircleShape shape) {
		List<ConnectedSection> rows = decompose(shape, false);
		List<ConnectedSection> columns = decompose(shape, true);
		boolean preferColumns = columns.size() < rows.size()
				|| (columns.size() == rows.size() && shape.height() > shape.width());
		return preferColumns ? columns : rows;
	}

	/**
	 * Row-major decomposition when {@code transpose} is false, column-major when true.
	 */
	static List<ConnectedSection> decompose(CircleShape shape, boolean transpose) {
		int lines = transpose ? shape.width() : shape.height();
		int length = transpose ? shape.height() : shape.width();

		// Each rectangle: [lineStart, lineEnd (inclusive), runStart, runEnd (inclusive)]
		List<int[]> rects = new ArrayList<>();
		Map<Long, int[]> open = new HashMap<>();
		Map<Long, int[]> nextOpen = new HashMap<>();

		for (int line = 0; line < lines; line++) {
			nextOpen.clear();
			int pos = 0;

			while (pos < length) {
				if (!cell(shape, transpose, line, pos)) {
					pos++;
					continue;
				}

				int start = pos;

				while (pos < length && cell(shape, transpose, line, pos)) {
					pos++;
				}

				int end = pos - 1;
				long key = ((long) start << 32) | (end & 0xFFFFFFFFL);
				int[] rect = open.get(key);

				if (rect != null && rect[1] == line - 1) {
					rect[1] = line;
				} else {
					rect = new int[] {line, line, start, end};
					rects.add(rect);
				}

				nextOpen.put(key, rect);
			}

			Map<Long, int[]> swap = open;
			open = nextOpen;
			nextOpen = swap;
		}

		List<ConnectedSection> sections = new ArrayList<>(rects.size());

		for (int[] rect : rects) {
			int lineSize = rect[1] - rect[0] + 1;
			int runSize = rect[3] - rect[2] + 1;

			if (transpose) {
				sections.add(new ConnectedSection(rect[0], rect[2], lineSize, runSize));
			} else {
				sections.add(new ConnectedSection(rect[2], rect[0], runSize, lineSize));
			}
		}

		sections.sort(Comparator.comparingInt(ConnectedSection::v).thenComparingInt(ConnectedSection::u));
		return List.copyOf(sections);
	}

	private static boolean cell(CircleShape shape, boolean transpose, int line, int pos) {
		return transpose ? shape.contains(line, pos) : shape.contains(pos, line);
	}
}
