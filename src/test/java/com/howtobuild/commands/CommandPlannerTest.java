package com.howtobuild.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.howtobuild.TestShapes;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.geometry.Voxels;

class CommandPlannerTest {
	/** Same state naming the client resolver produces: block id per role plus the shape's properties. */
	static List<StatePlacement> states(GeometryResult r, int ox, int oy, int oz) {
		List<StatePlacement> list = new ArrayList<>();

		for (Placement p : r.placements()) {
			String block = switch (p.shape().kind()) {
				case FULL -> "minecraft:stone_bricks";
				case SLAB -> "minecraft:stone_brick_slab";
				case STAIRS -> "minecraft:stone_brick_stairs";
			};
			String props = p.shape().properties();
			String role = p.role().key();
			list.add(new StatePlacement(p.x() + ox, p.y() + oy, p.z() + oz, block + (props.isEmpty() ? "" : "[" + props + "]") + "|" + role));
		}

		return list;
	}

	@Test
	void commandsReproduceTheGeometryExactly() {
		for (String tool : List.of("circle", "cylinder", "sphere", "dome", "spiral", "corridor")) {
			GeometryResult r = TestShapes.generate(tool, TestShapes.types(ShapeKind.BLOCKS, ShapeKind.SLABS, ShapeKind.STAIRS));
			List<StatePlacement> blocks = states(r, 100, 64, -200);
			CommandPlan plan = CommandPlanner.plan(blocks, CommandPlanner.Options.DEFAULT);
			Map<Long, String> expected = new HashMap<>();
			blocks.forEach(b -> expected.put(Voxels.pack(b.x(), b.y(), b.z()), b.state()));
			assertEquals(expected, CommandPlanner.expand(plan.commands()), tool + ": every block placed with its exact state, nothing else");
			assertEquals(blocks.size(), plan.blocks());
			assertTrue(plan.commands().size() <= blocks.size());
			assertEquals(plan.commands().size(), plan.fills() + plan.setblocks());
		}
	}

	@Test
	void straightRunsBecomeOneFill() {
		List<StatePlacement> blocks = new ArrayList<>();

		for (int x = 1; x <= 4; x++) {
			blocks.add(new StatePlacement(x, 64, 1, "minecraft:stone"));
		}

		CommandPlan plan = CommandPlanner.plan(blocks, CommandPlanner.Options.DEFAULT);
		assertEquals(List.of("fill 1 64 1 4 64 1 minecraft:stone"), plan.commands());
		assertEquals(0.75, plan.compression(), 1e-9);
	}

	@Test
	void differentStatesAreNeverMerged() {
		List<StatePlacement> blocks = List.of(
				new StatePlacement(0, 0, 0, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]"),
				new StatePlacement(1, 0, 0, "minecraft:oak_stairs[facing=west,half=bottom,shape=straight]"));
		CommandPlan plan = CommandPlanner.plan(blocks, CommandPlanner.Options.DEFAULT);
		assertEquals(2, plan.setblocks());
		assertEquals(0, plan.fills());
	}

	@Test
	void largeBoxesAreSplitBelowTheLimit() {
		List<StatePlacement> blocks = new ArrayList<>();

		for (int x = 0; x < 50; x++) {
			for (int y = 0; y < 30; y++) {
				for (int z = 0; z < 30; z++) {
					blocks.add(new StatePlacement(x, y, z, "minecraft:stone"));
				}
			}
		}

		CommandPlan plan = CommandPlanner.plan(blocks, CommandPlanner.Options.DEFAULT);
		assertTrue(plan.fills() >= 2, "45,000 blocks need more than one fill");

		for (String command : plan.commands()) {
			String[] p = command.split(" ");
			long volume = (long) (Integer.parseInt(p[4]) - Integer.parseInt(p[1]) + 1) * (Integer.parseInt(p[5]) - Integer.parseInt(p[2]) + 1)
					* (Integer.parseInt(p[6]) - Integer.parseInt(p[3]) + 1);
			assertTrue(volume <= CommandPlanner.DEFAULT_MAX_VOLUME);
		}

		assertEquals(45_000, CommandPlanner.expand(plan.commands()).size());
	}

	@Test
	void keepModeAndUndo() {
		List<StatePlacement> blocks = List.of(new StatePlacement(0, 0, 0, "minecraft:stone"), new StatePlacement(5, 0, 0, "minecraft:dirt"));
		CommandPlan plan = CommandPlanner.plan(blocks, new CommandPlanner.Options(32768, true));
		assertTrue(plan.commands().stream().allMatch(c -> c.endsWith(" keep")));
		assertEquals(2, CommandPlanner.expand(plan.undo()).size());
		assertTrue(CommandPlanner.expand(plan.undo()).values().stream().allMatch("minecraft:air"::equals));
	}

	@Test
	void commandsAreOrderedBottomUp() {
		GeometryResult r = TestShapes.generate("sphere");
		CommandPlan plan = CommandPlanner.plan(states(r, 0, 0, 0), CommandPlanner.Options.DEFAULT);
		int last = Integer.MIN_VALUE;

		for (String c : plan.commands()) {
			int y = Integer.parseInt(c.split(" ")[2]);
			assertTrue(y >= last);
			last = y;
		}
	}
}
