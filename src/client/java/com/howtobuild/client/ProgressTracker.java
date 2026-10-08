package com.howtobuild.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import com.howtobuild.geometry.Placement;

/**
 * Compares the planned blocks with the world, a bounded number of positions per tick, so the hologram can show which
 * blocks are already built (completed), still missing (pending) or occupied by something else (conflict). This powers
 * guided normal building without any automation.
 */
public final class ProgressTracker {
	public static final byte PENDING = 0;
	public static final byte COMPLETED = 1;
	public static final byte CONFLICT = 2;
	private static final int PER_TICK = 4096;
	private static final int MAX_BLOCKS = 300_000;

	private BuildSession.Resolved tracked;
	private byte[] status = new byte[0];
	private int cursor;
	private int completed;
	private int conflicts;
	private boolean changed;
	private int version;
	private int ticksSinceVersion;

	public void reset() {
		tracked = null;
		status = new byte[0];
		cursor = 0;
		completed = 0;
		conflicts = 0;
		changed = false;
		version++;
	}

	void tick(BuildSession.Resolved resolved) {
		ClientLevel level = Minecraft.getInstance().level;

		if (level == null) return;

		List<Placement> placements = resolved.result().placements();

		if (placements.size() > MAX_BLOCKS) return;

		if (tracked != resolved) {
			tracked = resolved;
			status = new byte[placements.size()];
			cursor = 0;
			completed = 0;
			conflicts = 0;
		}

		var t = resolved.transform();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int n = 0; n < PER_TICK && !placements.isEmpty(); n++) {
			int i = cursor;
			cursor = (cursor + 1) % placements.size();
			Placement p = placements.get(i);
			pos.set(t.x(p.x()), t.y(p.y()), t.z(p.z()));
			BlockState actual = level.getBlockState(pos);
			byte next = actual.equals(resolved.states()[i]) ? COMPLETED : actual.isAir() || actual.canBeReplaced() ? PENDING : CONFLICT;

			if (next != status[i]) {
				if (status[i] == COMPLETED) completed--;
				if (status[i] == CONFLICT) conflicts--;
				if (next == COMPLETED) completed++;
				if (next == CONFLICT) conflicts++;
				status[i] = next;
				changed = true;
			}
		}

		// Rebuild the mesh at most twice a second when the world changes.
		if (changed && ++ticksSinceVersion >= 10) {
			changed = false;
			ticksSinceVersion = 0;
			version++;
		}
	}

	public byte status(BuildSession.Resolved resolved, int index) {
		return tracked == resolved && index < status.length ? status[index] : PENDING;
	}

	public int completed() {
		return completed;
	}

	public int conflicts() {
		return conflicts;
	}

	public int version() {
		return version;
	}
}
