package com.howtobuild.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import com.howtobuild.HowToBuild;
import com.howtobuild.commands.CommandPlan;

/**
 * Sends a command plan through the normal command mechanism ({@code ClientPacketListener#sendCommand}), exactly as if
 * the player typed each command, a few per tick:
 *
 * <ul>
 *     <li>Operators send {@code commandsPerTick} commands per tick (default 2). Players who may use the commands but are
 *     not operators are subject to the server's spam protection, so for them only one command is sent every
 *     {@link #THROTTLED_INTERVAL} ticks.</li>
 *     <li>Pause, resume, stop and progress. Disconnecting or changing world stops the build.</li>
 *     <li>Errors reported by the server (unknown command, area too large, unloaded position, invalid block, …) pause or
 *     stop the build with an explanation; {@link #retry()} re-sends the commands of the last two seconds, which is
 *     safe because {@code /fill} and {@code /setblock} are idempotent.</li>
 *     <li>While building, the per-command success feedback is hidden from chat on this client only.</li>
 * </ul>
 * The server checks every command; nothing here can grant permissions or bypass protections.
 */
public final class CommandExecutor {
	public static final int THROTTLED_INTERVAL = 25;
	private static final int RETRY_WINDOW_TICKS = 40;
	private static final int ERROR_GRACE_MS = 3000;
	private static final CommandExecutor INSTANCE = new CommandExecutor();

	/** Feedback keys that mean "nothing to do" rather than an error (e.g. re-running a fill that is already done). */
	private static final Set<String> HARMLESS = Set.of("commands.fill.failed", "commands.setblock.failed");
	private static final Set<String> SUCCESS = Set.of("commands.fill.success", "commands.setblock.success");
	/** Errors after which retrying cannot help. */
	private static final Set<String> FATAL = Set.of("command.unknown.command", "command.unknown.argument", "commands.fill.toobig",
			"argument.block.id.invalid", "argument.block.property.unknown", "argument.block.property.invalid", "argument.id.invalid",
			"permissions.requires.player", "command.failed");
	/** Errors that can be fixed by moving closer and retrying. */
	private static final Set<String> RECOVERABLE = Set.of("argument.pos.unloaded", "argument.pos.outofworld");

	public enum State {
		IDLE,
		RUNNING,
		PAUSED,
		FINISHED,
		STOPPED,
		FAILED
	}

	private State state = State.IDLE;
	private List<String> commands = List.of();
	private String label = "";
	private int index;
	private int succeeded;
	private int harmless;
	private int failed;
	private @Nullable Component lastError;
	private @Nullable ClientLevel level;
	private boolean throttled;
	private int perTick = 2;
	private long tickCount;
	private final int[] indexAtTick = new int[64];
	private long lastSendMillis;
	private @Nullable CommandPlan undo;
	private boolean runningUndo;

	private CommandExecutor() {
	}

	public static CommandExecutor get() {
		return INSTANCE;
	}

