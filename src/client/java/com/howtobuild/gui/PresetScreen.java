package com.howtobuild.gui;

import java.util.List;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.config.PresetStore;

/**
 * Save and load presets: the selected tool, every tool's parameters, materials, details, labels, mirror and build
 * options, stored as JSON files in {@code config/howtobuild/presets}.
 */
public final class PresetScreen extends BaseScreen {
	private final Screen parent;
	private String name = "";
	private String message = "";
	private int messageColor = MUTED;
	private int page;

	public PresetScreen(Screen parent) {
		super(Component.translatable("gui.howtobuild.presets.title"));
		this.parent = parent;
	}

	@Override
	protected void build() {
		int panelW = Math.min(this.width - 16, 300);
		int left = (this.width - panelW) / 2;
		int top = 16;
		int bottom = this.height - 16;
		int inner = panelW - 2 * PADDING;
		int x = left + PADDING;
		panel(left, top, left + panelW, bottom);
		text(x, top + 6, inner, Component.translatable("gui.howtobuild.presets.title").getString(), ACCENT);
		int y = top + 20;

		EditBox box = new EditBox(font, x, y, inner - 64, CONTROL_HEIGHT, Component.translatable("gui.howtobuild.presets.name"));
		box.setMaxLength(48);
		box.setValue(name);
		box.setHint(Component.translatable("gui.howtobuild.presets.name"));
		box.setResponder(v -> name = v);
		widget(box);
		button(x + inner - 60, y, 60, Component.translatable("gui.howtobuild.presets.save"), () -> {
			if (name.isBlank()) {
				say(Component.translatable("gui.howtobuild.presets.need_name").getString(), MUTED);
				return;
			}

			boolean ok = PresetStore.save(name, HowToBuildConfig.get());
			say(Component.translatable(ok ? "gui.howtobuild.presets.saved" : "gui.howtobuild.presets.failed", name).getString(), ok ? GOOD : ERROR);
			rebuild();
		}, "gui.howtobuild.presets.save.tooltip");
		y += ROW;
		text(x, y + 2, inner, () -> message, () -> messageColor);
		y += 14;

		List<String> presets = PresetStore.list();
		int perPage = Math.max(1, (bottom - 30 - y) / ROW);
		page = Math.max(0, Math.min(page, (presets.size() - 1) / perPage));

		if (presets.isEmpty()) text(x, y + 4, inner, Component.translatable("gui.howtobuild.presets.none").getString(), MUTED);

		for (int i = page * perPage; i < Math.min(presets.size(), (page + 1) * perPage); i++) {
			String preset = presets.get(i);
			button(x, y, inner - 64, Component.literal(preset), () -> {
				boolean ok = PresetStore.load(preset);
				say(Component.translatable(ok ? "gui.howtobuild.presets.loaded" : "gui.howtobuild.presets.failed", preset).getString(), ok ? GOOD : ERROR);
			}, "gui.howtobuild.presets.load.tooltip");
			button(x + inner - 60, y, 60, Component.translatable("gui.howtobuild.presets.delete"), () -> {
				PresetStore.delete(preset);
				rebuild();
			}, null);
			y += ROW;
		}

		if (presets.size() > perPage) {
			button(x, bottom - 26, 20, Component.literal("<"), () -> {
				page--;
				rebuild();
			}, null);
			button(x + 24, bottom - 26, 20, Component.literal(">"), () -> {
				page++;
				rebuild();
			}, null);
		}

		button(left + panelW - PADDING - 60, bottom - 26, 60, Component.translatable("gui.back"), this::onClose, null);
	}

	private void say(String text, int color) {
		message = text;
		messageColor = color;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
