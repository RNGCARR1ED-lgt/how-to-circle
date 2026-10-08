package com.howtobuild.gui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import com.howtobuild.building.CommandPermission;
import com.howtobuild.client.BuildSession;
import com.howtobuild.commands.CommandPlan;
import com.howtobuild.config.BuildConfig;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.config.MaterialSlot;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailPreset;
import com.howtobuild.details.MaterialPattern;
import com.howtobuild.details.MaterialVariation;
import com.howtobuild.details.SlabMode;
import com.howtobuild.dimensions.DimensionFormat;
import com.howtobuild.dimensions.LabelComponent;
import com.howtobuild.dimensions.LabelFormat;
import com.howtobuild.dimensions.LabelPlacement;
import com.howtobuild.geometry.Box;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.input.CentreSelectionHandler;
import com.howtobuild.materials.BlockCatalog;
import com.howtobuild.materials.MaterialResolver;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.ToolParameter;
import com.howtobuild.tools.ToolRegistry;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.capability.Detailable;
import com.howtobuild.tools.capability.MaterialAssignable;
import com.howtobuild.tools.capability.Rotatable;
import com.howtobuild.transform.MirrorAxis;
import com.howtobuild.transform.MirrorMode;

/**
 * The main How to Build screen.
 *
 * <pre>
 * ┌ Tools ─────┐┌ Geometry │ Materials │ Details │ Labels │ Mirror │ Build ┐
 * │ ★ Circle   ││ (controls generated from the tool's parameters,       │
 * │ ★ Spiral   ││  scrollable, grouped in sections, tooltips on every    │
 * │   …        ││  control; advanced options only in Advanced mode)      │
 * │ Simple/Adv ││ status: size · blocks · warnings                       │
 * └────────────┘└────────────────────────────────────────────────────────┘
 *  [Select centre] [Use my position] [Clear]   [Preview: shown] [Build…]   [Presets] [?] [Done]
 * </pre>
 *
 * The panel covers only the left part of the screen and the game keeps running, so the hologram (the live preview)
 * updates as values change. <b>Preview</b> only shows or hides the hologram; <b>Build…</b> opens the separate build
 * screen, which always asks for confirmation before anything is changed in the world.
 */
public final class HowToBuildScreen extends BaseScreen {
	enum Tab {
		GEOMETRY,
		CENTER,
		MATERIALS,
		DETAILS,
		LABELS,
		MIRROR,
		BUILD
	}

	private static final int HEADER_ROW = 16;
	private static final int COLUMN_GAP = 10;
	private static final int TWO_COLUMN_MIN = 500;
	private static final String[] UNITS = {"", " blocks", " m"};

	private interface RowBuilder {
		void build(int x, int y, int w);
	}

	/** A form row; header rows carry the key of the section they start (used for columns and collapsing). */
	private record Row(int height, RowBuilder builder, String header) {
		Row(int height, RowBuilder builder) {
			this(height, builder, null);
		}
	}

	/** A section: its header (or none) and its rows, placed whole into one column. */
	private record Section(Row header, List<Row> rows) {
		int height(boolean collapsed) {
			int h = header == null ? 0 : header.height();

			if (!collapsed) {
				for (Row r : rows) {
					h += r.height();
				}
			}

			return h + 4;
		}
	}

	private int scroll;
	private int contentHeight;
	private int formX;
	private int formY;
	private int formW;
	private int formH;
	private int colW;
	private int toolColumn = 104;
	private boolean help;
	private final List<int[]> headerAreas = new ArrayList<>();
	private final List<String> headerKeys = new ArrayList<>();
	private String hoverInfo = "";
	private final List<int[]> slots = new ArrayList<>();
	private final List<Runnable> slotActions = new ArrayList<>();
	private final List<String> slotNames = new ArrayList<>();

	public HowToBuildScreen() {
		super(Component.translatable("gui.howtobuild.title"));
	}

	private static HowToBuildConfig config() {
		return HowToBuildConfig.get();
	}

	private Tab tab() {
		try {
			return Tab.valueOf(config().tab);
		} catch (IllegalArgumentException e) {
			return Tab.GEOMETRY;
		}
	}

	@Override
	protected void build() {
		BuildSession session = BuildSession.get();

		if (!session.hasAnchor()) session.setAnchorToPlayer();

		slots.clear();
		slotActions.clear();
		slotNames.clear();
		headerAreas.clear();
		headerKeys.clear();
		// Responsive: about two thirds of the screen (the rest shows the live hologram), within sensible limits.
		int panelW = Math.min(this.width - 16, Math.max(360, Math.min(820, Math.round(this.width * 0.68F))));
		int left = 8;
		int top = 22;
		int bottom = this.height - 28;
		toolColumn = Math.max(96, Math.min(140, panelW / 5));
		panel(left, top, left + panelW, bottom);
		centredText(left + panelW / 2, 8, panelW, () -> Component.translatable("gui.howtobuild.title").getString(), () -> ACCENT);

		buildToolList(left + PADDING, top + PADDING, bottom - PADDING);

		int cx = left + toolColumn + PADDING * 2;
		int cw = panelW - toolColumn - PADDING * 3;
		buildTabs(cx, top + PADDING, cw);

		formX = cx;
		formY = top + PADDING + CONTROL_HEIGHT + 6;
		formW = cw;
		formH = bottom - 36 - formY;
		int columns = formW >= TWO_COLUMN_MIN ? 2 : 1;
		colW = (formW - 6 - COLUMN_GAP * (columns - 1)) / columns;
		buildForm(columns);
		buildStatus(cx, bottom - 34, cw);
		buildBottomBar(left, this.height - 24, panelW);
	}

	// ----------------------------------------------------------------- tool list

