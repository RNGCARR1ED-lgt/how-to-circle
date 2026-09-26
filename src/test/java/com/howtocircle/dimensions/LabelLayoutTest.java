package com.howtocircle.dimensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LabelLayoutTest {
	@Test
	void separatedLabelsStayInPlace() {
		LabelLayout layout = new LabelLayout();
		int a = layout.add(0, 0, 1, 0.5, 1);
		int b = layout.add(5, 0, 1, 0.5, 1);
		layout.solve();
		assertTrue(layout.visible(a) && layout.visible(b));
		assertEquals(0, layout.shiftY(a));
		assertEquals(0, layout.shiftY(b));
	}

	@Test
	void overlappingLabelIsNudgedAndHigherPriorityWins() {
		LabelLayout layout = new LabelLayout();
		int low = layout.add(0, 0, 1, 0.5, 1);
		int high = layout.add(0.2, 0.1, 1, 0.5, 10);
		layout.solve();
		assertTrue(layout.visible(high));
		assertEquals(0, layout.shiftY(high), "highest priority keeps its position");
		assertTrue(layout.visible(low));
		assertNotEquals(0, layout.shiftY(low), "lower priority label moves out of the way");
	}

	@Test
	void labelsThatCannotFitAreHidden() {
		LabelLayout layout = new LabelLayout();

		for (int i = 0; i < 20; i++) {
			layout.add(0, 0, 1, 0.5, i);
		}

		layout.solve();
		int visible = 0;

		for (int i = 0; i < layout.size(); i++) {
			if (layout.visible(i)) visible++;
		}

		assertEquals(1 + 2 * LabelLayout.MAX_NUDGES, visible);
		assertTrue(layout.visible(19), "highest priority always shown");
		assertFalse(layout.visible(0));
	}

	@Test
	void reusableAcrossFrames() {
		LabelLayout layout = new LabelLayout();

		for (int frame = 0; frame < 3; frame++) {
			layout.clear();

			for (int i = 0; i < 500; i++) {
				layout.add(i * 3, (i % 7) * 0.3, 1, 0.5, i % 5);
			}

			layout.solve();
			assertEquals(500, layout.size());
		}
	}
}
