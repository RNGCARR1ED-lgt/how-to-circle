package com.howtobuild.commands;

import java.util.List;

/**
 * An optimised list of vanilla commands that builds exactly the planned blocks.
 *
 * @param commands     commands without the leading slash, in execution order (bottom-up)
 * @param undo         commands that clear the same blocks back to air (destructive; only offered with a warning)
 * @param blocks       number of blocks the plan places
 * @param fills        number of {@code /fill} commands
 * @param setblocks    number of {@code /setblock} commands
 */
public record CommandPlan(List<String> commands, List<String> undo, int blocks, int fills, int setblocks) {
	public static final CommandPlan EMPTY = new CommandPlan(List.of(), List.of(), 0, 0, 0);

	/** Fraction of commands saved compared to one {@code /setblock} per block (e.g. 0.985 = 98.5 %). */
	public double compression() {
		return blocks == 0 ? 0 : 1.0 - (double) commands.size() / blocks;
	}

	public String mode() {
		if (fills > 0 && setblocks > 0) return "Fill + SetBlock";
		if (fills > 0) return "Fill";
		return setblocks > 0 ? "SetBlock" : "None";
	}
}