	private void buildToolList(int x, int y, int bottom) {
		List<BuildTool> tools = new ArrayList<>(ToolRegistry.all());
		List<String> favourites = config().favorites;
		tools.sort((a, b) -> Boolean.compare(!favourites.contains(a.id()), !favourites.contains(b.id())));
		int rows = tools.size() + 3;
		int rowH = Math.max(14, Math.min(20, (bottom - y - 14) / rows));
		text(x, y + 2, toolColumn, Component.translatable("gui.howtobuild.tools").getString(), ACCENT);
		y += 14;

		for (BuildTool tool : tools) {
			boolean selected = tool.id().equals(config().tool);
			boolean favourite = favourites.contains(tool.id());
			Component name = Component.literal(selected ? "▶ " : "").append(Component.translatable(tool.translationKey()));
			Button b = widget(Button.builder(name, btn -> selectTool(tool)).bounds(x, y, toolColumn - 18, rowH).build());
			var tip = tooltip(tool.translationKey() + ".desc");

			if (tip != null) b.setTooltip(tip);

			widget(Button.builder(Component.literal(favourite ? "★" : "☆"), btn -> toggleFavourite(tool.id()))
					.bounds(x + toolColumn - 17, y, 17, rowH).tooltip(net.minecraft.client.gui.components.Tooltip.create(
							Component.translatable("gui.howtobuild.favourite"))).build());
			y += rowH + 1;
		}

		// Mirror is a transform rather than a shape; its entry opens the Mirror tab.
		widget(Button.builder(Component.translatable("tool.howtobuild.mirror"), btn -> setTab(Tab.MIRROR))
				.bounds(x, y, toolColumn, rowH).tooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.translatable("tool.howtobuild.mirror.desc"))).build());
		y += rowH + 4;
		dynamicButton(x, y, toolColumn, () -> Component.translatable(config().advanced ? "gui.howtobuild.mode.advanced" : "gui.howtobuild.mode.simple"),
				() -> {
					config().advanced = !config().advanced;
					rebuild();
				}, "gui.howtobuild.mode.tooltip");
	}

	private void selectTool(BuildTool tool) {
		config().tool = tool.id();
		scroll = 0;

		if (tab() == Tab.MIRROR) config().tab = Tab.GEOMETRY.name();

		HowToBuildConfig.save();
		rebuild();
	}

	private void toggleFavourite(String id) {
		List<String> favourites = config().favorites;

		if (!favourites.remove(id)) favourites.add(id);

		HowToBuildConfig.save();
		rebuild();
	}

	private void buildTabs(int x, int y, int w) {
		Tab[] tabs = Tab.values();
		int tw = w / tabs.length;

		for (int i = 0; i < tabs.length; i++) {
			Tab t = tabs[i];
			String key = "gui.howtobuild.tab." + t.name().toLowerCase(Locale.ROOT);
			Button b = button(x + i * tw, y, i == tabs.length - 1 ? w - i * tw : tw - 1, Component.translatable(key), () -> setTab(t), key + ".tooltip");
			b.active = t != tab();
		}
	}

	private void setTab(Tab t) {
		config().tab = t.name();
		scroll = 0;
		help = false;
		rebuild();
	}

	// ----------------------------------------------------------------- form

	private void buildForm(int columns) {
		List<Row> rows = switch (tab()) {
			case GEOMETRY -> geometryRows();
			case CENTER -> centreTabRows();
			case MATERIALS -> materialRows();
			case DETAILS -> detailRows();
			case LABELS -> labelRows();
			case MIRROR -> mirrorRows();
			case BUILD -> buildRows();
		};

		// Split into sections at header rows; each section goes whole into the currently shorter column.
		List<Section> sections = new ArrayList<>();
		Section current = new Section(null, new ArrayList<>());

		for (Row row : rows) {
			if (row.header() != null) {
				if (current.header() != null || !current.rows().isEmpty()) sections.add(current);
				current = new Section(row, new ArrayList<>());
			} else {
				current.rows().add(row);
			}
		}

		if (current.header() != null || !current.rows().isEmpty()) sections.add(current);

		int[] heights = new int[columns];
		List<List<Section>> placed = new ArrayList<>();

		for (int i = 0; i < columns; i++) {
			placed.add(new ArrayList<>());
		}

		for (Section section : sections) {
			int column = 0;

			for (int i = 1; i < columns; i++) {
				if (heights[i] < heights[column]) column = i;
			}

			// The untitled introduction stays on top of the first column.
			if (section.header() == null) column = 0;

			placed.get(column).add(section);
			heights[column] += section.height(collapsed(section));
		}

		contentHeight = 0;

		for (int h : heights) {
			contentHeight = Math.max(contentHeight, h);
		}

		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - formH)));

		for (int column = 0; column < columns; column++) {
			int x = formX + column * (colW + COLUMN_GAP);
			int y = formY - scroll;

			for (Section section : placed.get(column)) {
				boolean collapsed = collapsed(section);
				List<Row> visible = new ArrayList<>();

				if (section.header() != null) visible.add(section.header());
				if (!collapsed) visible.addAll(section.rows());

				for (Row row : visible) {
					if (y >= formY && y + row.height() <= formY + formH) row.builder().build(x, y, colW);

					y += row.height();
				}

				y += 4;
			}
		}
	}

	private boolean collapsed(Section section) {
		return section.header() != null && config().collapsedSections.contains(section.header().header());
	}

	/** A collapsible section header: click to fold or unfold the section. */
	private Row header(String key) {
		return new Row(HEADER_ROW, (x, y, w) -> {
			boolean folded = config().collapsedSections.contains(key);
			text(x, y + 4, w, (folded ? "▶ " : "▼ ") + Component.translatable(key).getString(), ACCENT);
			headerAreas.add(new int[] {x, y, w, HEADER_ROW});
			headerKeys.add(key);
		}, key);
	}

	private Row note(String text, int color) {
		return new Row(11, (x, y, w) -> text(x, y + 1, w, text, color));
	}

	private Row row(RowBuilder builder) {
		return new Row(ROW, builder);
	}

	/** Two controls side by side. */
	private Row pair(RowBuilder first, RowBuilder second) {
		return row((x, y, w) -> {
			int half = (w - 4) / 2;
			first.build(x, y, half);
			second.build(x + half + 4, y, w - half - 4);
		});
	}

	private void wrapNotes(List<Row> rows, String text, int color) {
		for (String line : wrap(text, colW - 4)) {
			rows.add(note(line, color));
		}
	}

	// ---- Geometry

	private List<Row> geometryRows() {
		HowToBuildConfig c = config();
		BuildTool tool = c.activeTool();
		ToolSettings settings = c.settings(tool);
		List<Row> rows = new ArrayList<>();
		wrapNotes(rows, Component.translatable(tool.translationKey() + ".desc").getString(), MUTED);
		String section = null;

		for (ToolParameter p : tool.parameters()) {
			// Centre parameters live in the shared Center section below.
			if (p.sectionKey().equals("centre") || !p.isVisible(settings) || p.isAdvanced() && !c.advanced) continue;

			if (!p.sectionKey().equals(section)) {
				if ("circle".equals(section)) rows.add(circleButtons(tool));

				section = p.sectionKey();
				rows.add(header("section.howtobuild." + section));
			}

			rows.add(row((x, y, w) -> parameter(tool, p, x, y, w)));
		}

		if ("circle".equals(section)) rows.add(circleButtons(tool));

		if (tool.usesMaterialTypes()) {
			rows.add(header("gui.howtobuild.material_types"));
			rows.add(row((x, y, w) -> {
				ShapeKind[] kinds = ShapeKind.values();
				int bw = (w - 8) / 3;

				for (int i = 0; i < kinds.length; i++) {
					ShapeKind kind = kinds[i];
					dynamicButton(x + i * (bw + 4), y, bw, () -> Component.literal(c.materialTypes.contains(kind) ? "■ " : "□ ")
							.append(option(kind)), () -> {
						if (c.materialTypes.contains(kind)) {
							if (c.materialTypes.size() > 1) c.materialTypes.remove(kind);
						} else {
							c.materialTypes.add(kind);
						}

						c.sanitize();
						rebuild();
					}, "gui.howtobuild.material_types.tooltip");
				}
			}));

			if (c.materialTypes.contains(ShapeKind.SLABS)) {
				rows.add(row((x, y, w) -> cycle(x, y, w, "gui.howtobuild.slab_mode", () -> c.slabMode, v -> c.slabMode = v, SlabMode.values(),
						"gui.howtobuild.slab_mode.tooltip", false)));
			}
		}

		rows.addAll(centreRows(false));

		if (tool instanceof Rotatable) {
			rows.add(header("gui.howtobuild.placement"));
			rows.add(row((x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable("gui.howtobuild.rotation", c.rotation * 90),
					() -> c.rotation = (c.rotation + 1) & 3, "gui.howtobuild.rotation.tooltip")));
		}

		return rows;
	}

	/** The Center tab: the shared centre section plus how the centre is shown in the world. */
	private List<Row> centreTabRows() {
		HowToBuildConfig c = config();
		List<Row> rows = centreRows(true);
		rows.add(header("gui.howtobuild.centre.display"));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.hologram.centre", () -> c.hologram.showCentre, v -> c.hologram.showCentre = v,
						"gui.howtobuild.hologram.centre.tooltip", false),
				(x, y, w) -> toggle(x, y, w, "gui.howtobuild.hologram.guides", () -> c.hologram.showGuides, v -> c.hologram.showGuides = v,
						"gui.howtobuild.hologram.guides.tooltip", false)));
		rows.add(row((x, y, w) -> toggle(x, y, w, "gui.howtobuild.debug", () -> c.hologram.debug, v -> c.hologram.debug = v,
				"gui.howtobuild.debug.tooltip", false)));
		return rows;
	}

	/**
	 * The shared Center section, identical for every tool: centre mode, the selected centre's coordinates and size, the
	 * X / Y / Z offset (always visible), Select Center / My Position, and the exact resulting bounds.
	 */
	private List<Row> centreRows(boolean full) {
		HowToBuildConfig c = config();
		BuildTool tool = c.activeTool();
		ToolSettings settings = c.settings(tool);
		List<Row> rows = new ArrayList<>();
		rows.add(header("gui.howtobuild.centre"));

		for (ToolParameter p : tool.parameters()) {
			if (p.sectionKey().equals("centre") && p.isVisible(settings) && (!p.isAdvanced() || c.advanced)) {
				rows.add(row((x, y, w) -> parameter(tool, p, x, y, w)));
			}
		}

		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, this::centreLine, () -> BuildSession.get().hasAnchor() ? TEXT : MUTED)));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, this::centreSizeLine, () -> MUTED)));
		rows.add(row((x, y, w) -> {
			int third = (w - 8) / 3;
			number(x, y, third, "gui.howtobuild.offset_x", -512, 512, () -> c.offsetX, v -> c.offsetX = v, "gui.howtobuild.offset.tooltip");
			number(x + third + 4, y, third, "gui.howtobuild.offset_y", -512, 512, () -> c.offsetY, v -> c.offsetY = v, "gui.howtobuild.offset.tooltip");
			number(x + 2 * (third + 4), y, w - 2 * (third + 4), "gui.howtobuild.offset_z", -512, 512, () -> c.offsetZ, v -> c.offsetZ = v,
					"gui.howtobuild.offset.tooltip");
		}));
		rows.add(pair((x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.select_centre"), this::selectCentre,
						"gui.howtobuild.select_centre.tooltip"),
				(x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.use_position"), () -> {
					BuildSession.get().setAnchorToPlayer();
					refresh();
				}, "gui.howtobuild.use_position.tooltip")));

		if (full || c.advanced) {
			rows.add(pair((x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable("gui.howtobuild.align_x", c.alignX ? "+X" : "−X"),
							() -> c.alignX = !c.alignX, "gui.howtobuild.align.tooltip"),
					(x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable("gui.howtobuild.align_z", c.alignZ ? "+Z" : "−Z"),
							() -> c.alignZ = !c.alignZ, "gui.howtobuild.align.tooltip")));
			rows.add(pair((x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable("gui.howtobuild.align_y", c.alignY ? "+Y" : "−Y"),
							() -> c.alignY = !c.alignY, "gui.howtobuild.align.tooltip"),
					(x, y, w) -> toggle(x, y, w, "gui.howtobuild.lock_centre", () -> c.lockToBlockCentre, v -> c.lockToBlockCentre = v,
							"gui.howtobuild.lock_centre.tooltip", false)));
		}

		if (full || c.hologram.debug) {
			rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> debugLine(0), () -> MUTED)));
			rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> debugLine(1), () -> MUTED)));
		}

		return rows;
	}

	private void selectCentre() {
		CentreSelectionHandler.get().start(CentreSelectionHandler.Target.SHAPE, true);
		onClose();
	}

	/** "Center: X 147 · Y 77 · Z -55" (plus the effective origin when an offset is set). */
	private String centreLine() {
		BuildSession session = BuildSession.get();
		BlockPos anchor = session.hasAnchor() ? session.anchor() : null;

		if (anchor == null) return Component.translatable("gui.howtobuild.status.no_centre").getString();

		String text = Component.translatable("gui.howtobuild.centre.at", anchor.getX(), anchor.getY(), anchor.getZ()).getString();
		BlockPos origin = session.origin();

		if (origin != null && !origin.equals(anchor)) {
			text += " → " + Component.translatable("gui.howtobuild.centre.origin", origin.getX(), origin.getY(), origin.getZ()).getString();
		}

		return text;
	}

	/** "Center size: 2 × 2 · Y 77 is the base layer". */
	private String centreSizeLine() {
		GeometryResult g = BuildSession.get().geometry();

		if (g == null || g.centreCells().isEmpty()) return "";

		long xs = g.centreCells().stream().mapToInt(c -> c[0]).distinct().count();
		long zs = g.centreCells().stream().mapToInt(c -> c[2]).distinct().count();
		BuildTool tool = config().activeTool();
		String layer = Component.translatable(tool.verticalAnchor(config().settings(tool)) == com.howtobuild.tools.VerticalAnchor.BASE
				? "gui.howtobuild.centre.base_layer" : "gui.howtobuild.centre.middle_layer").getString();
		return Component.translatable("gui.howtobuild.centre.size", xs + " × " + zs).getString() + " · " + layer;
	}

	/** Exact world bounds of the preview: line 0 min/max X and Z, line 1 sizes. */
	private String debugLine(int line) {
		BuildSession.Resolved r = BuildSession.get().resolved();
		Box b = r == null ? null : r.result().bounds();

		if (b == null) return "";

		var t = r.transform();

		if (line == 0) {
			return Component.translatable("gui.howtobuild.debug.bounds", t.x(b.minX()), t.x(b.maxX()), t.z(b.minZ()), t.z(b.maxZ()), t.y(b.minY()), t.y(b.maxY()))
					.getString();
		}

		return Component.translatable("gui.howtobuild.debug.size", b.sizeX(), b.sizeZ(), b.sizeY(), r.result().blockCount()).getString();
	}

	/** Spiral: copy the master circle's size from the Circle / Oval tool, or fit the whole staircase into it. */
	private Row circleButtons(BuildTool tool) {
		return pair((x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.circle.copy"), () -> {
					copyCircleDimensions(tool, false);
					rebuild();
				}, "gui.howtobuild.circle.copy.tooltip"),
				(x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.circle.fit"), () -> {
					copyCircleDimensions(tool, true);
					rebuild();
				}, "gui.howtobuild.circle.fit.tooltip"));
	}

	/**
	 * Takes the master footprint from the Circle tool (or the Oval tool when the master shape is an oval) and turns on
	 * Follow Circle Dimensions. With {@code fit}, switches to Fit Inside Circle instead (the steps sit inside the wall
	 * and clearance), keeps every detail inside, uses the automatic centre and limits the stair width / inner radius so
	 * there is room for steps.
	 */
	void copyCircleDimensions(BuildTool spiral, boolean fit) {
		HowToBuildConfig c = config();
		ToolSettings s = c.settings(spiral);
		boolean oval = "OVAL".equals(s.raw("master_shape"));
		int width;
		int length;

		if (oval) {
			ToolSettings o = c.settings(ToolRegistry.get("oval"));
			width = o.getInt("width");
			length = o.getInt("length");
		} else {
			width = c.settings(ToolRegistry.get("circle")).getInt("size");
			length = width;
		}

		c.setSetting(spiral, "circle_mode", fit ? "FIT_INSIDE" : "FOLLOW");
		c.setSetting(spiral, "circle_width", Integer.toString(width));
		c.setSetting(spiral, "circle_length", Integer.toString(length));

		if (fit) {
			int room = Math.min(width, length) - 2 * (s.getInt("wall_thickness") + s.getInt("clearance"));
			int half = Math.max(1, room / 2);
			c.setSetting(spiral, "allow_outside", "false");
			c.setSetting(spiral, "centre_size", "AUTO");
			c.setSetting(spiral, "stair_width", Integer.toString(Math.max(1, Math.min(s.getInt("stair_width"), half - 1))));

			if (s.getInt("inner_radius") >= half) c.setSetting(spiral, "inner_radius", Integer.toString(Math.max(0, half - 2)));
		}

		HowToBuildConfig.save();
	}

	private void parameter(BuildTool tool, ToolParameter p, int x, int y, int w) {
		HowToBuildConfig c = config();

		switch (p.type()) {
			case INT -> number(x, y, w, p.labelKey(), p.min(), p.max(), () -> c.settings(tool).getInt(p.id()),
					v -> c.setSetting(tool, p.id(), Integer.toString(v)), p.tooltipKey());
			case BOOL -> toggle(x, y, w, p.labelKey(), () -> c.settings(tool).getBool(p.id()), v -> c.setSetting(tool, p.id(), Boolean.toString(v)),
					p.tooltipKey(), true);
			case ENUM -> dynamicButton(x, y, w, () -> Component.translatable(p.labelKey()).append(": ")
					.append(Component.translatable(ToolParameter.optionKey(c.settings(tool).raw(p.id())))), () -> {
				List<String> options = p.options();
				int index = options.indexOf(c.settings(tool).raw(p.id()));
				int next = (index + (hasShiftDown() ? options.size() - 1 : 1)) % options.size();

				if (p.id().equals("circle_mode") && "OFF".equals(options.get(index < 0 ? 0 : index)) && !"OFF".equals(options.get(next))) {
					// Turning the master circle on copies the Circle tool's size.
					copyCircleDimensions(tool, "FIT_INSIDE".equals(options.get(next)));
				} else {
					c.setSetting(tool, p.id(), options.get(next));
				}

				rebuild();
			}, p.tooltipKey());
		}
	}

	// ---- Materials

	private List<Row> materialRows() {
		HowToBuildConfig c = config();
		BuildTool tool = c.activeTool();
		Set<MaterialRole> roles = tool instanceof MaterialAssignable m ? m.roles() : EnumSet.of(MaterialRole.PRIMARY);
		List<Row> rows = new ArrayList<>();
		wrapNotes(rows, Component.translatable("gui.howtobuild.materials.help").getString(), MUTED);

		for (MaterialRole role : MaterialRole.values()) {
			if (!roles.contains(role)) continue;

			rows.add(new Row(24, (x, y, w) -> materialRow(role, x, y, w)));
		}

		rows.add(header("gui.howtobuild.variation"));
		rows.add(row((x, y, w) -> cycle(x, y, w, "gui.howtobuild.variation", () -> c.variation, v -> c.variation = v, MaterialVariation.values(),
				"gui.howtobuild.variation.tooltip", true)));

		if (c.variation != MaterialVariation.NONE) {
			rows.add(row((x, y, w) -> {
				MaterialSlot primary = c.materials.get(MaterialRole.PRIMARY);
				text(x, y + 6, 60, Component.translatable("gui.howtobuild.variants").getString(), MUTED);
				int sx = x + 62;

				for (int i = 0; i < Math.min(6, primary.variants.size()); i++) {
					String id = primary.variants.get(i);
					slot(sx + i * 21, y, () -> stack(id), id, () -> {
						primary.variants.remove(id);
						rebuild();
					});
				}

				button(x + w - 70, y, 70, Component.translatable("gui.howtobuild.add_variant"), () -> openPicker(MaterialRole.PRIMARY, ShapeKind.BLOCKS, true),
						"gui.howtobuild.variants.tooltip");
			}));
			rows.add(row((x, y, w) -> number(x, y, w, "gui.howtobuild.seed", 0, 999999, () -> (int) c.seed, v -> c.seed = v, "gui.howtobuild.seed.tooltip")));
		}

		rows.add(header("gui.howtobuild.pattern"));
		rows.add(row((x, y, w) -> cycle(x, y, w, "gui.howtobuild.pattern", () -> c.pattern, v -> c.pattern = v, MaterialPattern.values(),
				"gui.howtobuild.pattern.tooltip", true)));

		if (c.pattern != MaterialPattern.NONE) {
			rows.add(row((x, y, w) -> number(x, y, w, "gui.howtobuild.pattern_size", 1, 32, () -> c.patternSize, v -> c.patternSize = v,
					"gui.howtobuild.pattern_size.tooltip")));
		}

		rows.add(row((x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.reset_materials"), () -> {
			c.materials = HowToBuildConfig.defaultMaterials();
			rebuild();
		}, null)));
		return rows;
	}

	private void materialRow(MaterialRole role, int x, int y, int w) {
		MaterialSlot slot = config().materials.get(role);
		text(x, y + 7, 64, Component.translatable("role.howtobuild." + role.name().toLowerCase(Locale.ROOT)).getString(), TEXT);
		int sx = x + 66;
		slot(sx, y, () -> stack(slot.block), slot.block, () -> openPicker(role, ShapeKind.BLOCKS, false));
		slot(sx + 22, y, () -> stack(slot.slab), slot.slab, () -> openPicker(role, ShapeKind.SLABS, false));
		slot(sx + 44, y, () -> stack(slot.stairs), slot.stairs, () -> openPicker(role, ShapeKind.STAIRS, false));
		text(sx + 68, y + 7, w - (sx + 68 - x), () -> name(slot.block), () -> MUTED);
	}

	private static ItemStack stack(String id) {
		return BlockCatalog.byId(id).map(BlockCatalog.Entry::icon).orElse(ItemStack.EMPTY);
	}

	private static String name(String id) {
		return MaterialResolver.block(id).map(b -> b.getName().getString()).orElse(id);
	}

	/** A 20×20 item slot drawn by the screen. */
	private void slot(int x, int y, java.util.function.Supplier<ItemStack> stack, String id, Runnable action) {
		slots.add(new int[] {x, y});
		slotActions.add(action);
		slotNames.add(id);
		icon(x + 2, y + 2, stack);
	}

	private void openPicker(MaterialRole role, ShapeKind kind, boolean variant) {
		minecraft.gui.setScreen(new BlockPickerScreen(this, role, kind, entry -> {
			MaterialSlot slot = config().materials.get(role);

			if (variant) {
				if (!slot.variants.contains(entry.id())) slot.variants.add(entry.id());
			} else if (entry.slab()) {
				slot.slab = entry.id();
			} else if (entry.stairs()) {
				slot.stairs = entry.id();
			} else {
				slot.block = entry.id();
				// Picking a full block also picks its slab and stairs, when the family has them.
				com.howtobuild.materials.MaterialFamilies.slabFor(entry.id()).ifPresent(id -> slot.slab = id);
				com.howtobuild.materials.MaterialFamilies.stairsFor(entry.id()).ifPresent(id -> slot.stairs = id);
			}

			HowToBuildConfig.save();
		}));
	}

	// ---- Details

	private List<Row> detailRows() {
		HowToBuildConfig c = config();
		BuildTool tool = c.activeTool();
		List<Row> rows = new ArrayList<>();

		if (!(tool instanceof Detailable detailable)) {
			rows.add(note(Component.translatable("gui.howtobuild.no_details").getString(), MUTED));
			return rows;
		}

		rows.add(row((x, y, w) -> cycle(x, y, w, "gui.howtobuild.detail_preset", () -> c.detailPreset, v -> c.detailPreset = v, DetailPreset.values(),
				"gui.howtobuild.detail_preset.tooltip", true)));
		EnumSet<DetailFeature> active = c.detailFeatures(tool);

		if (c.detailPreset == DetailPreset.CUSTOM) {
			rows.add(header("gui.howtobuild.details"));
			List<DetailFeature> features = new ArrayList<>(detailable.supportedDetails());
			features.sort(null);

			for (int i = 0; i < features.size(); i += 2) {
				DetailFeature first = features.get(i);
				DetailFeature second = i + 1 < features.size() ? features.get(i + 1) : null;
				rows.add(row((x, y, w) -> {
					int half = (w - 4) / 2;
					featureToggle(first, x, y, half);

					if (second != null) featureToggle(second, x + half + 4, y, w - half - 4);
				}));
			}
		} else {
			StringBuilder list = new StringBuilder();

			for (DetailFeature f : active) {
				if (!list.isEmpty()) list.append(", ");
				list.append(Component.translatable(f.translationKey()).getString());
			}

			wrapNotes(rows, Component.translatable("gui.howtobuild.details_active", list.isEmpty() ? "—" : list.toString()).getString(), MUTED);
		}

		rows.add(row((x, y, w) -> number(x, y, w, "gui.howtobuild.detail_interval", 2, 64, () -> c.detailInterval, v -> c.detailInterval = v,
				"gui.howtobuild.detail_interval.tooltip")));
		return rows;
	}

	private void featureToggle(DetailFeature feature, int x, int y, int w) {
		HowToBuildConfig c = config();
		dynamicButton(x, y, w, () -> Component.literal(c.customDetails.contains(feature) ? "■ " : "□ ").append(Component.translatable(feature.translationKey())),
				() -> {
					if (!c.customDetails.remove(feature)) c.customDetails.add(feature);
				}, feature.translationKey() + ".tooltip");
	}

	// ---- Labels

	private List<Row> labelRows() {
		var l = config().labels;
		List<Row> rows = new ArrayList<>();
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.labels.dimensions", () -> l.showDimensions, v -> l.showDimensions = v,
						"gui.howtobuild.labels.dimensions.tooltip", false),
				(x, y, w) -> toggle(x, y, w, "gui.howtobuild.labels.popups", () -> l.showPopups, v -> l.showPopups = v,
						"gui.howtobuild.labels.popups.tooltip", false)));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.labels.overall", () -> l.showOverall, v -> l.showOverall = v,
						"gui.howtobuild.labels.overall.tooltip", false),
				(x, y, w) -> toggle(x, y, w, "gui.howtobuild.labels.summary", () -> l.showSummary, v -> l.showSummary = v,
						"gui.howtobuild.labels.summary.tooltip", true)));
		rows.add(header("gui.howtobuild.labels.format_header"));
		rows.add(row((x, y, w) -> cycle(x, y, w, "gui.howtobuild.labels.format", () -> l.format, v -> l.format = v, DimensionFormat.values(),
				"gui.howtobuild.labels.format.tooltip", true)));

		if (l.format == DimensionFormat.FULL) {
			rows.add(row((x, y, w) -> cycle(x, y, w, "gui.howtobuild.labels.order", () -> l.order, v -> l.order = v, LabelFormat.values(),
					"gui.howtobuild.labels.order.tooltip", false)));
		}

		if (l.format == DimensionFormat.CUSTOM) {
			rows.add(row((x, y, w) -> {
				text(x, y + 6, 54, Component.translatable("gui.howtobuild.labels.template").getString(), MUTED);
				EditBox box = new EditBox(font, x + 56, y, w - 56, CONTROL_HEIGHT, Component.translatable("gui.howtobuild.labels.template"));
				box.setMaxLength(120);
				box.setValue(l.template);
				box.setResponder(v -> l.template = v);
				var tip = tooltip("gui.howtobuild.labels.template.tooltip");

				if (tip != null) box.setTooltip(tip);

				widget(box);
			}));
		}

		rows.add(pair((x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable("gui.howtobuild.labels.units",
						l.units.isBlank() ? Component.translatable("enum.howtobuild.none").getString() : l.units.trim()), () -> {
					int i = java.util.Arrays.asList(UNITS).indexOf(l.units);
					l.units = UNITS[(i + 1) % UNITS.length];
				}, "gui.howtobuild.labels.units.tooltip"),
				(x, y, w) -> number(x, y, w, "gui.howtobuild.labels.decimals", 0, 3, () -> l.decimals, v -> l.decimals = v, "gui.howtobuild.labels.decimals.tooltip")));

		if (l.showSummary) {
			rows.add(header("gui.howtobuild.labels.components"));
			LabelComponent[] components = LabelComponent.values();

			for (int i = 0; i < components.length; i += 2) {
				LabelComponent first = components[i];
				LabelComponent second = i + 1 < components.length ? components[i + 1] : null;
				rows.add(row((x, y, w) -> {
					int half = (w - 4) / 2;
					componentToggle(first, x, y, half);

					if (second != null) componentToggle(second, x + half + 4, y, w - half - 4);
				}));
			}
		}

		rows.add(header("gui.howtobuild.labels.style"));
		rows.add(row((x, y, w) -> cycle(x, y, w, "gui.howtobuild.labels.placement", () -> l.placement, v -> l.placement = v, LabelPlacement.values(),
				"gui.howtobuild.labels.placement.tooltip", false)));
		rows.add(pair((x, y, w) -> number(x, y, w, "gui.howtobuild.labels.size", 50, 300, () -> Math.round(l.textSize * 100), v -> l.textSize = v / 100F,
						"gui.howtobuild.labels.size.tooltip"),
				(x, y, w) -> number(x, y, w, "gui.howtobuild.labels.opacity", 10, 100, () -> Math.round(l.opacity * 100), v -> l.opacity = v / 100F,
						"gui.howtobuild.labels.opacity.tooltip")));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.labels.background", () -> l.background, v -> l.background = v,
						"gui.howtobuild.labels.background.tooltip", false),
				(x, y, w) -> toggle(x, y, w, "gui.howtobuild.labels.border", () -> l.border, v -> l.border = v, "gui.howtobuild.labels.border.tooltip", false)));
		rows.add(pair((x, y, w) -> number(x, y, w, "gui.howtobuild.labels.padding", 0, 4, () -> l.padding, v -> l.padding = v, "gui.howtobuild.labels.padding.tooltip"),
				(x, y, w) -> number(x, y, w, "gui.howtobuild.labels.offset", 0, 60, () -> Math.round(l.offset * 10), v -> l.offset = v / 10F,
						"gui.howtobuild.labels.offset.tooltip")));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.labels.leaders", () -> l.leaderLines, v -> l.leaderLines = v,
						"gui.howtobuild.labels.leaders.tooltip", false),
				(x, y, w) -> toggle(x, y, w, "gui.howtobuild.labels.scaling", () -> l.distanceScaling, v -> l.distanceScaling = v,
						"gui.howtobuild.labels.scaling.tooltip", false)));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.labels.billboard", () -> l.billboard, v -> l.billboard = v,
						"gui.howtobuild.labels.billboard.tooltip", false),
				(x, y, w) -> toggle(x, y, w, "gui.howtobuild.labels.through_walls", () -> l.throughWalls, v -> l.throughWalls = v,
						"gui.howtobuild.labels.through_walls.tooltip", false)));

		if (config().advanced) {
			rows.add(pair((x, y, w) -> number(x, y, w, "gui.howtobuild.labels.distance", 8, 256, () -> Math.round(l.labelDistance), v -> l.labelDistance = v,
							"gui.howtobuild.labels.distance.tooltip"),
					(x, y, w) -> number(x, y, w, "gui.howtobuild.labels.popup_distance", 4, 256, () -> Math.round(l.popupDistance), v -> l.popupDistance = v,
							"gui.howtobuild.labels.distance.tooltip")));
			rows.add(row((x, y, w) -> number(x, y, w, "gui.howtobuild.labels.min_section", 1, 64, () -> l.minSectionSize, v -> l.minSectionSize = v,
					"gui.howtobuild.labels.min_section.tooltip")));
		}

		return rows;
	}

	private void componentToggle(LabelComponent component, int x, int y, int w) {
		List<LabelComponent> list = config().labels.components;
		dynamicButton(x, y, w, () -> Component.literal(list.contains(component) ? "■ " : "□ ").append(option(component)), () -> {
			if (!list.remove(component)) {
				list.add(component);
				list.sort(null);
			}
		}, null);
	}

	// ---- Mirror

	private List<Row> mirrorRows() {
		var m = config().mirror;
		List<Row> rows = new ArrayList<>();
		wrapNotes(rows, Component.translatable("tool.howtobuild.mirror.desc").getString(), MUTED);
		rows.add(row((x, y, w) -> toggle(x, y, w, "gui.howtobuild.mirror.enabled", () -> m.enabled, v -> m.enabled = v, "gui.howtobuild.mirror.enabled.tooltip", true)));

		if (!m.enabled) return rows;

		rows.add(pair((x, y, w) -> cycle(x, y, w, "gui.howtobuild.mirror.axis", () -> m.axis, v -> m.axis = v, MirrorAxis.values(),
						"gui.howtobuild.mirror.axis.tooltip", true),
				(x, y, w) -> cycle(x, y, w, "gui.howtobuild.mirror.mode", () -> m.mode, v -> m.mode = v, MirrorMode.values(),
						"gui.howtobuild.mirror.mode.tooltip", false)));
		rows.add(header("gui.howtobuild.mirror.centre"));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.mirror.shape_centre", () -> m.useShapeCentre, v -> m.useShapeCentre = v,
						"gui.howtobuild.mirror.shape_centre.tooltip", false),
				(x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.mirror.select"), () -> {
					CentreSelectionHandler.get().start(CentreSelectionHandler.Target.MIRROR, true);
					onClose();
				}, "gui.howtobuild.mirror.select.tooltip")));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> {
			BlockPos centre = m.useShapeCentre ? BuildSession.get().origin() : BuildSession.get().mirrorCentre();
			return centre == null ? Component.translatable("gui.howtobuild.mirror.no_centre").getString()
					: Component.translatable("gui.howtobuild.mirror.centre_at", centre.getX(), centre.getY(), centre.getZ()).getString();
		}, () -> MUTED)));

		if (m.axis.mirrorsX()) {
			rows.add(pair((x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable(m.twoWideX ? "gui.howtobuild.mirror.width2" : "gui.howtobuild.mirror.width1", "X"),
							() -> m.twoWideX = !m.twoWideX, "gui.howtobuild.mirror.width.tooltip"),
					(x, y, w) -> number(x, y, w, "gui.howtobuild.mirror.offset_x", -512, 512, () -> m.offsetX, v -> m.offsetX = v, "gui.howtobuild.mirror.offset.tooltip")));
		}

		if (m.axis.mirrorsZ()) {
			rows.add(pair((x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable(m.twoWideZ ? "gui.howtobuild.mirror.width2" : "gui.howtobuild.mirror.width1", "Z"),
							() -> m.twoWideZ = !m.twoWideZ, "gui.howtobuild.mirror.width.tooltip"),
					(x, y, w) -> number(x, y, w, "gui.howtobuild.mirror.offset_z", -512, 512, () -> m.offsetZ, v -> m.offsetZ = v, "gui.howtobuild.mirror.offset.tooltip")));
		}

		if (m.twoWideX || m.twoWideZ) {
			rows.add(pair((x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable("gui.howtobuild.align_x", m.alignX ? "+X" : "−X"),
							() -> m.alignX = !m.alignX, "gui.howtobuild.align.tooltip"),
					(x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable("gui.howtobuild.align_z", m.alignZ ? "+Z" : "−Z"),
							() -> m.alignZ = !m.alignZ, "gui.howtobuild.align.tooltip")));
		}

		return rows;
	}

	// ---- Build

	private List<Row> buildRows() {
		HowToBuildConfig c = config();
		var h = c.hologram;
		var b = c.build;
		List<Row> rows = new ArrayList<>();
		rows.add(header("gui.howtobuild.hologram"));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.hologram.visible", () -> h.visible, v -> h.visible = v, "gui.howtobuild.hologram.visible.tooltip", false),
				(x, y, w) -> number(x, y, w, "gui.howtobuild.hologram.opacity", 5, 90, () -> Math.round(h.opacity * 100), v -> h.opacity = v / 100F,
						"gui.howtobuild.hologram.opacity.tooltip")));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.hologram.material_colours", () -> h.colorByMaterial, v -> h.colorByMaterial = v,
						"gui.howtobuild.hologram.material_colours.tooltip", false),
				(x, y, w) -> toggle(x, y, w, "gui.howtobuild.hologram.see_through", () -> h.seeThroughBlocks, v -> h.seeThroughBlocks = v,
						"gui.howtobuild.hologram.see_through.tooltip", false)));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.hologram.edges", () -> h.showEdges, v -> h.showEdges = v, "gui.howtobuild.hologram.edges.tooltip", false),
				(x, y, w) -> toggle(x, y, w, "gui.howtobuild.hologram.centre", () -> h.showCentre, v -> h.showCentre = v, "gui.howtobuild.hologram.centre.tooltip", false)));
		rows.add(header("gui.howtobuild.build"));
		rows.add(row((x, y, w) -> cycle(x, y, w, "gui.howtobuild.build.method", () -> b.method, v -> b.method = v, BuildConfig.Method.values(),
				"gui.howtobuild.build.method.tooltip", true)));
		wrapNotes(rows, Component.translatable("gui.howtobuild.build.method." + b.method.name().toLowerCase(Locale.ROOT)).getString(), MUTED);
		rows.add(row((x, y, w) -> toggle(x, y, w, "gui.howtobuild.build.progress", () -> b.trackProgress, v -> b.trackProgress = v,
				"gui.howtobuild.build.progress.tooltip", false)));

		if (b.method != BuildConfig.Method.NORMAL) {
			rows.add(pair((x, y, w) -> number(x, y, w, "gui.howtobuild.build.per_tick", 1, 20, () -> b.commandsPerTick, v -> b.commandsPerTick = v,
							"gui.howtobuild.build.per_tick.tooltip"),
					(x, y, w) -> toggle(x, y, w, "gui.howtobuild.build.keep", () -> b.keepExisting, v -> b.keepExisting = v, "gui.howtobuild.build.keep.tooltip", false)));
			rows.add(row((x, y, w) -> number(x, y, w, "gui.howtobuild.build.max_fill", 1, 1_000_000, () -> b.maxFillVolume, v -> b.maxFillVolume = v,
					"gui.howtobuild.build.max_fill.tooltip")));
			rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> {
				CommandPlan plan = BuildSession.get().plan();
				return Component.translatable("gui.howtobuild.build.summary", plan.blocks(), plan.commands().size(), plan.fills(), plan.setblocks(),
						String.format(Locale.ROOT, "%.1f", plan.compression())).getString();
			}, () -> TEXT)));
			rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> CommandPermission.describe(CommandPermission.check()).getString(),
					() -> CommandPermission.available(CommandPermission.check()) ? GOOD : WARNING)));
		}

		rows.add(row((x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.build.open"), this::openBuild, "gui.howtobuild.build.open.tooltip")));
		return rows;
	}

	// ----------------------------------------------------------------- status & bottom bar

	private void buildStatus(int x, int y, int w) {
		text(x, y, w, this::statusLine, () -> BuildSession.get().hasAnchor() ? TEXT : MUTED);
		text(x, y + 11, w, () -> warning(0), () -> warningColor(0));
		text(x, y + 22, w, () -> hoverInfo.isEmpty() ? warning(1) : hoverInfo, () -> hoverInfo.isEmpty() ? warningColor(1) : ACCENT);
	}

	private String statusLine() {
		BuildSession session = BuildSession.get();
		GeometryResult g = session.geometry();

		if (!session.hasAnchor()) return Component.translatable("gui.howtobuild.status.no_centre").getString();
		if (g == null || session.isGenerating() && g.isEmpty()) return Component.translatable("gui.howtobuild.status.generating").getString();

		Box bounds = g.bounds();
		BlockPos o = session.origin();
		String size = bounds == null ? "—" : bounds.sizeX() + " × " + bounds.sizeY() + " × " + bounds.sizeZ();
		String text = Component.translatable("gui.howtobuild.status.summary", size, g.blockCount(), o.getX(), o.getY(), o.getZ()).getString();
		return session.isGenerating() ? text + " · " + Component.translatable("gui.howtobuild.status.updating").getString() : text;
	}

	private String warning(int index) {
		GeometryResult g = BuildSession.get().geometry();
		List<String> warnings = g == null ? List.of() : g.warnings();
		return index < warnings.size() ? "⚠ " + warnings.get(index) : "";
	}

	private int warningColor(int index) {
		GeometryResult g = BuildSession.get().geometry();
		return g != null && g.isEmpty() && index == 0 ? ERROR : WARNING;
	}

	private void buildBottomBar(int x, int y, int panelW) {
		// Sized relative to the panel: eight buttons share its width, within limits.
		int w = Math.max(50, Math.min(100, (panelW - 60) / 8));
		int gap = 3;
		button(x, y, w, Component.translatable("gui.howtobuild.select_centre"), this::selectCentre, "gui.howtobuild.select_centre.tooltip");
		x += w + gap;
		button(x, y, w, Component.translatable("gui.howtobuild.use_position"), () -> {
			BuildSession.get().setAnchorToPlayer();
			refresh();
		}, "gui.howtobuild.use_position.tooltip");
		x += w + gap;
		button(x, y, w, Component.translatable("gui.howtobuild.clear"), () -> {
			BuildSession.get().clear();
			refresh();
		}, "gui.howtobuild.clear.tooltip");
		x += w + gap + 6;
		dynamicButton(x, y, w, () -> Component.translatable(config().hologram.visible ? "gui.howtobuild.preview.on" : "gui.howtobuild.preview.off"),
				() -> config().hologram.visible = !config().hologram.visible, "gui.howtobuild.preview.tooltip");
		x += w + gap;
		button(x, y, w, Component.translatable("gui.howtobuild.build_button"), this::openBuild, "gui.howtobuild.build_button.tooltip");
		x += w + gap + 6;
		int small = Math.max(20, Math.min(w, (this.width - 8 - x) / 3 - gap));
		button(x, y, small, Component.translatable("gui.howtobuild.presets"), () -> minecraft.gui.setScreen(new PresetScreen(this)), "gui.howtobuild.presets.tooltip");
		x += small + gap;
		dynamicButton(x, y, Math.min(small, 20), () -> Component.literal(help ? "×" : "?"), () -> help = !help, "gui.howtobuild.help.tooltip");
		x += Math.min(small, 20) + gap;
		button(x, y, small, Component.translatable("gui.done"), this::onClose, null);
	}

	private void openBuild() {
		HowToBuildConfig.save();
		minecraft.gui.setScreen(new CommandBuildScreen(this));
	}

	// ----------------------------------------------------------------- drawing & input

	@Override
	protected void drawBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		hoverInfo = "";

		for (int i = 0; i < slots.size(); i++) {
			int[] s = slots.get(i);
			boolean hovered = inside(mouseX, mouseY, s[0], s[1], 20, 20);
			graphics.fill(s[0], s[1], s[0] + 20, s[1] + 20, hovered ? 0xFF9DB3BA : 0xFF2A3A40);
			graphics.fill(s[0] + 1, s[1] + 1, s[0] + 19, s[1] + 19, hovered ? 0xFF2E4A55 : 0xFF16242A);

			if (hovered) hoverInfo = name(slotNames.get(i)) + " — " + slotNames.get(i);
		}

		if (contentHeight > formH) {
			int barX = formX + formW - 3;
			int barH = Math.max(12, formH * formH / contentHeight);
			int barY = formY + (formH - barH) * scroll / Math.max(1, contentHeight - formH);
			graphics.fill(barX, formY, barX + 2, formY + formH, 0x40FFFFFF);
			graphics.fill(barX, barY, barX + 2, barY + barH, ACCENT);
		}
	}

	@Override
	protected void drawForeground(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!help) return;

		String text = Component.translatable("help.howtobuild." + tab().name().toLowerCase(Locale.ROOT)).getString()
				+ "\n\n" + Component.translatable("help.howtobuild.general").getString();
		List<String> lines = wrap(text, formW - 16);
		int h = Math.min(formH, lines.size() * 10 + 12);
		roundedPanel(graphics, formX, formY, formX + formW, formY + h);
		graphics.fill(formX + 1, formY + 1, formX + formW - 1, formY + h - 1, 0xF0101820);
		int y = formY + 6;

		for (String line : lines) {
			if (y > formY + h - 10) break;

			graphics.text(font, line, formX + 8, y, TEXT, false);
			y += 10;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (help && inside(event.x(), event.y(), formX, formY, formW, formH)) {
			help = false;
			rebuild();
			return true;
		}

		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			for (int i = 0; i < headerAreas.size(); i++) {
				int[] a = headerAreas.get(i);

				if (inside(event.x(), event.y(), a[0], a[1], a[2], a[3])) {
					List<String> collapsed = config().collapsedSections;

					if (!collapsed.remove(headerKeys.get(i))) collapsed.add(headerKeys.get(i));

					rebuild();
					return true;
				}
			}

			for (int i = 0; i < slots.size(); i++) {
				int[] s = slots.get(i);

				if (inside(event.x(), event.y(), s[0], s[1], 20, 20)) {
					slotActions.get(i).run();
					return true;
				}
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (inside(mouseX, mouseY, formX, formY, formW, formH) && contentHeight > formH) {
			int next = Math.max(0, Math.min(contentHeight - formH, scroll - (int) Math.round(scrollY * ROW)));

			if (next != scroll) {
				scroll = next;
				rebuild();
			}

			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	protected void changed(boolean structural) {
		config().sanitize();
		super.changed(structural);
	}

	@Override
	public void onClose() {
		HowToBuildConfig.save();
		super.onClose();
	}

	/** For tests: the tool that is currently selected. */
	public static String selectedTool() {
		return config().tool;
	}
}
