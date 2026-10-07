package com.howtobuild.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Shared look and widgets for the How to Build screens: translucent panels over the live world (so every change is
 * visible on the hologram straight away), toggles, cycle buttons, number fields with − / + buttons, dynamic text, item
 * icons and tooltips. Screens never pause the game.
 */
abstract class BaseScreen extends Screen {
	static final int ROW = 22;
	static final int CONTROL_HEIGHT = 20;
	static final int PADDING = 6;
	static final int PANEL_BG = 0xD0101820;
	static final int PANEL_BORDER = 0x7033D6FF;
	static final int ACCENT = 0xFF33D6FF;
	static final int TEXT = 0xFFE6F2F5;
	static final int MUTED = 0xFF9DB3BA;
	static final int WARNING = 0xFFFFC857;
	static final int ERROR = 0xFFFF6B6B;
	static final int GOOD = 0xFF6BE08A;

	private final List<Renderable> widgets = new ArrayList<>();
	private final List<Runnable> refreshers = new ArrayList<>();
	private final List<int[]> panels = new ArrayList<>();
	private final List<TextItem> texts = new ArrayList<>();
	private final List<IconItem> icons = new ArrayList<>();

	private record TextItem(int x, int y, int maxWidth, Supplier<String> text, IntSupplier color, boolean centred) {
	}

	private record IconItem(int x, int y, Supplier<ItemStack> stack) {
	}

	protected BaseScreen(Component title) {
		super(title);
	}

	@Override
	protected final void init() {
		widgets.clear();
		refreshers.clear();
		panels.clear();
		texts.clear();
		icons.clear();
		build();
		refresh();
	}

	/** Creates the widgets (called on open, resize and whenever the layout changes). */
	protected abstract void build();

	/** Rebuilds all widgets, keeping state that lives in the config. */
	protected void rebuild() {
		this.init(this.width, this.height);
	}

	protected void refresh() {
		for (Runnable r : refreshers) {
			r.run();
		}
	}

	// ----------------------------------------------------------------- widgets

	protected <T extends GuiEventListener & Renderable & NarratableEntry> T widget(T widget) {
		widgets.add(widget);
		return addRenderableWidget(widget);
	}

	protected void onRefresh(Runnable refresher) {
		refreshers.add(refresher);
	}

	protected void panel(int x1, int y1, int x2, int y2) {
		panels.add(new int[] {x1, y1, x2, y2});
	}

	protected void text(int x, int y, int maxWidth, Supplier<String> text, IntSupplier color) {
		texts.add(new TextItem(x, y, maxWidth, text, color, false));
	}

	protected void text(int x, int y, int maxWidth, String text, int color) {
		texts.add(new TextItem(x, y, maxWidth, () -> text, () -> color, false));
	}

	protected void centredText(int x, int y, int maxWidth, Supplier<String> text, IntSupplier color) {
		texts.add(new TextItem(x, y, maxWidth, text, color, true));
	}

	protected void icon(int x, int y, Supplier<ItemStack> stack) {
		icons.add(new IconItem(x, y, stack));
	}

	protected static @Nullable Tooltip tooltip(@Nullable String key) {
		return key != null && Language.getInstance().has(key) ? Tooltip.create(Component.translatable(key)) : null;
	}

	protected Button button(int x, int y, int w, Component label, Runnable action, @Nullable String tooltipKey) {
		Button.Builder builder = Button.builder(label, b -> action.run()).bounds(x, y, w, CONTROL_HEIGHT);
		Tooltip tooltip = tooltip(tooltipKey);

		if (tooltip != null) builder.tooltip(tooltip);

		return widget(builder.build());
	}

	/** A button whose label is recomputed after every change. */
	protected Button dynamicButton(int x, int y, int w, Supplier<Component> label, Runnable action, @Nullable String tooltipKey) {
		Button button = button(x, y, w, label.get(), () -> {
			action.run();
			refresh();
		}, tooltipKey);
		onRefresh(() -> button.setMessage(label.get()));
		return button;
	}

	/** "Label: ON / OFF". */
	protected Button toggle(int x, int y, int w, String labelKey, BooleanSupplier get, Consumer<Boolean> set, @Nullable String tooltipKey,
			boolean rebuildOnChange) {
		return dynamicButton(x, y, w, () -> Component.translatable(labelKey).append(": ")
				.append(Component.translatable(get.getAsBoolean() ? "options.on" : "options.off")), () -> {
			set.accept(!get.getAsBoolean());
			changed(rebuildOnChange);
		}, tooltipKey);
	}

	/** "Label: Option", cycling through an enum. */
	protected <E extends Enum<E>> Button cycle(int x, int y, int w, String labelKey, Supplier<E> get, Consumer<E> set, E[] values,
			@Nullable String tooltipKey, boolean rebuildOnChange) {
		return dynamicButton(x, y, w, () -> Component.translatable(labelKey).append(": ").append(option(get.get())), () -> {
			set.accept(values[(get.get().ordinal() + 1) % values.length]);
			changed(rebuildOnChange);
		}, tooltipKey);
	}

