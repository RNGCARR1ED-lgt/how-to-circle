package com.howtobuild.building;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.howtobuild.client.BuildSession;
import com.howtobuild.commands.CommandPlan;
import com.howtobuild.commands.CommandPlanner;
import com.howtobuild.commands.StatePlacement;
import com.howtobuild.config.BuildConfig;
import com.howtobuild.geometry.Placement;
import com.howtobuild.materials.MaterialResolver;

/**
 * Everything the command build screen shows before anything is sent: the optimised plan, an undo plan that restores
 * what is there now (as seen by this client), and warnings about what the build would replace.
 *
 * @param plan            commands for the blocks that still need placing
 * @param undo            commands restoring the current blocks at every position the plan changes
 * @param planned         buildable blocks in the preview
 * @param alreadyCorrect  blocks that already match (left out of the plan)
 * @param replaced        non-air blocks that would be overwritten (only when not using {@code keep})
 * @param blockEntities   replaced blocks with contents (chests, signs, …), which undo cannot restore
 * @param unloaded        positions in chunks this client has not loaded (cannot be checked or snapshotted)
 * @param outsideWorld    positions above or below the world's build height (left out)
 */
public record BuildAnalysis(CommandPlan plan, CommandPlan undo, int planned, int alreadyCorrect, int replaced, int blockEntities,
		int unloaded, int outsideWorld) {
	public static final BuildAnalysis EMPTY = new BuildAnalysis(CommandPlan.EMPTY, CommandPlan.EMPTY, 0, 0, 0, 0, 0, 0);

	public boolean destructive() {
		return replaced > 0;
	}

	public static BuildAnalysis analyse(BuildSession.Resolved r, MaterialResolver resolver, BuildConfig config) {
		ClientLevel level = Minecraft.getInstance().level;

		if (level == null) return EMPTY;

		List<StatePlacement> blocks = new ArrayList<>();
		List<StatePlacement> previous = new ArrayList<>();
		List<Placement> placements = r.result().placements();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int planned = 0;
		int correct = 0;
		int replaced = 0;
		int entities = 0;
		int unloaded = 0;
		int outside = 0;

		for (int i = 0; i < placements.size(); i++) {
			if (!r.buildable()[i]) continue;

			Placement p = placements.get(i);
			int x = r.transform().x(p.x());
			int y = r.transform().y(p.y());
			int z = r.transform().z(p.z());
			planned++;

			if (level.isOutsideBuildHeight(y)) {
				outside++;
				continue;
			}

			BlockState target = r.states()[i];
			pos.set(x, y, z);

			if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
				unloaded++;
				blocks.add(new StatePlacement(x, y, z, resolver.commandString(target)));
				previous.add(new StatePlacement(x, y, z, "minecraft:air"));
				continue;
			}

			BlockState current = level.getBlockState(pos);

			if (current.equals(target)) {
				correct++;
				continue;
			}

			if (!current.isAir()) {
				if (config.keepExisting) continue;

				replaced++;

				if (current.getBlock() instanceof EntityBlock) entities++;
			}

			blocks.add(new StatePlacement(x, y, z, resolver.commandString(target)));
			previous.add(new StatePlacement(x, y, z, resolver.commandString(current)));
		}

		CommandPlanner.Options options = new CommandPlanner.Options(config.maxFillVolume, config.keepExisting);
		CommandPlan plan = CommandPlanner.plan(blocks, options);
		CommandPlan undo = CommandPlanner.plan(previous, new CommandPlanner.Options(config.maxFillVolume, false));
		return new BuildAnalysis(plan, undo, planned, correct, replaced, entities, unloaded, outside);
	}
}
