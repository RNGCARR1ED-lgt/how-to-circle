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
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;

import com.howtobuild.building.CommandPermission;
import com.howtobuild.client.BuildLibrary;
import com.howtobuild.client.BuildSession;
import com.howtobuild.commands.CommandPlan;
import com.howtobuild.commands.MaterialCounter;
import com.howtobuild.config.BuildConfig;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.config.MaterialSlot;
import com.howtobuild.config.RandomConfig;
import com.howtobuild.config.TerrainLayerConfig;
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
import com.howtobuild.materials.MaterialThemes;
import com.howtobuild.palette.MirrorRandomisation;
import com.howtobuild.palette.RandomMode;
import com.howtobuild.palette.RandomPattern;
import com.howtobuild.palette.RandomSymmetry;
import com.howtobuild.palette.WeightedPalette;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.ToolParameter;
import com.howtobuild.tools.ToolRegistry;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.capability.Detailable;
import com.howtobuild.tools.capability.MaterialAssignable;
import com.howtobuild.tools.capability.Rotatable;
import com.howtobuild.tools.impl.TerrainTool;
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
		RANDOMISE,
		DETAILS,
		LABELS,
		MIRROR,
		BUILD,
		BUILDS
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
		int paneLeft = left + panelW + 12;
		buildPreviewPane(paneLeft, top + 4, Math.min(240, this.width - paneLeft - 12), bottom);
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
			case RANDOMISE -> randomRows();
			case BUILD -> buildRows();
			case BUILDS -> savedBuildRows();
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
			case TEXT -> {
				String label = Component.translatable(p.labelKey()).getString();
				int labelW = Math.min(w / 3, font.width(label) + 6);
				text(x, y + 6, labelW - 4, label, MUTED);
				EditBox box = new EditBox(font, x + labelW, y, w - labelW, CONTROL_HEIGHT, Component.translatable(p.labelKey()));
				box.setMaxLength(p.max());
				box.setValue(c.settings(tool).raw(p.id()));
				box.setResponder(v -> c.setSetting(tool, p.id(), v));
				var tip = tooltip(p.tooltipKey());

				if (tip != null) box.setTooltip(tip);

				widget(box);
			}
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

		rows.add(header("gui.howtobuild.themes"));
		MaterialThemes[] themes = MaterialThemes.values();

		for (int i = 0; i < themes.length; i += 4) {
			int start = i;
			rows.add(row((x, y, w) -> {
				int bw = (w - 12) / 4;

				for (int k = 0; k < 4 && start + k < themes.length; k++) {
					MaterialThemes theme = themes[start + k];
					button(x + k * (bw + 4), y, bw, Component.translatable("theme.howtobuild." + theme.name().toLowerCase(Locale.ROOT)), () -> {
						c.materials = theme.materials();
						changed(true);
					}, "gui.howtobuild.themes.tooltip");
				}
			}));
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

		if (tool instanceof TerrainTool) rows.addAll(terrainLayerRows());

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
		List<Row> rows = dashboardRows();
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

	// ---- Randomise

	/** The Randomise tab: palette with percentages, arrangement, seed, roles, edge protection and symmetry. */
	private List<Row> randomRows() {
		HowToBuildConfig c = config();
		RandomConfig r = c.random;
		List<Row> rows = new ArrayList<>();
		wrapNotes(rows, Component.translatable("gui.howtobuild.random.help").getString(), MUTED);
		rows.add(row((x, y, w) -> toggle(x, y, w, "gui.howtobuild.random.enabled", () -> r.enabled, v -> r.enabled = v,
				"gui.howtobuild.random.enabled.tooltip", true)));
		rows.add(header("gui.howtobuild.random.palette"));

		for (RandomConfig.Entry entry : r.palette) {
			rows.add(new Row(24, (x, y, w) -> paletteRow(r.palette, entry, x, y, w)));
		}

		rows.add(pair((x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.random.add"), () -> pickBlock(id -> {
					r.palette.add(new RandomConfig.Entry(id, 10));
				}), "gui.howtobuild.random.add.tooltip"),
				(x, y, w) -> toggle(x, y, w, "gui.howtobuild.random.normalise", () -> r.normalise, v -> r.normalise = v,
						"gui.howtobuild.random.normalise.tooltip", false)));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> {
			WeightedPalette palette = r.weightedPalette();
			String total = Component.translatable("gui.howtobuild.random.total", String.format(Locale.ROOT, "%.1f", palette.total())).getString();
			String warning = palette.totalWarning();
			return warning == null ? total : total + " · " + warning;
		}, () -> r.weightedPalette().totalWarning() == null ? GOOD : WARNING)));

		rows.add(header("gui.howtobuild.random.arrangement"));
		rows.add(pair((x, y, w) -> cycle(x, y, w, "gui.howtobuild.random.mode", () -> r.mode, v -> r.mode = v, RandomMode.values(),
						"gui.howtobuild.random.mode.tooltip", false),
				(x, y, w) -> cycle(x, y, w, "gui.howtobuild.random.pattern", () -> r.pattern, v -> r.pattern = v, RandomPattern.values(),
						"gui.howtobuild.random.pattern.tooltip", true)));
		rows.add(row((x, y, w) -> {
			int third = (w - 8) / 3;
			EditBox seed = number(x, y, third + 30, "gui.howtobuild.seed", 0, 999_999, () -> (int) r.seed, v -> r.seed = v, "gui.howtobuild.random.seed.tooltip");
			button(x + third + 34, y, third - 15, Component.translatable("gui.howtobuild.random.again"), () -> {
				boolean locked = r.lockSeed;
				r.lockSeed = false;
				r.reseed();
				r.lockSeed = locked;
				seed.setValue(Long.toString(r.seed));
			}, "gui.howtobuild.random.again.tooltip");
			toggle(x + 2 * third + 23, y, w - 2 * third - 23, "gui.howtobuild.random.lock", () -> r.lockSeed, v -> r.lockSeed = v,
					"gui.howtobuild.random.lock.tooltip", false);
		}));

		if (EnumSet.of(RandomPattern.CLUSTERED, RandomPattern.PATCHY, RandomPattern.STRIPED, RandomPattern.RADIAL).contains(r.pattern)) {
			rows.add(row((x, y, w) -> number(x, y, w, "gui.howtobuild.random.cluster", 1, 64, () -> r.clusterSize, v -> r.clusterSize = v,
					"gui.howtobuild.random.cluster.tooltip")));
		}

		if (r.pattern == RandomPattern.NOISE || r.pattern == RandomPattern.CUSTOM || r.pattern == RandomPattern.NATURAL) {
			rows.add(pair((x, y, w) -> number(x, y, w, "gui.howtobuild.random.scale", 1, 256, () -> r.noiseScale, v -> r.noiseScale = v,
							"gui.howtobuild.random.scale.tooltip"),
					(x, y, w) -> number(x, y, w, "gui.howtobuild.random.strength", 0, 100, () -> r.noiseStrength, v -> r.noiseStrength = v,
							"gui.howtobuild.random.strength.tooltip")));

			if (r.pattern == RandomPattern.CUSTOM) {
				rows.add(pair((x, y, w) -> number(x, y, w, "gui.howtobuild.random.octaves", 1, 8, () -> r.octaves, v -> r.octaves = v,
								"gui.howtobuild.random.octaves.tooltip"),
						(x, y, w) -> number(x, y, w, "gui.howtobuild.random.contrast", 1, 100, () -> r.contrast, v -> r.contrast = v,
								"gui.howtobuild.random.contrast.tooltip")));
				rows.add(row((x, y, w) -> number(x, y, w, "gui.howtobuild.random.threshold", -100, 100, () -> r.threshold, v -> r.threshold = v,
						"gui.howtobuild.random.threshold.tooltip")));
			}
		}

		rows.add(header("gui.howtobuild.random.roles"));
		MaterialRole[] roles = MaterialRole.values();

		for (int i = 0; i < roles.length; i += 3) {
			int start = i;
			rows.add(row((x, y, w) -> {
				int bw = (w - 8) / 3;

				for (int k = 0; k < 3 && start + k < roles.length; k++) {
					MaterialRole role = roles[start + k];
					dynamicButton(x + k * (bw + 4), y, bw, () -> Component.literal(r.hasRole(role) ? "■ " : "□ ")
							.append(Component.translatable("role.howtobuild." + role.key())), () -> r.toggleRole(role), "gui.howtobuild.random.roles.tooltip");
				}
			}));
		}

		rows.add(header("gui.howtobuild.random.edges"));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.random.protect_edge", () -> r.protectEdge, v -> r.protectEdge = v,
						"gui.howtobuild.random.protect_edge.tooltip", true),
				(x, y, w) -> number(x, y, w, "gui.howtobuild.random.edge_variation", 0, 100, () -> r.edgeVariation, v -> r.edgeVariation = v,
						"gui.howtobuild.random.edge_variation.tooltip")));

		if (r.protectEdge) {
			rows.add(new Row(24, (x, y, w) -> {
				String edge = r.edgeBlock.isBlank() && !r.palette.isEmpty() ? r.palette.getFirst().block : r.edgeBlock;
				text(x, y + 7, 70, Component.translatable("gui.howtobuild.random.edge_block").getString(), MUTED);
				slot(x + 72, y, () -> stack(edge), edge, () -> pickBlock(id -> r.edgeBlock = id));
				text(x + 96, y + 7, w - 96, () -> name(edge), () -> TEXT);
			}));
		}

		rows.add(header("gui.howtobuild.random.symmetry"));
		rows.add(pair((x, y, w) -> cycle(x, y, w, "gui.howtobuild.random.spiral", () -> r.symmetry, v -> r.symmetry = v, RandomSymmetry.values(),
						"gui.howtobuild.random.spiral.tooltip", false),
				(x, y, w) -> cycle(x, y, w, "gui.howtobuild.random.mirror", () -> r.mirror, v -> r.mirror = v, MirrorRandomisation.values(),
						"gui.howtobuild.random.mirror.tooltip", false)));

		rows.add(header("gui.howtobuild.random.breakdown"));
		rows.addAll(materialCountRows(8, false));
		return rows;
	}

	/** One palette entry: block (click to change), percentage, on / off and remove. */
	private void paletteRow(List<RandomConfig.Entry> list, RandomConfig.Entry entry, int x, int y, int w) {
		slot(x, y, () -> stack(entry.block), entry.block, () -> pickBlock(id -> entry.block = id));
		int nameW = Math.max(40, w / 3);
		text(x + 24, y + 7, nameW - 4, () -> name(entry.block), () -> entry.enabled ? TEXT : MUTED);
		int fieldX = x + 24 + nameW;
		int fieldW = w - (fieldX - x) - 48;
		number(fieldX, y + 2, fieldW, "gui.howtobuild.random.percent", 0, 1000, () -> (int) Math.round(entry.weight), v -> entry.weight = v,
				"gui.howtobuild.random.percent.tooltip");
		dynamicButton(x + w - 46, y + 2, 22, () -> Component.literal(entry.enabled ? "✔" : "–"), () -> {
			entry.enabled = !entry.enabled;
			changed(false);
		}, "gui.howtobuild.random.entry_enabled.tooltip");
		button(x + w - 22, y + 2, 22, Component.literal("×"), () -> {
			list.remove(entry);
			changed(true);
		}, "gui.howtobuild.random.remove.tooltip");
	}

	/** Opens the block selector; the chosen block id is passed on and the screen comes back. */
	private void pickBlock(java.util.function.Consumer<String> onPick) {
		minecraft.gui.setScreen(new BlockPickerScreen(this, MaterialRole.PRIMARY, ShapeKind.BLOCKS, entry -> {
			onPick.accept(entry.id());
			config().sanitize();
			HowToBuildConfig.save();
		}));
	}

	// ---- Terrain layers (Materials tab of the Terrain tool)

	private List<Row> terrainLayerRows() {
		HowToBuildConfig c = config();
		List<Row> rows = new ArrayList<>();
		rows.add(header("gui.howtobuild.terrain.layers"));
		wrapNotes(rows, Component.translatable("gui.howtobuild.terrain.layers.help").getString(), MUTED);

		for (int i = 0; i < c.terrainLayers.size(); i++) {
			TerrainLayerConfig layer = c.terrainLayers.get(i);
			int index = i;
			rows.add(row((x, y, w) -> {
				text(x, y + 6, 60, Component.translatable("gui.howtobuild.terrain.layer", index + 1).getString(), ACCENT);
				number(x + 62, y, w - 62 - 72, "gui.howtobuild.terrain.thickness", 0, 256, () -> layer.thickness, v -> layer.thickness = v,
						"gui.howtobuild.terrain.thickness.tooltip");
				button(x + w - 70, y, 24, Component.literal("+"), () -> pickBlock(id -> layer.blocks.add(new RandomConfig.Entry(id, 20))),
						"gui.howtobuild.terrain.add_block.tooltip");
				button(x + w - 44, y, 22, Component.literal("↑"), () -> {
					if (index > 0) {
						c.terrainLayers.remove(index);
						c.terrainLayers.add(index - 1, layer);
						changed(true);
					}
				}, "gui.howtobuild.terrain.up.tooltip");
				button(x + w - 22, y, 22, Component.literal("×"), () -> {
					c.terrainLayers.remove(layer);
					changed(true);
				}, "gui.howtobuild.terrain.remove.tooltip");
			}));

			for (RandomConfig.Entry entry : layer.blocks) {
				rows.add(new Row(24, (x, y, w) -> paletteRow(layer.blocks, entry, x + 8, y, w - 8)));
			}
		}

		rows.add(pair((x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.terrain.add_layer"),
						() -> pickBlock(id -> c.terrainLayers.add(new TerrainLayerConfig(1, new RandomConfig.Entry(id, 100)))),
						"gui.howtobuild.terrain.add_layer.tooltip"),
				(x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.terrain.reset_layers"), () -> {
					c.terrainLayers = TerrainLayerConfig.defaults();
					changed(true);
				}, null)));
		rows.add(row((x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.terrain.resample"), () -> BuildSession.get().resampleGround(),
				"gui.howtobuild.terrain.resample.tooltip")));
		return rows;
	}

	// ---- Build dashboard

	/** Material list filters. */
	enum MaterialFilter {
		ALL,
		BLOCKS,
		SLABS,
		STAIRS,
		DETAILS,
		STRUCTURAL,
		ACCENT
	}

	private static MaterialFilter materialFilter = MaterialFilter.ALL;
	private static boolean showBlockList;
	private static String comparison = "";

	/** Summary of what will be built: tool, size, centre, blocks, materials, details, randomisation, mirror, inset. */
	private List<Row> dashboardRows() {
		HowToBuildConfig c = config();
		List<Row> rows = new ArrayList<>();
		rows.add(header("gui.howtobuild.dashboard"));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> {
			BuildSession.Resolved r = BuildSession.get().resolved();
			Box b = r == null ? null : r.result().bounds();
			String size = b == null ? "—" : b.sizeX() + " × " + b.sizeY() + " × " + b.sizeZ();
			return Component.translatable("gui.howtobuild.dashboard.shape", Component.translatable(c.activeTool().translationKey()).getString(), size).getString();
		}, () -> TEXT)));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> {
			List<MaterialCounter.Count> counts = BuildSession.get().materialCounts();
			return Component.translatable("gui.howtobuild.dashboard.blocks", MaterialCounter.total(counts), counts.size()).getString();
		}, () -> TEXT)));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, this::centreLine, () -> MUTED)));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> {
			GeometryResult g = BuildSession.get().geometry();
			String details = Component.translatable("enum.howtobuild." + c.detailPreset.name().toLowerCase(Locale.ROOT)).getString();
			String random = Component.translatable(c.random.enabled || c.activeTool().id().equals("randomise") ? "options.on" : "options.off").getString();
			String mirror = Component.translatable(c.mirror.enabled ? "options.on" : "options.off").getString();
			String text = Component.translatable("gui.howtobuild.dashboard.flags", details, random, mirror).getString();
			Double inset = g == null ? null : g.values().get("inset");
			return inset != null && inset > 0 ? text + " · " + Component.translatable("gui.howtobuild.dashboard.inset", inset.intValue()).getString() : text;
		}, () -> MUTED)));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> {
			java.util.Set<String> missing = BuildSession.get().resolved() == null ? java.util.Set.of() : BuildSession.get().resolved().missingBlocks();
			return missing.isEmpty() ? "" : Component.translatable("gui.howtobuild.dashboard.missing", String.join(", ", missing)).getString();
		}, () -> ERROR)));

		rows.add(header("gui.howtobuild.dashboard.materials"));
		rows.add(row((x, y, w) -> {
			MaterialFilter[] filters = MaterialFilter.values();
			int bw = (w - (filters.length - 1) * 2) / filters.length;

			for (int i = 0; i < filters.length; i++) {
				MaterialFilter f = filters[i];
				Button b = button(x + i * (bw + 2), y, bw, Component.translatable("gui.howtobuild.filter." + f.name().toLowerCase(Locale.ROOT)), () -> {
					materialFilter = f;
					rebuild();
				}, "gui.howtobuild.filter.tooltip");
				b.active = f != materialFilter;
			}
		}));
		rows.addAll(materialCountRows(64, true));
		rows.add(pair((x, y, w) -> toggle(x, y, w, "gui.howtobuild.dashboard.block_list", () -> showBlockList, v -> showBlockList = v,
						"gui.howtobuild.dashboard.block_list.tooltip", true),
				(x, y, w) -> {
					Button reset = button(x, y, w, Component.translatable("gui.howtobuild.dashboard.reset_replacements"), () -> {
						c.materialOverrides.clear();
						changed(true);
					}, "gui.howtobuild.dashboard.reset_replacements.tooltip");
					reset.active = !c.materialOverrides.isEmpty();
				}));

		if (showBlockList) {
			for (MaterialCounter.Count count : filteredCounts(256)) {
				for (var e : count.states().entrySet()) {
					rows.add(note(e.getValue() + " × " + e.getKey(), MUTED));
				}
			}
		}

		rows.add(header("gui.howtobuild.dashboard.resources"));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, this::resourceLine, () -> resourceShortfall() > 0 ? WARNING : GOOD)));
		rows.add(pair((x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.dashboard.compare"), () -> {
					BuildLibrary.Comparison cmp = BuildLibrary.compare();
					comparison = Component.translatable("gui.howtobuild.dashboard.comparison", cmp.correct(), cmp.missing(), cmp.incorrect(), cmp.extra()).getString();
				}, "gui.howtobuild.dashboard.compare.tooltip"),
				(x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.dashboard.save"), () -> setTab(Tab.BUILDS), "gui.howtobuild.dashboard.save.tooltip")));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> comparison, () -> TEXT)));
		return rows;
	}

	/** Material counts that pass the current filter (most used first). */
	private List<MaterialCounter.Count> filteredCounts(int limit) {
		List<MaterialCounter.Count> all = BuildSession.get().materialCounts();
		java.util.Map<String, EnumSet<MaterialRole>> roles = rolesByBlock();
		List<MaterialCounter.Count> out = new ArrayList<>();

		for (MaterialCounter.Count count : all) {
			if (out.size() >= limit) break;

			EnumSet<MaterialRole> r = roles.getOrDefault(count.block(), EnumSet.noneOf(MaterialRole.class));
			boolean keep = switch (materialFilter) {
				case ALL -> true;
				case BLOCKS -> MaterialResolver.block(count.block()).filter(b -> !(b instanceof SlabBlock) && !(b instanceof StairBlock)).isPresent();
				case SLABS -> MaterialResolver.block(count.block()).filter(b -> b instanceof SlabBlock).isPresent();
				case STAIRS -> MaterialResolver.block(count.block()).filter(b -> b instanceof StairBlock).isPresent();
				case DETAILS -> r.stream().anyMatch(role -> EnumSet.of(MaterialRole.TRIM, MaterialRole.ACCENT, MaterialRole.RAIL, MaterialRole.CAP,
						MaterialRole.HIGHLIGHT, MaterialRole.OUTER_EDGE, MaterialRole.INNER_EDGE).contains(role));
				case STRUCTURAL -> r.stream().anyMatch(role -> EnumSet.of(MaterialRole.PRIMARY, MaterialRole.SECONDARY, MaterialRole.SUPPORT, MaterialRole.STEP,
						MaterialRole.FLOOR, MaterialRole.INNER).contains(role));
				case ACCENT -> r.contains(MaterialRole.ACCENT) || r.contains(MaterialRole.HIGHLIGHT);
			};

			if (keep) out.add(count);
		}

		return out;
	}

	/** Which roles use each block in the current preview. */
	private static java.util.Map<String, EnumSet<MaterialRole>> rolesByBlock() {
		BuildSession.Resolved r = BuildSession.get().resolved();
		java.util.Map<String, EnumSet<MaterialRole>> map = new java.util.HashMap<>();

		if (r == null) return map;

		List<com.howtobuild.geometry.Placement> placements = r.result().placements();

		for (int i = 0; i < placements.size(); i++) {
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(r.states()[i].getBlock()).toString();
			map.computeIfAbsent(id, k -> EnumSet.noneOf(MaterialRole.class)).add(placements.get(i).role());
		}

		return map;
	}

	/** Rows with a 3D icon, name, exact count and share per material; with {@code replace}, clicking replaces it. */
	private List<Row> materialCountRows(int limit, boolean replace) {
		List<Row> rows = new ArrayList<>();
		List<MaterialCounter.Count> counts = replace ? filteredCounts(limit) : BuildSession.get().materialCounts().stream().limit(limit).toList();
		long total = MaterialCounter.total(BuildSession.get().materialCounts());

		if (counts.isEmpty()) {
			rows.add(note(Component.translatable("gui.howtobuild.dashboard.no_materials").getString(), MUTED));
			return rows;
		}

		for (MaterialCounter.Count count : counts) {
			rows.add(new Row(22, (x, y, w) -> {
				slot(x, y, () -> stack(count.block()), count.block(), replace ? () -> replaceMaterial(count.block()) : () -> {
				});
				text(x + 24, y + 2, w - 24, name(count.block()) + "  " + count.count() + " (" + String.format(Locale.ROOT, "%.1f", count.percent(total)) + "%)", TEXT);
				text(x + 24, y + 12, w - 24, stateSummary(count), MUTED);
			}));
		}

		return rows;
	}

	/** "facing N 12 · E 10 … · half top 4 …": the state distribution of a material. */
	private static String stateSummary(MaterialCounter.Count count) {
		StringBuilder sb = new StringBuilder();

		for (String property : List.of("facing", "half", "type", "axis", "shape", "waterlogged")) {
			java.util.Map<String, Long> values = MaterialCounter.byProperty(count, property);

			if (values.isEmpty() || values.size() == 1 && property.equals("waterlogged")) continue;
			if (!sb.isEmpty()) sb.append(" · ");

			sb.append(property);
			values.forEach((v, n) -> sb.append(' ').append(v).append(' ').append(n));
		}

		return sb.isEmpty() ? count.states().size() + " state" + (count.states().size() == 1 ? "" : "s") : sb.toString();
	}

	/** Replaces a material everywhere in the preview, keeping facing, half, shape, type, waterlogged and other shared properties. */
	private void replaceMaterial(String shownId) {
		HowToBuildConfig c = config();
		String original = c.materialOverrides.entrySet().stream().filter(e -> e.getValue().equals(shownId)).map(java.util.Map.Entry::getKey).findFirst()
				.orElse(shownId);
		pickBlock(id -> {
			if (id.equals(original)) c.materialOverrides.remove(original);
			else c.materialOverrides.put(original, id);
		});
	}

	/** Blocks still needed from the inventory for a normal (by hand) build. */
	private long resourceShortfall() {
		if (config().build.method != BuildConfig.Method.NORMAL || minecraft.player == null) return 0;

		java.util.Map<String, Long> have = new java.util.HashMap<>();
		var inventory = minecraft.player.getInventory();

		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			net.minecraft.world.level.block.Block block = net.minecraft.world.level.block.Block.byItem(stack.getItem());

			if (!stack.isEmpty() && block != net.minecraft.world.level.block.Blocks.AIR) {
				have.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString(), (long) stack.getCount(), Long::sum);
			}
		}

		long missing = 0;

		for (MaterialCounter.Count count : BuildSession.get().materialCounts()) {
			long need = 0;

			for (var e : count.states().entrySet()) {
				// A double slab is two slab items.
				need += e.getKey().contains("type=double") ? 2 * e.getValue() : e.getValue();
			}

			missing += Math.max(0, need - have.getOrDefault(count.block(), 0L));
		}

		return missing;
	}

	private String resourceLine() {
		if (config().build.method != BuildConfig.Method.NORMAL) {
			CommandPlan plan = BuildSession.get().plan();
			return Component.translatable("gui.howtobuild.dashboard.commands", plan.commands().size(), plan.blocks()).getString();
		}

		long missing = resourceShortfall();
		return missing == 0 ? Component.translatable("gui.howtobuild.dashboard.resources_ok").getString()
				: Component.translatable("gui.howtobuild.dashboard.resources_missing", missing).getString();
	}

	// ---- Saved builds

	private static String saveName = "";
	private static String saveDescription = "";
	private static BuildLibrary.Source saveSource = BuildLibrary.Source.AS_SHOWN;
	private static boolean saveProcedural;
	private static com.howtobuild.saves.BuildFile.Origin saveOrigin = com.howtobuild.saves.BuildFile.Origin.CENTER;
	private static String buildsStatus = "";
	private static String pendingDelete = "";
	private static boolean buildsListed;

	/** The Builds tab: save the current preview (exact or as a procedural preset), and the list of saved builds. */
	private List<Row> savedBuildRows() {
		HowToBuildConfig c = config();
		List<Row> rows = new ArrayList<>();

		if (!buildsListed) {
			buildsListed = true;
			BuildLibrary.refresh(this::rebuildIfOpen);
		}

		rows.add(header("gui.howtobuild.builds.save"));
		rows.add(row((x, y, w) -> textField(x, y, w, "gui.howtobuild.builds.name", 64, () -> saveName, v -> saveName = v)));
		rows.add(row((x, y, w) -> textField(x, y, w, "gui.howtobuild.builds.description", 128, () -> saveDescription, v -> saveDescription = v)));
		rows.add(row((x, y, w) -> textField(x, y, w, "gui.howtobuild.builds.author", 64, () -> c.author, v -> c.author = v)));
		rows.add(pair((x, y, w) -> cycle(x, y, w, "gui.howtobuild.builds.source", () -> saveSource, v -> saveSource = v, BuildLibrary.Source.values(),
						"gui.howtobuild.builds.source.tooltip", false),
				(x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable(saveProcedural ? "gui.howtobuild.builds.procedural" : "gui.howtobuild.builds.exact"),
						() -> saveProcedural = !saveProcedural, "gui.howtobuild.builds.kind.tooltip")));
		rows.add(row((x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable("gui.howtobuild.builds.origin").append(": ")
				.append(Component.translatable("gui.howtobuild.builds.origin." + saveOrigin.name().toLowerCase(Locale.ROOT))), () ->
				saveOrigin = saveOrigin == com.howtobuild.saves.BuildFile.Origin.CENTER ? com.howtobuild.saves.BuildFile.Origin.MIN_CORNER
						: com.howtobuild.saves.BuildFile.Origin.CENTER, "gui.howtobuild.builds.origin.tooltip")));
		rows.add(row((x, y, w) -> dynamicButton(x, y, w, () -> Component.translatable(BuildLibrary.names().stream().anyMatch(n -> n.equalsIgnoreCase(fileStem(saveName)))
				? "gui.howtobuild.builds.overwrite" : "gui.howtobuild.builds.save_button"), this::saveBuild, "gui.howtobuild.builds.save_button.tooltip")));
		rows.add(new Row(12, (x, y, w) -> text(x, y + 2, w, () -> buildsStatus, () -> buildsStatus.startsWith("⚠") ? WARNING : GOOD)));

		rows.add(header("gui.howtobuild.builds.list"));
		rows.add(pair((x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.builds.refresh"), () -> BuildLibrary.refresh(this::rebuildIfOpen), null),
				(x, y, w) -> button(x, y, w, Component.translatable("gui.howtobuild.builds.folder"), () -> {
					try {
						java.nio.file.Files.createDirectories(BuildLibrary.directory());
						net.minecraft.util.Util.getPlatform().openPath(BuildLibrary.directory());
					} catch (java.io.IOException | RuntimeException e) {
						buildsStatus = "⚠ " + BuildLibrary.directory();
					}
				}, "gui.howtobuild.builds.folder.tooltip")));
		List<String> names = BuildLibrary.names();

		if (names.isEmpty()) wrapNotes(rows, Component.translatable("gui.howtobuild.builds.empty", BuildLibrary.directory().toString()).getString(), MUTED);

		for (String name : names) {
			rows.add(new Row(34, (x, y, w) -> {
				BuildLibrary.Info info = BuildLibrary.info(name);
				text(x, y + 1, w - 110, name, ACCENT);
				text(x, y + 12, w - 110, () -> infoLine(BuildLibrary.info(name)), () -> info != null && !info.ok() ? ERROR : MUTED);
				Button load = button(x + w - 108, y + 4, 52, Component.translatable("gui.howtobuild.builds.load"), () -> {
					String error = BuildLibrary.load(name);
					buildsStatus = error == null ? Component.translatable("gui.howtobuild.builds.loaded", name).getString() : "⚠ " + error;
					scroll = 0;
					rebuild();
				}, "gui.howtobuild.builds.load.tooltip");
				load.active = info == null || info.ok();
				button(x + w - 54, y + 4, 54, Component.translatable(pendingDelete.equals(name) ? "gui.howtobuild.builds.confirm_delete" : "gui.howtobuild.builds.delete"),
						() -> {
							if (!pendingDelete.equals(name)) {
								pendingDelete = name;
								rebuild();
								return;
							}

							pendingDelete = "";
							BuildLibrary.delete(name, () -> {
								buildsStatus = Component.translatable("gui.howtobuild.builds.deleted", name).getString();
								rebuildIfOpen();
							});
						}, "gui.howtobuild.builds.delete.tooltip");
			}));
		}

		return rows;
	}

	private static String infoLine(BuildLibrary.Info info) {
		if (info == null) return Component.translatable("gui.howtobuild.builds.reading").getString();
		if (!info.ok()) return "⚠ " + info.error();

		var f = info.file();

		if (f.procedural()) {
			return Component.translatable("gui.howtobuild.builds.preset_info", Component.translatable("tool.howtobuild." + f.tool()).getString(),
					String.format(Locale.ROOT, "%.1f", info.fileSize() / 1024.0)).getString();
		}

		int[] size = f.size();
		return Component.translatable("gui.howtobuild.builds.info", size[0] + " × " + size[1] + " × " + size[2], f.blockCount(), f.palette().size(),
				String.format(Locale.ROOT, "%.1f", info.fileSize() / 1024.0)).getString();
	}

	private static String fileStem(String name) {
		String file = com.howtobuild.saves.HwbCodec.fileName(name);
		return file.substring(0, file.length() - com.howtobuild.saves.HwbCodec.EXTENSION.length());
	}

	private void saveBuild() {
		String name = saveName.isBlank() ? Component.translatable(config().activeTool().translationKey()).getString() : saveName.trim();
		var captured = BuildLibrary.capture(name, saveDescription, saveSource, saveProcedural);
		var file = captured == null ? null : BuildLibrary.withOrigin(captured, saveOrigin);

		if (file == null) {
			buildsStatus = "⚠ " + Component.translatable("gui.howtobuild.builds.nothing").getString();
			return;
		}

		buildsStatus = Component.translatable("gui.howtobuild.builds.saving").getString();
		BuildLibrary.save(fileStem(name), file, error -> {
			buildsStatus = error == null ? Component.translatable("gui.howtobuild.builds.saved", fileStem(name), file.blockCount()).getString() : "⚠ " + error;
			BuildLibrary.refresh(this::rebuildIfOpen);
		});
	}

	private void rebuildIfOpen() {
		if (minecraft != null && minecraft.gui.screen() == this) rebuild();
	}

	/** A labelled single-line text field. */
	private void textField(int x, int y, int w, String labelKey, int maxLength, java.util.function.Supplier<String> get, java.util.function.Consumer<String> set) {
		String label = Component.translatable(labelKey).getString();
		int labelW = Math.min(w / 3, font.width(label) + 6);
		text(x, y + 6, labelW - 4, label, MUTED);
		EditBox box = new EditBox(font, x + labelW, y, w - labelW, CONTROL_HEIGHT, Component.translatable(labelKey));
		box.setMaxLength(maxLength);
		box.setValue(get.get());
		box.setResponder(set);
		var tip = tooltip(labelKey + ".tooltip");

		if (tip != null) box.setTooltip(tip);

		widget(box);
	}

	// ----------------------------------------------------------------- preview pane

	private int paneX;
	private int paneY;
	private int paneSize;
	private Object paneKey;
	private int paneCells;
	private int[] paneColours = new int[0];
	private Object seenResolved;

	/**
	 * The third column on wide screens: a top-down map of the preview (each column coloured by its top block) and a
	 * short summary, so the shape can be checked while the world view is busy.
	 */
	private void buildPreviewPane(int x, int top, int w, int bottom) {
		paneSize = 0;

		if (w < 120) return;

		if (!config().previewPane) {
			toggle(x, top, w, "gui.howtobuild.preview_pane.show", () -> config().previewPane, v -> config().previewPane = v,
					"gui.howtobuild.preview_pane.show.tooltip", true);
			return;
		}

		paneX = x;
		paneY = top + 14;
		paneSize = Math.min(w, Math.max(80, bottom - top - 90));
		panel(x - 4, top - 4, x + w + 4, paneY + paneSize + 76);
		text(x, top, w, Component.translatable("gui.howtobuild.preview_pane").getString(), ACCENT);
		int y = paneY + paneSize + 4;
		text(x, y, w, () -> {
			BuildSession.Resolved r = BuildSession.get().resolved();
			Box b = r == null ? null : r.result().bounds();
			return b == null ? "—" : b.sizeX() + " × " + b.sizeY() + " × " + b.sizeZ();
		}, () -> TEXT);
		text(x, y + 11, w, () -> {
			List<MaterialCounter.Count> counts = BuildSession.get().materialCounts();
			return Component.translatable("gui.howtobuild.dashboard.blocks", MaterialCounter.total(counts), counts.size()).getString();
		}, () -> MUTED);
		text(x, y + 22, w, () -> {
			HowToBuildConfig c = config();
			return Component.translatable("gui.howtobuild.preview_pane.flags",
					Component.translatable(c.random.enabled ? "options.on" : "options.off").getString(),
					Component.translatable(c.mirror.enabled ? "options.on" : "options.off").getString()).getString();
		}, () -> MUTED);
		text(x, y + 33, w, () -> BuildSession.get().isGenerating() ? Component.translatable("gui.howtobuild.status.updating").getString() : "", () -> WARNING);
		toggle(x, y + 46, w, "gui.howtobuild.preview_pane.show", () -> config().previewPane, v -> config().previewPane = v,
				"gui.howtobuild.preview_pane.show.tooltip", true);
	}

	/** Top block colour per column, downsampled to at most 96 × 96 cells (cached per resolved preview). */
	private void updatePaneColours() {
		BuildSession.Resolved r = BuildSession.get().resolved();

		if (r == paneKey) return;

		paneKey = r;
		Box b = r == null ? null : r.result().bounds();

		if (b == null) {
			paneCells = 0;
			return;
		}

		int extent = Math.max(b.sizeX(), b.sizeZ());
		int step = Math.max(1, (extent + 95) / 96);
		paneCells = (extent + step - 1) / step;
		paneColours = new int[paneCells * paneCells];
		int[] topY = new int[paneColours.length];
		java.util.Arrays.fill(topY, Integer.MIN_VALUE);
		List<com.howtobuild.geometry.Placement> placements = r.result().placements();
		int offX = b.minX() - (paneCells * step - b.sizeX()) / 2;
		int offZ = b.minZ() - (paneCells * step - b.sizeZ()) / 2;

		for (int i = 0; i < placements.size(); i++) {
			var p = placements.get(i);
			int u = Math.floorDiv(p.x() - offX, step);
			int v = Math.floorDiv(p.z() - offZ, step);

			if (u < 0 || v < 0 || u >= paneCells || v >= paneCells) continue;

			int cell = v * paneCells + u;

			if (p.y() >= topY[cell]) {
				topY[cell] = p.y();
				int rgb = MaterialResolver.tint(r.states()[i]);
				paneColours[cell] = 0xFF000000 | (p.mirrored() ? blendHalf(rgb, 0xC890FF) : rgb);
			}
		}
	}

	private static int blendHalf(int a, int b) {
		return ((a >> 1) & 0x7F7F7F) + ((b >> 1) & 0x7F7F7F);
	}

	private void drawPreviewPane(GuiGraphicsExtractor graphics) {
		if (paneSize <= 0) return;

		updatePaneColours();
		graphics.fill(paneX, paneY, paneX + paneSize, paneY + paneSize, 0xFF0B1216);

		if (paneCells == 0) return;

		float cell = (float) paneSize / paneCells;

		for (int v = 0; v < paneCells; v++) {
			for (int u = 0; u < paneCells; u++) {
				int colour = paneColours[v * paneCells + u];

				if (colour == 0) continue;

				int x0 = paneX + Math.round(u * cell);
				int y0 = paneY + Math.round(v * cell);
				graphics.fill(x0, y0, Math.max(x0 + 1, paneX + Math.round((u + 1) * cell)), Math.max(y0 + 1, paneY + Math.round((v + 1) * cell)), colour);
			}
		}
	}

	/** Lists that show counts follow the preview: rebuild them when it changes. */
	@Override
	public void tick() {
		super.tick();
		Object current = BuildSession.get().resolved();

		if (current != seenResolved) {
			seenResolved = current;

			if (tab() == Tab.BUILD || tab() == Tab.RANDOMISE) rebuild();
		}
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
		drawPreviewPane(graphics);

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
