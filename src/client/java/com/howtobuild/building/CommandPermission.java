package com.howtobuild.building;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/**
 * Detects whether the player may use {@code /fill} and {@code /setblock}, using only what the server already told the
 * client: the player's permission level and the command tree the server sent (which only contains commands the player
 * may run). Nothing is spoofed or bypassed; if the server says no, command building is unavailable and the mod explains
 * why. The server still checks every command itself.
 */
public final class CommandPermission {
	public enum Status {
		/** Operator-level permission: commands can be sent at the configured rate. */
		AVAILABLE,
		/** The server allows the commands but the player is not an operator: commands are sent slowly (spam protection). */
		AVAILABLE_THROTTLED,
		/** Not in a world. */
		NO_WORLD,
		/** The server does not allow the commands for this player. */
		NO_PERMISSION
	}

	private CommandPermission() {
	}

	public static Status check() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientPacketListener connection = client.getConnection();

		if (player == null || connection == null || client.level == null) return Status.NO_WORLD;

		boolean gamemaster = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		boolean inTree = hasCommand(connection, "fill") && hasCommand(connection, "setblock");

		if (gamemaster && inTree) return Status.AVAILABLE;
		if (inTree) return Status.AVAILABLE_THROTTLED;

		return Status.NO_PERMISSION;
	}

	private static boolean hasCommand(ClientPacketListener connection, String name) {
		CommandDispatcher<?> dispatcher = connection.getCommands();
		return dispatcher != null && dispatcher.getRoot().getChild(name) != null;
	}

	public static boolean available(Status status) {
		return status == Status.AVAILABLE || status == Status.AVAILABLE_THROTTLED;
	}

	public static Component describe(Status status) {
		return Component.translatable("permission.howtobuild." + status.name().toLowerCase(java.util.Locale.ROOT));
	}
}
