package com.howtobuild.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.howtobuild.HowToBuild;
import com.howtobuild.building.BuildAnalysis;
import com.howtobuild.building.CommandExecutor;
import com.howtobuild.building.CommandExport;
import com.howtobuild.building.CommandPermission;
import com.howtobuild.client.BuildSession;
import com.howtobuild.config.BuildConfig;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.materials.MaterialResolver;

/**
 * The build screen: a summary of exactly what would be built, the output modes (Execute, Copy, Save, Preview list), a
 * confirmation step for anything that replaces existing blocks, and live progress with pause / resume / stop / retry
 * and undo.
 *
 * <p>Execution needs command permission granted by the server; otherwise the screen says why it is unavailable and
 * only offers copying and saving.
 */
public final class CommandBuildScreen extends BaseScreen {
	private static final int LIST_LINES = 200;

	private final @Nullable Screen parent;
	private BuildAnalysis analysis = BuildAnalysis.EMPTY;
	private boolean analysed;
	private boolean confirming;
	private boolean confirmingUndo;
	private boolean showList;
	private int listScroll;
	private String message = "";
	private int messageColor = MUTED;
	private int listX;
	private int listY;
	private int listW;
	private int listH;

	public CommandBuildScreen(@Nullable Screen parent) {
		super(Component.translatable("gui.howtobuild.build.title"));
		this.parent = parent;
	}

	private void analyse() {
		BuildSession.Resolved r = BuildSession.get().resolved();
		HowToBuildConfig config = HowToBuildConfig.get();
		analysis = r == null ? BuildAnalysis.EMPTY : BuildAnalysis.analyse(r, new MaterialResolver(config.materials), config.build);
		analysed = true;
		confirming = false;
	}

	/** For tests and the GUI: the current analysis (computed on first use). */
	public BuildAnalysis analysis() {
		if (!analysed) analyse();
		return analysis;
	}

