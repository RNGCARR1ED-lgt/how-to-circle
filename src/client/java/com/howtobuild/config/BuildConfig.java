package com.howtobuild.config;

/**
 * Construction options.
 */
public final class BuildConfig {
	public enum Method {
		/** Guided building by hand: progress tracking and hints, no automation. */
		NORMAL,
		/** Build with vanilla commands (requires command permission). */
		COMMANDS,
		/** Only generate commands to copy or save. */
		EXPORT
	}

	public Method method = Method.COMMANDS;
	/** Commands sent per client tick (20 ticks per second). Conservative by default to avoid flooding the server. */
	public int commandsPerTick = 2;
	public int maxFillVolume = 32768;
	/** Use {@code keep} so existing blocks are never replaced. */
	public boolean keepExisting = false;
	/** Compare the world with the plan and colour finished / conflicting blocks. */
	public boolean trackProgress = true;

	public void sanitize() {
		if (method == null) method = Method.COMMANDS;
		commandsPerTick = Math.max(1, Math.min(20, commandsPerTick));
		maxFillVolume = Math.max(1, Math.min(1_000_000, maxFillVolume));
	}
}