	public void register() {
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !onFeedback(message));
	}

	/**
	 * Starts sending a plan. {@code undoPlan} is remembered so the build can be reverted afterwards. Returns false (and
	 * does nothing) if the server does not allow the commands.
	 */
	public boolean start(CommandPlan plan, @Nullable CommandPlan undoPlan, String name, int commandsPerTick) {
		CommandPermission.Status status = CommandPermission.check();

		if (!CommandPermission.available(status) || plan.commands().isEmpty()) return false;

		begin(plan.commands(), name, commandsPerTick, status);
		undo = undoPlan;
		runningUndo = false;
		return true;
	}

	/** Reverts the last build by restoring the blocks that were there before it (as seen by this client). */
	public boolean undo(int commandsPerTick) {
		CommandPermission.Status status = CommandPermission.check();

		if (undo == null || !CommandPermission.available(status) || isBusy()) return false;

		begin(undo.commands(), "undo", commandsPerTick, status);
		undo = null;
		runningUndo = true;
		return true;
	}

	private void begin(List<String> list, String name, int commandsPerTick, CommandPermission.Status status) {
		commands = List.copyOf(list);
		label = name;
		index = 0;
		succeeded = 0;
		harmless = 0;
		failed = 0;
		lastError = null;
		level = Minecraft.getInstance().level;
		throttled = status == CommandPermission.Status.AVAILABLE_THROTTLED;
		perTick = Math.max(1, commandsPerTick);
		state = State.RUNNING;
		HowToBuild.LOGGER.info("Command build '{}' started: {} commands{}", name, commands.size(), throttled ? " (throttled)" : "");
	}

	public void pause() {
		if (state == State.RUNNING) state = State.PAUSED;
	}

	public void resume() {
		if (state == State.PAUSED && CommandPermission.available(CommandPermission.check())) state = State.RUNNING;
	}

	public void togglePause() {
		if (state == State.RUNNING) {
			pause();
		} else {
			resume();
		}
	}

	public void stop() {
		if (state == State.RUNNING || state == State.PAUSED) state = State.STOPPED;
	}

	/** Re-sends the commands of the last two seconds and continues. */
	public void retry() {
		if (state != State.PAUSED && state != State.FAILED && state != State.STOPPED) return;

		long from = Math.max(0, tickCount - RETRY_WINDOW_TICKS);
		index = Math.min(index, indexAtTick[(int) (from & 63)]);
		lastError = null;
		state = State.RUNNING;
	}

	public void onDisconnect() {
		if (state == State.RUNNING || state == State.PAUSED) state = State.STOPPED;

		undo = null;
		level = null;
	}

	public void tick(Minecraft client) {
		tickCount++;
		indexAtTick[(int) (tickCount & 63)] = index;

		if (state != State.RUNNING) return;

		ClientPacketListener connection = client.getConnection();

		if (connection == null || client.level == null || client.level != level) {
			state = State.STOPPED;
			lastError = Component.translatable("build.howtobuild.error.world_changed");
			return;
		}

		int budget = throttled ? (tickCount % THROTTLED_INTERVAL == 0 ? 1 : 0) : perTick;

		for (int i = 0; i < budget && index < commands.size(); i++) {
			connection.sendCommand(commands.get(index++));
			lastSendMillis = System.currentTimeMillis();
		}

		if (index >= commands.size()) {
			state = State.FINISHED;
			HowToBuild.LOGGER.info("Command build '{}' finished: {} commands sent", label, commands.size());
		}
	}

	/** Handles server feedback. Returns true if the message should be hidden from chat. */
	private boolean onFeedback(Component message) {
		boolean recent = state == State.RUNNING || state == State.PAUSED || System.currentTimeMillis() - lastSendMillis < ERROR_GRACE_MS;

		if (!recent || commands.isEmpty()) return false;

		String key = translationKey(message);

		if (key == null) return false;

		if (SUCCESS.contains(key)) {
			succeeded++;
			return true;
		}

		if (HARMLESS.contains(key)) {
			harmless++;
			return true;
		}

		if (FATAL.contains(key) || RECOVERABLE.contains(key) || key.startsWith("argument.") || key.startsWith("command.")) {
			failed++;
			lastError = message.copy();

			if (state == State.RUNNING || state == State.FINISHED) {
				state = FATAL.contains(key) ? State.FAILED : State.PAUSED;
			}
		}

		return false;
	}

	/** The first translation key in a message (failure feedback is wrapped in a coloured empty component). */
	static @Nullable String translationKey(Component component) {
		if (component.getContents() instanceof TranslatableContents t) return t.getKey();

		for (Component sibling : component.getSiblings()) {
			String key = translationKey(sibling);

			if (key != null) return key;
		}

		return null;
	}

	public State state() {
		return state;
	}

	public boolean isBusy() {
		return state == State.RUNNING || state == State.PAUSED;
	}

	public int sent() {
		return index;
	}

	public int total() {
		return commands.size();
	}

	public float progress() {
		return commands.isEmpty() ? 0 : index / (float) commands.size();
	}

	public int succeeded() {
		return succeeded;
	}

	public int unchanged() {
		return harmless;
	}

	public int failed() {
		return failed;
	}

	public boolean throttled() {
		return throttled;
	}

	public @Nullable Component lastError() {
		return lastError;
	}

	public boolean canUndo() {
		return undo != null && !isBusy();
	}

	public boolean runningUndo() {
		return runningUndo;
	}

	public String label() {
		return label;
	}

	/** Estimated seconds remaining. */
	public int secondsRemaining() {
		int left = commands.size() - index;
		return throttled ? left * THROTTLED_INTERVAL / 20 : (int) Math.ceil(left / (perTick * 20.0));
	}

	public String stateKey() {
		return "build.howtobuild.state." + state.name().toLowerCase(Locale.ROOT);
	}

	/** Splits a long command list for display. */
	public static List<String> page(List<String> commands, int from, int count) {
		List<String> page = new ArrayList<>();

		for (int i = from; i < Math.min(commands.size(), from + count); i++) {
			page.add(commands.get(i));
		}

		return page;
	}
}