	@Override
	protected void build() {
		if (!analysed) analyse();

		HowToBuildConfig config = HowToBuildConfig.get();
		CommandExecutor executor = CommandExecutor.get();
		int panelW = Math.min(this.width - 16, 400);
		int left = 8;
		int top = 8;
		int bottom = this.height - 8;
		int inner = panelW - 2 * PADDING;
		int x = left + PADDING;
		panel(left, top, left + panelW, bottom);
		text(x, top + 6, inner, Component.translatable("gui.howtobuild.build.title").getString(), ACCENT);
		int y = top + 20;
		CommandPermission.Status permission = CommandPermission.check();
		boolean permitted = CommandPermission.available(permission);
		BuildAnalysis a = analysis;

		text(x, y, inner, () -> Component.translatable("gui.howtobuild.build.blocks", a.planned(), a.alreadyCorrect(), a.plan().blocks()).getString(), () -> TEXT);
		y += 11;
		text(x, y, inner, () -> Component.translatable("gui.howtobuild.build.commands", a.plan().commands().size(), a.plan().fills(), a.plan().setblocks(),
				String.format(Locale.ROOT, "%.1f", a.plan().compression())).getString(), () -> TEXT);
		y += 11;
		text(x, y, inner, () -> Component.translatable(config.build.keepExisting ? "gui.howtobuild.build.mode_keep" : "gui.howtobuild.build.mode_replace",
				config.build.maxFillVolume).getString(), () -> MUTED);
		y += 11;
		text(x, y, inner, () -> CommandPermission.describe(permission).getString(), () -> permitted
				? (permission == CommandPermission.Status.AVAILABLE ? GOOD : WARNING) : ERROR);
		y += 11;

		if (config.build.method == BuildConfig.Method.NORMAL) {
			for (String line : wrap(Component.translatable("gui.howtobuild.build.method.normal").getString(), inner)) {
				text(x, y, inner, line, MUTED);
				y += 10;
			}
		}

		if (a.destructive()) {
			text(x, y, inner, "⚠ " + Component.translatable("gui.howtobuild.build.warn_replace", a.replaced()).getString(), WARNING);
			y += 11;
		}

		if (a.blockEntities() > 0) {
			text(x, y, inner, "⚠ " + Component.translatable("gui.howtobuild.build.warn_entities", a.blockEntities()).getString(), ERROR);
			y += 11;
		}

		if (a.unloaded() > 0) {
			text(x, y, inner, "⚠ " + Component.translatable("gui.howtobuild.build.warn_unloaded", a.unloaded()).getString(), WARNING);
			y += 11;
		}

		if (a.outsideWorld() > 0) {
			text(x, y, inner, "⚠ " + Component.translatable("gui.howtobuild.build.warn_outside", a.outsideWorld()).getString(), WARNING);
			y += 11;
		}

		y += 3;
		int bw = (inner - 9) / 4;
		dynamicButton(x, y, bw, () -> Component.translatable(confirming
				? "gui.howtobuild.build.confirm" : "gui.howtobuild.build.execute"), this::execute, "gui.howtobuild.build.execute.tooltip").active =
				permitted && !executor.isBusy() && !a.plan().commands().isEmpty();
		button(x + bw + 3, y, bw, Component.translatable("gui.howtobuild.build.copy"), this::copy, "gui.howtobuild.build.copy.tooltip");
		button(x + 2 * (bw + 3), y, bw, Component.translatable("gui.howtobuild.build.save"), this::save, "gui.howtobuild.build.save.tooltip");
		dynamicButton(x + 3 * (bw + 3), y, inner - 3 * (bw + 3), () -> Component.translatable(showList ? "gui.howtobuild.build.hide_list"
				: "gui.howtobuild.build.show_list"), () -> {
			showList = !showList;
			rebuild();
		}, "gui.howtobuild.build.show_list.tooltip");
		y += ROW;
		text(x, y + 2, inner, () -> message, () -> messageColor);
		y += 13;

		// Progress
		text(x, y, inner, this::progressLine, () -> executor.state() == CommandExecutor.State.FAILED ? ERROR : TEXT);
		y += 11;
		text(x, y, inner, () -> executor.lastError() == null ? "" : executor.lastError().getString(), () -> ERROR);
		y += 11;
		int progressY = y;
		progressBar = new int[] {x, progressY, inner};
		y += 6;
		dynamicButton(x, y, bw, () -> Component.translatable(executor.state() == CommandExecutor.State.PAUSED ? "gui.howtobuild.build.resume"
				: "gui.howtobuild.build.pause"), executor::togglePause, "gui.howtobuild.build.pause.tooltip");
		button(x + bw + 3, y, bw, Component.translatable("gui.howtobuild.build.stop"), executor::stop, "gui.howtobuild.build.stop.tooltip");
		button(x + 2 * (bw + 3), y, bw, Component.translatable("gui.howtobuild.build.retry"), executor::retry, "gui.howtobuild.build.retry.tooltip");
		dynamicButton(x + 3 * (bw + 3), y, inner - 3 * (bw + 3), () -> Component.translatable(confirmingUndo ? "gui.howtobuild.build.confirm_undo"
				: "gui.howtobuild.build.undo"), this::undo, "gui.howtobuild.build.undo.tooltip");
		y += ROW;

		listX = x;
		listY = y;
		listW = inner;
		listH = bottom - 30 - y;

		button(x, bottom - 26, 80, Component.translatable("gui.howtobuild.build.refresh"), () -> {
			analyse();
			rebuild();
		}, "gui.howtobuild.build.refresh.tooltip");
		button(left + panelW - PADDING - 60, bottom - 26, 60, Component.translatable("gui.back"), this::onClose, null);
	}

	private int[] progressBar = {0, 0, 0};

	private String progressLine() {
		CommandExecutor e = CommandExecutor.get();

		if (e.state() == CommandExecutor.State.IDLE) return Component.translatable("gui.howtobuild.build.idle").getString();

		return Component.translatable("gui.howtobuild.build.progress_line", Component.translatable(e.stateKey()).getString(), e.sent(), e.total(),
				Math.round(e.progress() * 100), e.succeeded(), e.unchanged(), e.failed(), e.secondsRemaining()).getString();
	}

