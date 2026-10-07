package com.howtobuild.building;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.minecraft.client.Minecraft;

import net.fabricmc.loader.api.FabricLoader;

import com.howtobuild.HowToBuild;

/**
 * Command output without executing anything: copy to the clipboard, or save as {@code .mcfunction} files (plus an undo
 * file) under {@code <game dir>/howtobuild/exports}, ready for a data pack or a server admin.
 */
public final class CommandExport {
	/** The clipboard is limited so pasting never freezes another program. */
	public static final int CLIPBOARD_LIMIT = 20_000;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private CommandExport() {
	}

	/** Copies the commands (one per line, without a leading slash). Returns the number of commands copied. */
	public static int copy(List<String> commands) {
		int count = Math.min(commands.size(), CLIPBOARD_LIMIT);
		Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", commands.subList(0, count)));
		return count;
	}

	public static Path directory() {
		return FabricLoader.getInstance().getGameDir().resolve(HowToBuild.MOD_ID).resolve("exports");
	}

	/** Saves {@code <name>-<time>.mcfunction} and, if given, a matching {@code _undo} file. Returns the main file. */
	public static Path save(String name, List<String> commands, List<String> undo) throws IOException {
		Path dir = directory();
		Files.createDirectories(dir);
		String base = name.replaceAll("[^a-z0-9_\\-]", "_") + "-" + LocalDateTime.now().format(STAMP);
		Path file = dir.resolve(base + ".mcfunction");
		Files.write(file, commands);

		if (!undo.isEmpty()) Files.write(dir.resolve(base + "_undo.mcfunction"), undo);

		return file;
	}
}
