package com.howtobuild.gui;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.materials.BlockCatalog;
import com.howtobuild.materials.MaterialCategory;

/**
 * Block selector with real 3D item models, search and categories.
 *
 * <ul>
 *     <li>Search matches names, ids, namespaces ({@code create:}), categories and tags ({@code #logs}).</li>
 *     <li>Categories: All, Building, Full blocks, Slabs, Stairs, Walls, Pillars, Decorative, Functional, Modded.</li>
 *     <li>Material types (Blocks / Slabs / Stairs) can be combined; a block is shown if it is <em>any</em> of the
 *     selected types. Types are decided by the block's class and shape, never by its name, so modded blocks work.</li>
 * </ul>
 */
public final class BlockPickerScreen extends BaseScreen {
	private static final int CELL = 20;
	private static final int CATEGORY_WIDTH = 78;

	private final Screen parent;
	private final MaterialRole role;
	private final Consumer<BlockCatalog.Entry> onPick;
	private final EnumSet<ShapeKind> kinds;
	private MaterialCategory category = MaterialCategory.ALL;
	private String query = "";
	private List<BlockCatalog.Entry> entries = List.of();
	private int scrollRows;
	private int gridX;
	private int gridY;
	private int gridW;
	private int gridH;
	private int columns = 1;
	private BlockCatalog.Entry hovered;

	public BlockPickerScreen(Screen parent, MaterialRole role, ShapeKind kind, Consumer<BlockCatalog.Entry> onPick) {
		super(Component.translatable("gui.howtobuild.picker.title"));
		this.parent = parent;
		this.role = role;
		this.onPick = onPick;
		this.kinds = EnumSet.of(kind);
	}

	@Override
	protected void build() {
		int panelW = Math.min(this.width - 16, 380);
		int left = (this.width - panelW) / 2;
		int top = 8;
		int bottom = this.height - 8;
		panel(left, top, left + panelW, bottom);
		String roleName = Component.translatable("role.howtobuild." + role.name().toLowerCase(Locale.ROOT)).getString();
		text(left + PADDING, top + 6, panelW - 2 * PADDING, Component.translatable("gui.howtobuild.picker.heading", roleName).getString(), ACCENT);

		int y = top + 18;
		EditBox search = new EditBox(font, left + PADDING, y, panelW - 2 * PADDING - 3 * 52, CONTROL_HEIGHT, Component.translatable("gui.howtobuild.picker.search"));
		search.setMaxLength(64);
		search.setValue(query);
		search.setHint(Component.translatable("gui.howtobuild.picker.search_hint"));
		search.setResponder(value -> {
			query = value;
			scrollRows = 0;
			filter();
		});
		var tip = tooltip("gui.howtobuild.picker.search.tooltip");

		if (tip != null) search.setTooltip(tip);

		widget(search);
		setInitialFocus(search);

		int kx = left + panelW - PADDING - 3 * 52 + 4;

		for (ShapeKind kind : ShapeKind.values()) {
			dynamicButton(kx, y, 48, () -> Component.literal(kinds.contains(kind) ? "■ " : "□ ").append(option(kind)), () -> {
				if (!kinds.remove(kind)) kinds.add(kind);

				scrollRows = 0;
				filter();
			}, "gui.howtobuild.material_types.tooltip");
			kx += 52;
		}

		y += ROW + 2;
		int categoryY = y;

		for (MaterialCategory c : MaterialCategory.values()) {
			Button b = button(left + PADDING, categoryY, CATEGORY_WIDTH, option(c), () -> {
				category = c;
				scrollRows = 0;
				rebuild();
			}, null);
			b.active = c != category;
			b.setHeight(16);
			categoryY += 17;
		}

		gridX = left + PADDING + CATEGORY_WIDTH + 6;
		gridY = y;
		gridW = left + panelW - PADDING - gridX;
		gridH = bottom - 40 - gridY;
		columns = Math.max(1, gridW / CELL);
		text(gridX, bottom - 34, gridW, this::hoverText, () -> TEXT);
		text(gridX, bottom - 23, gridW, this::countText, () -> MUTED);
		button(left + panelW - PADDING - 60, bottom - 26, 60, Component.translatable("gui.cancel"), this::onClose, null);
		filter();
	}

	private void filter() {
		entries = BlockCatalog.filter(query, category, kinds);
		scrollRows = Math.max(0, Math.min(scrollRows, maxScroll()));
	}

	private int visibleRows() {
		return Math.max(1, gridH / CELL);
	}

	private int maxScroll() {
		int rows = (entries.size() + columns - 1) / columns;
		return Math.max(0, rows - visibleRows());
	}

	private String hoverText() {
		if (hovered == null) return Component.translatable("gui.howtobuild.picker.hint").getString();

		return hovered.name() + " · " + hovered.id() + " · " + hovered.category();
	}

	private String countText() {
		return Component.translatable("gui.howtobuild.picker.count", entries.size()).getString();
	}

	@Override
	protected void drawBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		hovered = null;
		graphics.fill(gridX - 1, gridY - 1, gridX + columns * CELL + 1, gridY + visibleRows() * CELL + 1, 0x80000000);
		int first = scrollRows * columns;
		int last = Math.min(entries.size(), first + visibleRows() * columns);

		for (int i = first; i < last; i++) {
			int col = (i - first) % columns;
			int row = (i - first) / columns;
			int x = gridX + col * CELL;
			int y = gridY + row * CELL;
			BlockCatalog.Entry e = entries.get(i);
			boolean over = inside(mouseX, mouseY, x, y, CELL, CELL);

			if (over) {
				hovered = e;
				graphics.fill(x, y, x + CELL, y + CELL, 0x8033D6FF);
			}

			graphics.item(e.icon(), x + 2, y + 2);
		}

		if (maxScroll() > 0) {
			int barX = gridX + columns * CELL + 2;
			int trackH = visibleRows() * CELL;
			int barH = Math.max(10, trackH * visibleRows() / (maxScroll() + visibleRows()));
			int barY = gridY + (trackH - barH) * scrollRows / maxScroll();
			graphics.fill(barX, gridY, barX + 2, gridY + trackH, 0x40FFFFFF);
			graphics.fill(barX, barY, barX + 2, barY + barH, ACCENT);
		}

		if (entries.isEmpty()) {
			graphics.centeredText(font, Component.translatable("gui.howtobuild.picker.empty").getString(), gridX + gridW / 2, gridY + 20, MUTED);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && inside(event.x(), event.y(), gridX, gridY, columns * CELL, visibleRows() * CELL)) {
			int col = (int) ((event.x() - gridX) / CELL);
			int row = (int) ((event.y() - gridY) / CELL);
			int index = (scrollRows + row) * columns + col;

			if (index >= 0 && index < entries.size()) {
				pick(entries.get(index));
				return true;
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	/** Selects an entry and returns to the previous screen. */
	public void pick(BlockCatalog.Entry entry) {
		onPick.accept(entry);
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (inside(mouseX, mouseY, gridX, gridY, gridW, gridH)) {
			scrollRows = Math.max(0, Math.min(maxScroll(), scrollRows - (int) Math.signum(scrollY) * 2));
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** For tests: the currently filtered entries. */
	public List<BlockCatalog.Entry> entries() {
		return entries;
	}

	public void setFilter(String text, MaterialCategory newCategory, EnumSet<ShapeKind> newKinds) {
		query = text;
		category = newCategory;
		kinds.clear();
		kinds.addAll(newKinds);
		rebuild();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