	static Component option(Enum<?> value) {
		return Component.translatable("enum.howtobuild." + value.name().toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * A labelled number field with − / + buttons (shift: ×10). Invalid input is shown in red and not applied; valid
	 * input is applied immediately so the hologram updates while typing.
	 */
	protected EditBox number(int x, int y, int w, String labelKey, int min, int max, IntSupplier get, IntConsumer set, @Nullable String tooltipKey) {
		int labelWidth = Math.min(w / 2, Math.max(40, font.width(Component.translatable(labelKey).getString()) + 6));
		int fieldWidth = w - labelWidth - 2 * 18;
		boolean[] valid = {true};
		text(x, y + 6, labelWidth - 4, () -> Component.translatable(labelKey).getString(), () -> valid[0] ? MUTED : ERROR);
		EditBox box = new EditBox(font, x + labelWidth + 18, y, fieldWidth, CONTROL_HEIGHT, Component.translatable(labelKey));
		box.setMaxLength(7);
		box.setValue(Integer.toString(get.getAsInt()));
		box.setResponder(value -> {
			try {
				int parsed = Integer.parseInt(value.trim());
				valid[0] = parsed >= min && parsed <= max;

				if (valid[0] && parsed != get.getAsInt()) {
					set.accept(parsed);
					changed(false);
				}
			} catch (NumberFormatException e) {
				valid[0] = value.trim().equals("-") && min < 0;
			}
		});
		Tooltip tooltip = tooltip(tooltipKey);

		if (tooltip != null) box.setTooltip(tooltip);

		widget(box);
		Runnable minus = () -> step(box, get, -1, min, max);
		Runnable plus = () -> step(box, get, 1, min, max);
		widget(Button.builder(Component.literal("−"), b -> minus.run()).bounds(x + labelWidth, y, 18, CONTROL_HEIGHT).build());
		widget(Button.builder(Component.literal("+"), b -> plus.run()).bounds(x + w - 18, y, 18, CONTROL_HEIGHT).build());
		return box;
	}

	private void step(EditBox box, IntSupplier get, int direction, int min, int max) {
		int amount = hasShiftDown() ? 10 : 1;
		int value = Math.max(min, Math.min(max, get.getAsInt() + direction * amount));
		box.setValue(Integer.toString(value));
	}

	static boolean hasShiftDown() {
		return net.minecraft.client.Minecraft.getInstance().hasShiftDown();
	}

	/** Called after a value changed; {@code structural} changes (visibility of other rows) rebuild the widgets. */
	protected void changed(boolean structural) {
		if (structural) rebuild();
		else refresh();
	}

	// ----------------------------------------------------------------- rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		for (int[] p : panels) {
			roundedPanel(graphics, p[0], p[1], p[2], p[3]);
		}

		drawBackground(graphics, mouseX, mouseY);

		for (TextItem t : texts) {
			String s = fit(t.text().get(), t.maxWidth());

			if (t.centred()) graphics.centeredText(font, s, t.x(), t.y(), t.color().getAsInt());
			else graphics.text(font, s, t.x(), t.y(), t.color().getAsInt(), false);
		}

		for (Renderable renderable : widgets) {
			renderable.extractRenderState(graphics, mouseX, mouseY, delta);
		}

		for (IconItem icon : icons) {
			ItemStack stack = icon.stack().get();

			if (stack != null && !stack.isEmpty()) graphics.item(stack, icon.x(), icon.y());
		}

		drawForeground(graphics, mouseX, mouseY);
	}

	/** Custom drawing below the widgets. */
	protected void drawBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
	}

	/** Custom drawing above the widgets. */
	protected void drawForeground(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
	}

	protected String fit(String text, int maxWidth) {
		if (maxWidth <= 0 || font.width(text) <= maxWidth) return text;

		String ellipsis = "…";
		String result = text;

		while (!result.isEmpty() && font.width(result + ellipsis) > maxWidth) {
			result = result.substring(0, result.length() - 1);
		}

		return result + ellipsis;
	}

	/** Word-wraps text to a width. */
	protected List<String> wrap(String text, int maxWidth) {
		List<String> lines = new ArrayList<>();

		for (String paragraph : text.split("\n")) {
			StringBuilder line = new StringBuilder();

			for (String word : paragraph.split(" ")) {
				String candidate = line.isEmpty() ? word : line + " " + word;

				if (font.width(candidate) > maxWidth && !line.isEmpty()) {
					lines.add(line.toString());
					line = new StringBuilder(word);
				} else {
					line = new StringBuilder(candidate);
				}
			}

			lines.add(line.toString());
		}

		return lines;
	}

	/** A panel with 2px rounded corners and a thin accent border. */
	static void roundedPanel(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2) {
		graphics.fill(x1 + 2, y1, x2 - 2, y1 + 1, PANEL_BORDER);
		graphics.fill(x1 + 2, y2 - 1, x2 - 2, y2, PANEL_BORDER);
		graphics.fill(x1, y1 + 2, x1 + 1, y2 - 2, PANEL_BORDER);
		graphics.fill(x2 - 1, y1 + 2, x2, y2 - 2, PANEL_BORDER);
		graphics.fill(x1 + 1, y1 + 1, x1 + 2, y1 + 2, PANEL_BORDER);
		graphics.fill(x2 - 2, y1 + 1, x2 - 1, y1 + 2, PANEL_BORDER);
		graphics.fill(x1 + 1, y2 - 2, x1 + 2, y2 - 1, PANEL_BORDER);
		graphics.fill(x2 - 2, y2 - 2, x2 - 1, y2 - 1, PANEL_BORDER);
		graphics.fill(x1 + 2, y1 + 1, x2 - 2, y2 - 1, PANEL_BG);
		graphics.fill(x1 + 1, y1 + 2, x1 + 2, y2 - 2, PANEL_BG);
		graphics.fill(x2 - 2, y1 + 2, x2 - 1, y2 - 2, PANEL_BG);
	}

	static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
