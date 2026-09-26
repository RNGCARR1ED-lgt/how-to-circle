package com.howtocircle.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import com.howtocircle.client.HologramManager;
import com.howtocircle.config.HowToCircleConfig;
import com.howtocircle.dimensions.LabelFormat;
import com.howtocircle.geometry.ResolvedDimensions;
import com.howtocircle.geometry.ShapePlacement;
import com.howtocircle.geometry.ShapeType;
import com.howtocircle.input.CentreSelectionHandler;

/**
 * The main How to Circle screen: three panels (Circle, Dimensions, Position) on top of the live world, so every change
 * is visible on the hologram immediately.
 */
public final class CircleSettingsScreen extends Screen {
	private static final int ROW = 22;
	private static final int CONTROL_HEIGHT = 20;
	private static final int PADDING = 6;
	private static final int HEADER = 16;
	private static final int PANEL_BG = 0xD0101820;
	private static final int PANEL_BORDER = 0x7033D6FF;
	private static final int ACCENT = 0xFF33D6FF;
	private static final int TEXT = 0xFFE6F2F5;
	private static final int MUTED = 0xFF9DB3BA;
	private static final int WARNING = 0xFFFFC857;
	private static final int COLOR_BOX_WIDTH = 60;

	private final HowToCircleConfig config = HowToCircleConfig.get();
	private final List<Runnable> refreshers = new ArrayList<>();
	private final List<Renderable> widgets = new ArrayList<>();
	private final List<Slider> sliders = new ArrayList<>();
	private final List<int[]> panels = new ArrayList<>();
	private final List<String> panelTitles = new ArrayList<>();

	private EditBox widthBox;
	private EditBox heightBox;
	private EditBox colorBox;
	private boolean widthValid = true;
	private boolean heightValid = true;
	private boolean ignoreResponders;
	private Slider dragging;

	private int circleInfoX;
	private int circleInfoY;
	private int circleInfoWidth;
	private int positionInfoX;
	private int positionInfoY;
	private int swatchX;
	private int swatchY;
	private int swatchSize;

	public CircleSettingsScreen() {
		super(Component.translatable("gui.how-to-circle.title"));
	}

	@Override
	protected void init() {
		refreshers.clear();
		widgets.clear();
		sliders.clear();
		panels.clear();
		panelTitles.clear();

		int gap = 6;
		int columnWidth = Math.max(110, Math.min(176, (this.width - 16 - 2 * gap) / 3));
		int total = columnWidth * 3 + gap * 2;
		int left = (this.width - total) / 2;
		int top = 26;
		int inner = columnWidth - 2 * PADDING;

		buildCirclePanel(left, top, inner);
		buildDimensionPanel(left + columnWidth + gap, top, inner);
		buildPositionPanel(left + 2 * (columnWidth + gap), top, inner);

		widget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(left + total - 60, 4, 60, CONTROL_HEIGHT).build());