	private void execute() {
		HowToBuildConfig config = HowToBuildConfig.get();

		if (analysis.destructive() && !confirming) {
			confirming = true;
			say(Component.translatable("gui.howtobuild.build.confirm_hint", analysis.replaced()).getString(), WARNING);
			return;
		}

		confirming = false;
		String name = BuildSession.get().geometry() == null ? "build" : BuildSession.get().geometry().toolId();

		if (CommandExecutor.get().start(analysis.plan(), analysis.undo(), name, config.build.commandsPerTick)) {
			say(Component.translatable("gui.howtobuild.build.started", analysis.plan().commands().size()).getString(), GOOD);
		} else {
			say(CommandPermission.describe(CommandPermission.check()).getString(), ERROR);
		}

		rebuild();
	}

	private void undo() {
		CommandExecutor executor = CommandExecutor.get();

		if (!executor.canUndo()) {
			say(Component.translatable("gui.howtobuild.build.no_undo").getString(), MUTED);
			return;
		}

		if (!confirmingUndo) {
			confirmingUndo = true;
			say(Component.translatable("gui.howtobuild.build.undo_hint").getString(), WARNING);
			return;
		}

		confirmingUndo = false;

		if (executor.undo(HowToBuildConfig.get().build.commandsPerTick)) {
			say(Component.translatable("gui.howtobuild.build.undo_started").getString(), GOOD);
		}
	}

	private void copy() {
		int copied = CommandExport.copy(analysis.plan().commands());
		say(Component.translatable("gui.howtobuild.build.copied", copied, analysis.plan().commands().size()).getString(), GOOD);
	}

	private void save() {
		try {
			String name = BuildSession.get().geometry() == null ? "build" : BuildSession.get().geometry().toolId();
			Path file = CommandExport.save(name, analysis.plan().commands(), analysis.undo().commands());
			say(Component.translatable("gui.howtobuild.build.saved", file.getFileName().toString()).getString(), GOOD);
		} catch (IOException e) {
			HowToBuild.LOGGER.warn("Could not save commands", e);
			say(Component.translatable("gui.howtobuild.build.save_failed").getString(), ERROR);
		}
	}

	private void say(String text, int color) {
		message = text;
		messageColor = color;
	}

	@Override
	protected void drawForeground(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		CommandExecutor e = CommandExecutor.get();
		int[] bar = progressBar;
		graphics.fill(bar[0], bar[1], bar[0] + bar[2], bar[1] + 3, 0xFF2A3A40);
		graphics.fill(bar[0], bar[1], bar[0] + Math.round(bar[2] * e.progress()), bar[1] + 3, e.state() == CommandExecutor.State.FAILED ? ERROR : GOOD);

		if (!showList || listH < 20) return;

		graphics.fill(listX, listY, listX + listW, listY + listH, 0xA0000000);
		List<String> commands = analysis.plan().commands();
		int lines = listH / 10;
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, Math.min(commands.size(), LIST_LINES) - lines)));
		int y = listY + 2;

		for (String command : CommandExecutor.page(commands, listScroll, Math.min(lines, LIST_LINES - listScroll))) {
			graphics.text(font, fit("/" + command, listW - 4), listX + 2, y, 0xFFCFE9F0, false);
			y += 10;
		}

		if (commands.size() > LIST_LINES && listScroll + lines >= LIST_LINES) {
			graphics.text(font, Component.translatable("gui.howtobuild.build.more", commands.size() - LIST_LINES).getString(), listX + 2,
					listY + listH - 10, MUTED, false);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (showList && inside(mouseX, mouseY, listX, listY, listW, listH)) {
			listScroll = Math.max(0, listScroll - (int) Math.signum(scrollY) * 3);
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void tick() {
		super.tick();
		refresh();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
