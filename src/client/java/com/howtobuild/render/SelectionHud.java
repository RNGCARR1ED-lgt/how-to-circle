package com.howtobuild.render;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import com.howtobuild.building.CommandExecutor;
import com.howtobuild.client.BuildSession;
import com.howtobuild.client.ProgressTracker;
import com.howtobuild.config.BuildConfig;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.input.CentreSelectionHandler;

/**
 * HUD overlays: centre selection help, command build progress, and (for normal building by hand) how many blocks
 * of the preview are already placed.
 */
public final class SelectionHud {
	private static final int ACCENT = 0xFF33D6FF;

	private SelectionHud() {
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();

		if (client.gui.screen() != null) return;

		Font font = client.font;
		CentreSelectionHandler selection = CentreSelectionHandler.get();

		if (selection.isActive()) {
			boolean mirror = selection.mode() == CentreSelectionHandler.Target.MIRROR;
			String title = Component.translatable(mirror ? "hud.howtobuild.selecting_mirror" : "hud.howtobuild.selecting").getString();
			panel(graphics, font, 12, mirror ? 0xFFB070FF : ACCENT, title, selection.statusLine().getString(),
					Component.translatable("hud.howtobuild.help").getString());
			return;
		}

		CommandExecutor executor = CommandExecutor.get();

		if (executor.isBusy()) {
			String title = Component.translatable("hud.howtobuild.building", executor.sent(), executor.total(),
					Math.round(executor.progress() * 100)).getString();
			String status = Component.translatable(executor.stateKey()).getString()
					+ (executor.throttled() ? " · " + Component.translatable("hud.howtobuild.throttled").getString() : "");
			String error = executor.lastError() != null ? executor.lastError().getString() : Component.translatable("hud.howtobuild.build_help").getString();
			int top = 12;
			int width = panel(graphics, font, top, ACCENT, title, status, error);
			int left = graphics.guiWidth() / 2 - width / 2;
			graphics.fill(left, top + 39, left + Math.round(width * executor.progress()), top + 40, 0xFF5CE07A);
			return;
		}

		HowToBuildConfig config = HowToBuildConfig.get();

		if (config.hologram.debug) debug(graphics, font);

		BuildSession.Resolved resolved = config.hologram.visible && config.build.trackProgress ? BuildSession.get().resolved() : null;

		if (resolved != null && config.build.method == BuildConfig.Method.NORMAL) {
			ProgressTracker progress = BuildSession.get().progress();
			int total = resolved.result().blockCount();
			String text = Component.translatable("hud.howtobuild.progress", progress.completed(), total, progress.conflicts()).getString();
			int x = graphics.guiWidth() - font.width(text) - 8;
			graphics.fill(x - 4, 6, graphics.guiWidth() - 4, 20, 0x90101C24);
			graphics.text(font, text, x, 9, 0xFFFFFFFF);
		}
	}

	/** Exact bounds, size and centre of the preview in world coordinates, for checking geometry. */
	private static void debug(GuiGraphicsExtractor graphics, Font font) {
		BuildSession.Resolved r = BuildSession.get().resolved();

		if (r == null || r.result().bounds() == null) return;

		var b = r.result().bounds();
		var t = r.transform();
		var cells = r.result().centreCells();
		long cx = cells.stream().mapToInt(c -> c[0]).distinct().count();
		long cz = cells.stream().mapToInt(c -> c[2]).distinct().count();
		String[] lines = {
				Component.translatable("gui.howtobuild.debug.bounds", t.x(b.minX()), t.x(b.maxX()), t.z(b.minZ()), t.z(b.maxZ()), t.y(b.minY()), t.y(b.maxY())).getString(),
				Component.translatable("gui.howtobuild.debug.size", b.sizeX(), b.sizeZ(), b.sizeY(), r.result().blockCount()).getString(),
				Component.translatable("gui.howtobuild.debug.centre", t.anchorX(), t.anchorY(), t.anchorZ(), cx + "×" + cz,
						t.offsetX(), t.offsetY(), t.offsetZ()).getString()};
		int width = 0;

		for (String line : lines) {
			width = Math.max(width, font.width(line));
		}

		graphics.fill(4, 4, 12 + width, 8 + 11 * lines.length, 0x90101C24);

		for (int i = 0; i < lines.length; i++) {
			graphics.text(font, lines[i], 8, 7 + 11 * i, 0xFFE6F2F5);
		}
	}

	private static int panel(GuiGraphicsExtractor graphics, Font font, int top, int accent, String title, String status, String help) {
		int width = Math.max(font.width(title), Math.max(font.width(status), font.width(help))) + 16;
		int centreX = graphics.guiWidth() / 2;
		int left = centreX - width / 2;
		graphics.fill(left, top, left + width, top + 40, 0xB0101C24);
		graphics.fill(left, top, left + width, top + 1, accent);
		graphics.centeredText(font, title, centreX, top + 4, accent);
		graphics.centeredText(font, status, centreX, top + 16, 0xFFFFFFFF);
		graphics.centeredText(font, help, centreX, top + 28, 0xFFB8C7CC);
		return width;
	}
}