		refreshAll();
	}

	// ---------------------------------------------------------------- Circle settings

	private void buildCirclePanel(int x, int top, int inner) {
		int px = x + PADDING;
		int y = top + HEADER + 2;
		int half = (inner - 4) / 2;
		int labelWidth = font.width("W ") + 2;

		widthBox = new EditBox(font, px + labelWidth, y, half - labelWidth, CONTROL_HEIGHT, Component.translatable("gui.how-to-circle.width"));
		widthBox.setMaxLength(4);
		widthBox.setValue(Integer.toString(config.width));
		widthBox.setResponder(value -> onDimensionTyped(value, true));
		widget(widthBox);

		heightBox = new EditBox(font, px + half + 4 + labelWidth, y, half - labelWidth, CONTROL_HEIGHT, Component.translatable("gui.how-to-circle.height"));
		heightBox.setMaxLength(4);
		heightBox.setValue(Integer.toString(config.height));
		heightBox.setResponder(value -> onDimensionTyped(value, false));
		widget(heightBox);
		y += ROW;

		cycle(px, y, inner, () -> Component.translatable("gui.how-to-circle.mode",
				Component.translatable(config.shapeType == ShapeType.CIRCLE ? "gui.how-to-circle.mode.circle" : "gui.how-to-circle.mode.oval")),
				() -> {
					config.shapeType = config.shapeType.next();

					if (config.shapeType == ShapeType.CIRCLE) {
						config.height = config.width;
					}
				}, "gui.how-to-circle.mode.tooltip");
		y += ROW;

		cycle(px, y, inner, () -> Component.translatable("gui.how-to-circle.fill",
				Component.translatable("gui.how-to-circle.fill." + config.fillMode.name().toLowerCase(Locale.ROOT))),
				() -> config.fillMode = config.fillMode.next(), "gui.how-to-circle.fill.tooltip");
		y += ROW;

		cycle(px, y, inner, () -> Component.translatable("gui.how-to-circle.centre",
				Component.translatable("gui.how-to-circle.centre." + config.centreSize.name().toLowerCase(Locale.ROOT)),
				config.resolveDimensions().centreLabel()),
				() -> config.centreSize = config.centreSize.next(), "gui.how-to-circle.centre.tooltip");
		y += ROW;

		Button align = cycle(px, y, inner, this::alignmentLabel, this::cycleAlignment, "gui.how-to-circle.align.tooltip");
		refreshers.add(() -> {
			ResolvedDimensions dims = config.resolveDimensions();
			align.active = dims.centreWidth() == 2 || dims.centreHeight() == 2;
		});
		y += ROW;

		circleInfoX = px;
		circleInfoY = y + 1;
		circleInfoWidth = inner;
		y += 4 * 10 + 4;

		int buttonWidth = (inner - 4) / 2;
		widget(Button.builder(Component.translatable("gui.how-to-circle.generate"), b -> generate())
				.bounds(px, y, buttonWidth, CONTROL_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("gui.how-to-circle.generate.tooltip"))).build());
		widget(Button.builder(Component.translatable("gui.how-to-circle.clear"), b -> {
			HologramManager.get().clear();
			refreshAll();
		}).bounds(px + buttonWidth + 4, y, buttonWidth, CONTROL_HEIGHT).build());
		y += ROW;

		addPanel(x, top, inner, y, "gui.how-to-circle.section.circle");
	}

	private void onDimensionTyped(String value, boolean isWidth) {
		if (ignoreResponders) return;

		Integer parsed = parse(value);

		if (isWidth) {
			widthValid = parsed != null;
			if (parsed != null) config.width = parsed;
		} else {
			heightValid = parsed != null;
			if (parsed != null) config.height = parsed;
		}

		if (config.shapeType == ShapeType.CIRCLE && isWidth && parsed != null) {
			config.height = parsed;
			ignoreResponders = true;
			heightBox.setValue(value);
			ignoreResponders = false;
		}

		refreshMessages();
	}

	private static Integer parse(String value) {
		try {
			int parsed = Integer.parseInt(value.trim());
			return parsed >= ResolvedDimensions.MIN_SIZE && parsed <= ResolvedDimensions.MAX_SIZE ? parsed : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private Component alignmentLabel() {
		ResolvedDimensions dims = config.resolveDimensions();
		ShapePlacement placement = config.placement();

		if (dims.centreWidth() == 1 && dims.centreHeight() == 1) {
			return Component.translatable("gui.how-to-circle.align.exact");
		}

		StringBuilder sides = new StringBuilder();

		if (dims.centreWidth() == 2) {
			sides.append(placement.widthAxis().directionName(config.alignPositiveU));
		}

		if (dims.centreHeight() == 2) {
			if (!sides.isEmpty()) sides.append(' ');
			sides.append(placement.heightAxis().directionName(config.alignPositiveV));
		}

		return Component.translatable("gui.how-to-circle.align", sides.toString());
	}

	private void cycleAlignment() {
		ResolvedDimensions dims = config.resolveDimensions();

		if (dims.centreWidth() == 2 && dims.centreHeight() == 2) {
			// Walk the four blocks around the centre corner: (+,+) → (-,+) → (-,-) → (+,-).
			boolean u = config.alignPositiveU;
			boolean v = config.alignPositiveV;
			config.alignPositiveU = u != v ? u : !u;
			config.alignPositiveV = u != v ? !v : v;
		} else if (dims.centreWidth() == 2) {
			config.alignPositiveU = !config.alignPositiveU;
		} else if (dims.centreHeight() == 2) {
			config.alignPositiveV = !config.alignPositiveV;
		}
	}

	private void generate() {
		if (!widthValid || !heightValid) return;

		HologramManager manager = HologramManager.get();

		if (manager.anchor() == null) {
			manager.setAnchorToPlayer();
		}

		config.showHologram = true;
		HowToCircleConfig.save();
		refreshAll();
	}

	// ---------------------------------------------------------------- Dimension settings

	private void buildDimensionPanel(int x, int top, int inner) {
		int px = x + PADDING;
		int y = top + HEADER + 2;

		toggle(px, y, inner, "gui.how-to-circle.show_dimensions", () -> config.showDimensions, () -> config.showDimensions = !config.showDimensions);
		y += ROW;
		toggle(px, y, inner, "gui.how-to-circle.show_popups", () -> config.showPopups, () -> config.showPopups = !config.showPopups);
		y += ROW;
		Button face = toggle(px, y, inner, "gui.how-to-circle.face_player", () -> config.popupsFacePlayer, () -> config.popupsFacePlayer = !config.popupsFacePlayer);
		refreshers.add(() -> face.active = config.showPopups);
		y += ROW;
		cycle(px, y, inner, () -> Component.translatable("gui.how-to-circle.label_format",
				Component.translatable("gui.how-to-circle.label_format." + config.labelFormat.name().toLowerCase(Locale.ROOT))),
				() -> config.labelFormat = config.labelFormat.next(), "gui.how-to-circle.label_format.tooltip");
		y += ROW;

		sliders.add(new Slider(px, y, inner, "gui.how-to-circle.text_size", 0.5, 3.0, 0.1,
				() -> config.textSize, v -> config.textSize = (float) v, v -> String.format(Locale.ROOT, "%.1f×", v)));
		y += ROW;
		sliders.add(new Slider(px, y, inner, "gui.how-to-circle.label_offset", 0.25, 4.0, 0.25,
				() -> config.labelOffset, v -> config.labelOffset = (float) v, v -> String.format(Locale.ROOT, "%.2f", v)));
		y += ROW;
		sliders.add(new Slider(px, y, inner, "gui.how-to-circle.label_opacity", 0.1, 1.0, 0.05,
				() -> config.labelOpacity, v -> config.labelOpacity = (float) v, Slider::percent));
		y += ROW;
		sliders.add(new Slider(px, y, inner, "gui.how-to-circle.hologram_opacity", 0.05, 0.9, 0.05,
				() -> config.hologramOpacity, v -> config.hologramOpacity = (float) v, Slider::percent));
		y += ROW;

		// Colour selector: preset swatches and a hex field.
		swatchX = px;
		swatchY = y + 11;
		swatchSize = Math.min(12, (inner - 9) / HowToCircleConfig.COLOR_PRESETS.length);
		y += 11 + swatchSize + 4;

		colorBox = new EditBox(font, px + font.width("#") + 3, y, COLOR_BOX_WIDTH, CONTROL_HEIGHT, Component.translatable("gui.how-to-circle.color"));
		colorBox.setMaxLength(6);
		colorBox.setValue(String.format(Locale.ROOT, "%06X", config.hologramColor));
		colorBox.setResponder(value -> {
			if (ignoreResponders) return;

			try {
				if (value.length() == 6) {
					config.hologramColor = Integer.parseInt(value, 16) & 0xFFFFFF;
				}
			} catch (NumberFormatException ignored) {
				// keep the previous colour until the field holds a valid hex value
			}
		});
		widget(colorBox);
		y += ROW;

		addPanel(x, top, inner, y, "gui.how-to-circle.section.dimensions");
	}

	// ---------------------------------------------------------------- Position settings

	private void buildPositionPanel(int x, int top, int inner) {
		int px = x + PADDING;
		int y = top + HEADER + 2;

		positionInfoX = px;
		positionInfoY = y + 1;
		y += 2 * 10 + 4;

		widget(Button.builder(Component.translatable("gui.how-to-circle.select_centre"), b -> {
			HowToCircleConfig.save();
			minecraft.gui.setScreen(null);
			CentreSelectionHandler.get().start(true);
		}).bounds(px, y, inner, CONTROL_HEIGHT).tooltip(Tooltip.create(Component.translatable("gui.how-to-circle.select_centre.tooltip"))).build());
		y += ROW;

		widget(Button.builder(Component.translatable("gui.how-to-circle.use_position"), b -> {
			HologramManager.get().setAnchorToPlayer();
			config.showHologram = true;
			refreshAll();
		}).bounds(px, y, inner, CONTROL_HEIGHT).build());
		y += ROW;

		toggle(px, y, inner, "gui.how-to-circle.lock_centre", () -> config.lockToBlockCentre, () -> config.lockToBlockCentre = !config.lockToBlockCentre,
				"gui.how-to-circle.lock_centre.tooltip");
		y += ROW;

		int third = (inner - 8) / 3;
		widget(Button.builder(Component.translatable("gui.how-to-circle.up"), b -> {
			config.verticalOffset++;
			refreshAll();
		}).bounds(px, y, third, CONTROL_HEIGHT).build());
		widget(Button.builder(Component.translatable("gui.how-to-circle.down"), b -> {
			config.verticalOffset--;
			refreshAll();
		}).bounds(px + third + 4, y, third, CONTROL_HEIGHT).build());
		Button offset = Button.builder(Component.empty(), b -> {
			config.verticalOffset = 0;
			refreshAll();
		}).bounds(px + 2 * (third + 4), y, inner - 2 * (third + 4), CONTROL_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("gui.how-to-circle.offset.tooltip"))).build();
		widget(offset);
		refreshers.add(() -> offset.setMessage(Component.literal(String.format(Locale.ROOT, "%+d", config.verticalOffset))));
		y += ROW;

		int half = (inner - 4) / 2;
		Button rotate = Button.builder(Component.empty(), b -> {
			config.rotated = !config.rotated;
			refreshAll();
		}).bounds(px, y, half, CONTROL_HEIGHT).build();
		widget(rotate);
		refreshers.add(() -> rotate.setMessage(Component.translatable("gui.how-to-circle.rotate", config.rotated ? 90 : 0)));
		Button plane = Button.builder(Component.empty(), b -> {
			config.plane = config.plane.next();
			refreshAll();
		}).bounds(px + half + 4, y, inner - half - 4, CONTROL_HEIGHT).build();
		widget(plane);
		refreshers.add(() -> plane.setMessage(Component.translatable(config.plane == ShapePlacement.Plane.HORIZONTAL
				? "gui.how-to-circle.plane.horizontal" : "gui.how-to-circle.plane.vertical")));
		y += ROW;

		widget(Button.builder(Component.translatable("gui.how-to-circle.reset_position"), b -> {
			config.verticalOffset = 0;
			config.rotated = false;
			config.plane = ShapePlacement.Plane.HORIZONTAL;
			config.alignPositiveU = true;
			config.alignPositiveV = true;

			if (HologramManager.get().hasHologram()) {
				HologramManager.get().setAnchorToPlayer();
			}

			refreshAll();
		}).bounds(px, y, inner, CONTROL_HEIGHT).tooltip(Tooltip.create(Component.translatable("gui.how-to-circle.reset_position.tooltip"))).build());
		y += ROW;

		toggle(px, y, inner, "gui.how-to-circle.block_grid", () -> config.showBlockGrid, () -> config.showBlockGrid = !config.showBlockGrid);
		y += ROW;
		toggle(px, y, inner, "gui.how-to-circle.see_through", () -> config.seeThroughBlocks, () -> config.seeThroughBlocks = !config.seeThroughBlocks,
				"gui.how-to-circle.see_through.tooltip");
		y += ROW;

		addPanel(x, top, inner, y, "gui.how-to-circle.section.position");
	}

	// ---------------------------------------------------------------- Widget helpers

	/** Adds a widget to the screen and remembers it so it can be drawn on top of the panels. */
	private <T extends GuiEventListener & Renderable & NarratableEntry> T widget(T widget) {
		widgets.add(widget);
		return addRenderableWidget(widget);
	}

	private Button toggle(int x, int y, int width, String key, BooleanSupplier value, Runnable action) {
		return toggle(x, y, width, key, value, action, null);
	}

	private Button toggle(int x, int y, int width, String key, BooleanSupplier value, Runnable action, String tooltipKey) {
		Button.Builder builder = Button.builder(Component.empty(), b -> {
			action.run();
			refreshAll();
		}).bounds(x, y, width, CONTROL_HEIGHT);

		if (tooltipKey != null) {
			builder.tooltip(Tooltip.create(Component.translatable(tooltipKey)));
		}

		Button button = builder.build();
		widget(button);
		refreshers.add(() -> button.setMessage(Component.translatable(key,
				Component.translatable(value.getAsBoolean() ? "options.on" : "options.off"))));
		return button;
	}

	private Button cycle(int x, int y, int width, Supplier<Component> label, Runnable action, String tooltipKey) {
		Button button = Button.builder(label.get(), b -> {
			action.run();
			refreshAll();
		}).bounds(x, y, width, CONTROL_HEIGHT).tooltip(Tooltip.create(Component.translatable(tooltipKey))).build();
		widget(button);
		refreshers.add(() -> button.setMessage(label.get()));
		return button;
	}

	private void addPanel(int x, int top, int inner, int bottom, String titleKey) {
		panels.add(new int[] {x, top, x + inner + 2 * PADDING, bottom + PADDING - 2});
		panelTitles.add(Component.translatable(titleKey).getString());
	}

	private void refreshAll() {
		config.sanitize();

		if (config.shapeType == ShapeType.CIRCLE && heightBox != null) {
			ignoreResponders = true;
			heightBox.setValue(Integer.toString(config.height));
			ignoreResponders = false;
		}

		if (heightBox != null) {
			heightBox.active = config.shapeType == ShapeType.OVAL;
		}

		refreshMessages();
	}

	private void refreshMessages() {
		for (Runnable refresher : refreshers) {
			refresher.run();
		}
	}

	// ---------------------------------------------------------------- Rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.centeredText(font, Component.translatable("gui.how-to-circle.title").getString(), this.width / 2, 10, ACCENT);

		for (int i = 0; i < panels.size(); i++) {
			int[] p = panels.get(i);
			roundedPanel(graphics, p[0], p[1], p[2], p[3]);
			graphics.text(font, panelTitles.get(i), p[0] + PADDING, p[1] + 5, ACCENT, false);
			graphics.fill(p[0] + PADDING, p[1] + HEADER - 2, p[2] - PADDING, p[1] + HEADER - 1, PANEL_BORDER);
		}

		// Labels for the dimension boxes
		graphics.text(font, "W", widthBox.getX() - font.width("W ") - 1, widthBox.getY() + 6, widthValid ? MUTED : WARNING, false);
		graphics.text(font, "H", heightBox.getX() - font.width("H ") - 1, heightBox.getY() + 6, heightValid ? MUTED : WARNING, false);
		drawCircleInfo(graphics);
		drawPositionInfo(graphics);

		for (Slider slider : sliders) {
			slider.draw(graphics, mouseX, mouseY);
		}

		drawSwatches(graphics, mouseX, mouseY);

		for (Renderable renderable : widgets) {
			renderable.extractRenderState(graphics, mouseX, mouseY, delta);
		}
	}

	private void drawCircleInfo(GuiGraphicsExtractor graphics) {
		ResolvedDimensions dims = config.resolveDimensions();
		HologramManager manager = HologramManager.get();
		int y = circleInfoY;
		String shape = Component.translatable(dims.width() == dims.height() ? "gui.how-to-circle.info.circle" : "gui.how-to-circle.info.oval",
				dims.width(), dims.height(), dims.centreLabel()).getString();
		graphics.text(font, fit(shape), circleInfoX, y, TEXT, false);
		y += 10;
		String counts = Component.translatable("gui.how-to-circle.info.counts", manager.shape() == null ? 0 : manager.shape().blockCount(),
				manager.sections().size()).getString();
		graphics.text(font, fit(counts), circleInfoX, y, MUTED, false);
		y += 10;

		if (!widthValid || !heightValid) {
			graphics.text(font, fit(Component.translatable("gui.how-to-circle.info.invalid", ResolvedDimensions.MAX_SIZE).getString()), circleInfoX, y, WARNING, false);
			return;
		}

		int lines = 0;

		for (String note : dims.notes()) {
			if (lines++ >= 2) break;
			graphics.text(font, fit(note), circleInfoX, y, WARNING, false);
			y += 10;
		}

		if (dims.mixedParity() && lines < 2) {
			graphics.text(font, fit(Component.translatable("gui.how-to-circle.info.mixed", dims.centreLabel()).getString()), circleInfoX, y, WARNING, false);
		}
	}

	private void drawPositionInfo(GuiGraphicsExtractor graphics) {
		BlockPos anchor = HologramManager.get().anchor();
		String centre = anchor == null
				? Component.translatable("gui.how-to-circle.info.no_centre").getString()
				: Component.translatable("gui.how-to-circle.info.centre", anchor.getX(), anchor.getY(), anchor.getZ()).getString();
		graphics.text(font, fit(centre), positionInfoX, positionInfoY, anchor == null ? MUTED : TEXT, false);
		ShapePlacement placement = config.placement();
		String axes = Component.translatable("gui.how-to-circle.info.axes", placement.widthAxis().name(), placement.heightAxis().name()).getString();
		graphics.text(font, fit(axes), positionInfoX, positionInfoY + 10, MUTED, false);
	}

	private String fit(String text) {
		if (font.width(text) <= circleInfoWidth) return text;

		String ellipsis = "…";
		String result = text;

		while (!result.isEmpty() && font.width(result + ellipsis) > circleInfoWidth) {
			result = result.substring(0, result.length() - 1);
		}

		return result + ellipsis;
	}

	private void drawSwatches(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.text(font, Component.translatable("gui.how-to-circle.color").getString(), swatchX, swatchY - 10, MUTED, false);
		int[] presets = HowToCircleConfig.COLOR_PRESETS;

		for (int i = 0; i < presets.length; i++) {
			int x = swatchX + i * (swatchSize + 1);
			boolean selected = presets[i] == config.hologramColor;
			boolean hovered = mouseX >= x && mouseX < x + swatchSize && mouseY >= swatchY && mouseY < swatchY + swatchSize;
			graphics.fill(x - 1, swatchY - 1, x + swatchSize + 1, swatchY + swatchSize + 1, selected ? 0xFFFFFFFF : hovered ? 0xFF9DB3BA : 0xFF2A3A40);
			graphics.fill(x, swatchY, x + swatchSize, swatchY + swatchSize, 0xFF000000 | presets[i]);
		}

		graphics.text(font, "#", colorBox.getX() - font.width("#") - 2, colorBox.getY() + 6, MUTED, false);
		int previewX = colorBox.getX() + COLOR_BOX_WIDTH + 6;
		graphics.fill(previewX - 1, colorBox.getY() - 1, previewX + 21, colorBox.getY() + CONTROL_HEIGHT + 1, 0xFF9DB3BA);
		graphics.fill(previewX, colorBox.getY(), previewX + 20, colorBox.getY() + CONTROL_HEIGHT, 0xFF000000 | config.hologramColor);
	}

	/** A panel with 2px rounded corners and a thin accent border. */
	private static void roundedPanel(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2) {
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

	// ---------------------------------------------------------------- Input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			for (Slider slider : sliders) {
				if (slider.isMouseOver(event.x(), event.y())) {
					dragging = slider;
					slider.setFromMouse(event.x());
					return true;
				}
			}

			int[] presets = HowToCircleConfig.COLOR_PRESETS;

			for (int i = 0; i < presets.length; i++) {
				int x = swatchX + i * (swatchSize + 1);

				if (event.x() >= x && event.x() < x + swatchSize && event.y() >= swatchY && event.y() < swatchY + swatchSize) {
					config.hologramColor = presets[i];
					ignoreResponders = true;
					colorBox.setValue(String.format(Locale.ROOT, "%06X", presets[i]));
					ignoreResponders = false;
					return true;
				}
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging != null) {
			dragging.setFromMouse(event.x());
			return true;
		}

		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			HowToCircleConfig.save();
			return true;
		}

		return super.mouseReleased(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		HowToCircleConfig.save();
		super.onClose();
	}

	/**
	 * A horizontal slider drawn by the screen itself.
	 */
	private final class Slider {
		private final int x;
		private final int y;
		private final int width;
		private final String key;
		private final double min;
		private final double max;
		private final double step;
		private final java.util.function.DoubleSupplier getter;
		private final java.util.function.DoubleConsumer setter;
		private final java.util.function.DoubleFunction<String> formatter;

		Slider(int x, int y, int width, String key, double min, double max, double step,
				java.util.function.DoubleSupplier getter, java.util.function.DoubleConsumer setter, java.util.function.DoubleFunction<String> formatter) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.key = key;
			this.min = min;
			this.max = max;
			this.step = step;
			this.getter = getter;
			this.setter = setter;
			this.formatter = formatter;
		}

		static String percent(double value) {
			return Math.round(value * 100) + "%";
		}

		boolean isMouseOver(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + CONTROL_HEIGHT;
		}

		void setFromMouse(double mouseX) {
			double t = Math.max(0, Math.min(1, (mouseX - x - 4) / (width - 8)));
			double value = min + t * (max - min);
			value = Math.round(value / step) * step;
			setter.accept(Math.max(min, Math.min(max, value)));
			config.sanitize();
		}

		void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
			boolean hovered = isMouseOver(mouseX, mouseY) || dragging == this;
			double t = (getter.getAsDouble() - min) / (max - min);
			int knob = x + 4 + (int) Math.round(t * (width - 8));
			graphics.fill(x, y, x + width, y + CONTROL_HEIGHT, hovered ? 0xFF1E3038 : 0xFF16242A);
			graphics.fill(x, y, x + width, y + 1, 0xFF2E4A55);
			graphics.fill(x + 4, y + CONTROL_HEIGHT - 5, x + width - 4, y + CONTROL_HEIGHT - 3, 0xFF2E4A55);
			graphics.fill(x + 4, y + CONTROL_HEIGHT - 5, knob, y + CONTROL_HEIGHT - 3, ACCENT);
			graphics.fill(knob - 2, y + CONTROL_HEIGHT - 8, knob + 2, y + CONTROL_HEIGHT, hovered ? 0xFFFFFFFF : 0xFFCFE9F0);
			String label = Component.translatable(key).getString();
			String value = formatter.apply(getter.getAsDouble());
			graphics.text(font, label, x + 4, y + 3, TEXT, false);
			graphics.text(font, value, x + width - 4 - font.width(value), y + 3, ACCENT, false);
		}
	}
}
