package com.howtobuild.gametest;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import com.howtobuild.building.BuildAnalysis;
import com.howtobuild.building.CommandExecutor;
import com.howtobuild.building.CommandPermission;
import com.howtobuild.client.BuildSession;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailPreset;
import com.howtobuild.dimensions.DimensionFormat;
import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.gui.BlockPickerScreen;
import com.howtobuild.gui.CommandBuildScreen;
import com.howtobuild.gui.HowToBuildScreen;
import com.howtobuild.input.CentreSelectionHandler;
import com.howtobuild.input.KeyBindings;
import com.howtobuild.materials.BlockCatalog;
import com.howtobuild.materials.MaterialCategory;
import com.howtobuild.materials.MaterialResolver;
import com.howtobuild.render.LabelSet;
import com.howtobuild.render.RenderStats;
import com.howtobuild.transform.MirrorAxis;
import com.howtobuild.transform.MirrorMode;

/**
 * End-to-end acceptance test in a real Minecraft 26.2 client. It drives the GUI and key bindings, generates every
 * tool, checks the generated blocks, labels, mirror and block picker against the real registries, measures render
 * cost, verifies that previewing never changes the world, and finally builds a staircase with commands (as an
 * operator) and checks every block state on the server, then undoes it. Screenshots go to
 * {@code build/run/clientGameTest/screenshots}.
 */
@SuppressWarnings("UnstableApiUsage")
public class HowToBuildClientGameTest implements FabricClientGameTest {
	private static final BlockPos CENTRE = new BlockPos(0, -60, 0);
	private static final BlockPos BUILD_CENTRE = new BlockPos(8, -60, 90);

	private TestSingleplayerContext singleplayer;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> reset(HowToBuildConfig.get()));

		// Cheats on, like a creative world: the command build test needs the player to have command permission.
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(HowToBuildClientGameTest::allowCommands).create()) {
			this.singleplayer = singleplayer;
			singleplayer.getServer().runCommand("time set noon");
			// Creative + flying: the camera stays where it is teleported and the player cannot take fall damage.
			singleplayer.getServer().runCommand("gamemode creative @a");
			context.waitTicks(220);
			singleplayer.getServer().runCommand("tp @a 0.5 -60 0.5 0 90");
			singleplayer.getConnection().waitForChunksRender();
			context.waitTicks(5);

			List<BlockState> before = snapshot(singleplayer);

			testGui(context);
			testCircle(context);
			testCylinder(context);
			testSpiral(context);
			testSphere(context);
			testDome(context);
			testCorridor(context);
			testMirror(context);
			testSpiralFollowsCircle(context);
			testEvenCentreAndOffsets(context);
			testCorridorMaterials(context);
			testLabels(context);
			testBlockPicker(context);
			testLargeShapes(context);
			testCentreSelectionKey(context);
			testGuiOpenCloseRepeatedly(context);

			List<BlockState> after = snapshot(singleplayer);
			check(before.equals(after), "Previewing never placed or modified a real block");

			testCommandBuild(context);
		}
	}

	// ----------------------------------------------------------------- helpers

	/** Turns on "Allow Commands" in the create-world settings (the setter's name is looked up, not assumed). */
	private static void allowCommands(Object creator) {
		for (java.lang.reflect.Method m : creator.getClass().getMethods()) {
			if (m.getName().startsWith("setAllow") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class) {
				try {
					m.invoke(creator, true);
					System.out.println("[howtobuild] world setting " + m.getName() + "(true)");
				} catch (ReflectiveOperationException e) {
					throw new AssertionError("Could not call " + m.getName(), e);
				}
			}
		}
	}

	private static void reset(HowToBuildConfig c) {
		c.tool = "circle";
		c.tab = "GEOMETRY";
		c.advanced = false;
		c.toolSettings.clear();
		c.materialTypes = new ArrayList<>(List.of(ShapeKind.BLOCKS));
		c.detailPreset = DetailPreset.NONE;
		c.customDetails = new ArrayList<>();
		c.pattern = com.howtobuild.details.MaterialPattern.NONE;
		c.variation = com.howtobuild.details.MaterialVariation.NONE;
		c.rotation = 0;
		c.offsetX = 0;
		c.offsetY = 0;
		c.offsetZ = 0;
		c.alignX = true;
		c.alignY = true;
		c.alignZ = true;
		c.lockToBlockCentre = true;
		c.materials = HowToBuildConfig.defaultMaterials();
		c.labels = new com.howtobuild.config.LabelSettings();
		c.mirror = new com.howtobuild.config.MirrorConfig();
		c.hologram.visible = true;
		c.hologram.seeThroughBlocks = false;
		c.build.commandsPerTick = 2;
		c.build.keepExisting = false;
		c.sanitize();
	}

	/** Selects a tool with the given parameters (everything else default) at the test centre and waits for it. */
	private GeometryResult use(ClientGameTestContext context, String tool, Map<String, String> values, Consumer<HowToBuildConfig> extra) {
		context.runOnClient(client -> {
			HowToBuildConfig c = HowToBuildConfig.get();
			reset(c);
			c.tool = tool;

			for (Map.Entry<String, String> e : values.entrySet()) {
				c.setSetting(c.activeTool(), e.getKey(), e.getValue());
			}

			extra.accept(c);
			c.sanitize();
			BuildSession.get().setAnchor(CENTRE);
		});
		return awaitGeometry(context);
	}

	private static Map<String, String> params(String... keyValues) {
		Map<String, String> map = new LinkedHashMap<>();

		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}

		return map;
	}

	private GeometryResult awaitGeometry(ClientGameTestContext context) {
		context.waitTicks(2);

		for (int i = 0; i < 600; i++) {
			GeometryResult result = context.computeOnClient(client -> BuildSession.get().isGenerating() ? null : BuildSession.get().geometry());

			if (result != null) return result;

			context.waitTicks(1);
		}

		throw new AssertionError("Geometry generation timed out");
	}

	private void view(ClientGameTestContext context, String tp) {
		context.runOnClient(client -> {
			if (client.player != null) client.player.getAbilities().flying = true;
		});
		singleplayer.getServer().runCommand("tp @a " + tp);
		context.waitTicks(15);
	}

	// ----------------------------------------------------------------- GUI

	private void testGui(ClientGameTestContext context) {
		context.getInput().pressKey(KeyBindings.OPEN_SETTINGS);
		context.waitForScreen(HowToBuildScreen.class);
		context.clickScreenButton("gui.howtobuild.use_position");
		check(CENTRE.equals(context.computeOnClient(client -> BuildSession.get().anchor())), "My position centres the preview on the player");
		awaitGeometry(context);
		context.takeScreenshot("gui_geometry");

		for (String tab : List.of("center", "materials", "details", "labels", "mirror", "build", "geometry")) {
			context.clickScreenButton("gui.howtobuild.tab." + tab);
			context.waitTicks(2);
			check(context.computeOnClient(client -> HowToBuildConfig.get().tab.equalsIgnoreCase(tab)), "Tab " + tab + " opens");
			context.takeScreenshot("gui_" + tab);
		}

		context.clickScreenButton("tool.howtobuild.spiral");
		check(context.computeOnClient(client -> HowToBuildConfig.get().tool.equals("spiral")), "Choosing a tool in the list selects it");
		awaitGeometry(context);
		context.takeScreenshot("gui_spiral");
		context.clickScreenButton("tool.howtobuild.circle");
		context.clickScreenButton("gui.howtobuild.mode.simple");
		check(context.computeOnClient(client -> HowToBuildConfig.get().advanced), "Simple / Advanced toggle switches to Advanced");
		context.takeScreenshot("gui_advanced");
		context.clickScreenButton("gui.howtobuild.mode.advanced");
		context.clickScreenButton("gui.howtobuild.build_button");
		context.waitForScreen(CommandBuildScreen.class);
		context.takeScreenshot("gui_build_screen");
		context.setScreen(() -> null);
		context.waitTicks(2);
	}

	// ----------------------------------------------------------------- tools

	private void testCircle(ClientGameTestContext context) {
		GeometryResult odd = use(context, "circle", params("size", "15", "fill", "OUTLINE"), c -> {
		});
		check(odd.width() == 15 && odd.length() == 15 && odd.height() == 1, "Circle 15 spans exactly 15 × 15");
		check(odd.centreCells().size() == 1, "Odd circle has a 1×1 centre");
		view(context, "0.5 -45 -16 0 45");
		context.takeScreenshot("circle_15_outline");

		GeometryResult even = use(context, "circle", params("size", "16", "fill", "OUTLINE"), c -> {
		});
		check(even.width() == 16 && even.centreCells().size() == 4, "Even circle has a 2×2 centre");
		context.waitTicks(5);
		context.takeScreenshot("circle_16_outline_2x2_centre");

		GeometryResult oval = use(context, "oval", params("width", "21", "length", "13", "fill", "FILLED"), c -> {
		});
		check(oval.width() == 21 && oval.length() == 13, "Oval 21 × 13 spans exactly 21 × 13");
		context.waitTicks(5);
		context.takeScreenshot("oval_21x13_filled");
	}

	private void testCylinder(ClientGameTestContext context) {
		GeometryResult plain = use(context, "cylinder", params("width", "11", "length", "11", "height", "12", "style", "HOLLOW", "thickness", "1"), c -> {
		});
		check(plain.width() == 11 && plain.height() == 12 && plain.length() == 11, "Cylinder is 11 × 12 × 11");
		check(isEmptyAtCentre(plain), "Hollow cylinder is empty inside");
		// The detailed preset adds a rim that projects one block beyond the wall at the top.
		GeometryResult r = use(context, "cylinder", params("width", "11", "length", "11", "height", "12", "style", "HOLLOW", "thickness", "1"),
				c -> c.detailPreset = DetailPreset.DETAILED);
		check(isEmptyAtCentre(r), "Detailed hollow cylinder is still empty inside");
		check(r.count(MaterialRole.TRIM) > 0, "Cylinder trim details are generated");
		view(context, "0.5 -48 -14 0 30");
		context.takeScreenshot("cylinder_hollow_trim");
	}

	private void testSpiral(ClientGameTestContext context) {
		GeometryResult r = use(context, "spiral", params("diameter", "13", "stair_width", "3", "height", "16", "revolutions", "2"),
				c -> c.materialTypes = new ArrayList<>(List.of(ShapeKind.BLOCKS, ShapeKind.SLABS, ShapeKind.STAIRS)));
		check(r.count(BlockShape.Kind.STAIRS) > 0, "Spiral uses stairs");
		check(r.count(BlockShape.Kind.SLAB) > 0, "Spiral uses slabs");
		check(r.count(BlockShape.Kind.FULL) > 0, "Spiral uses full blocks");
		check(r.height() == 16, "Spiral is 16 blocks high");
		boolean statesValid = context.computeOnClient(client -> {
			BuildSession.Resolved resolved = BuildSession.get().resolved();

			for (int i = 0; i < resolved.states().length; i++) {
				BlockShape shape = resolved.result().placements().get(i).shape();
				BlockState state = resolved.states()[i];

				if (shape.kind() == BlockShape.Kind.STAIRS && !(state.getBlock() instanceof StairBlock)) return false;
				if (shape.kind() == BlockShape.Kind.SLAB && !(state.getBlock() instanceof SlabBlock)) return false;
			}

			return true;
		});
		check(statesValid, "Every stair placement resolves to a stair block and every slab placement to a slab block");
		view(context, "0.5 -46 -14 0 35");
		context.takeScreenshot("spiral_blocks_slabs_stairs");

		GeometryResult detailed = use(context, "spiral", params("diameter", "13", "stair_width", "3", "height", "16", "revolutions", "2"), c -> {
			c.materialTypes = new ArrayList<>(List.of(ShapeKind.STAIRS, ShapeKind.SLABS));
			c.detailPreset = DetailPreset.ARCHITECTURAL;
		});
		check(detailed.blockCount() > r.blockCount() / 2, "Spiral with only stairs and slabs generates");
		context.waitTicks(5);
		context.takeScreenshot("spiral_architectural");
	}

	private void testSphere(ClientGameTestContext context) {
		GeometryResult r = use(context, "sphere", params("diameter_x", "15", "diameter_y", "15", "diameter_z", "15", "style", "HOLLOW", "thickness", "1"),
				c -> c.detailPreset = DetailPreset.DETAILED);
		check(r.width() == 15 && r.height() == 15 && r.length() == 15, "Sphere 15 spans exactly 15 in every direction");
		check(isEmptyAtCentre(r), "Hollow sphere is empty inside");
		check(r.count(MaterialRole.PRIMARY) < r.blockCount(), "Detailed sphere uses detail materials");
		view(context, "0.5 -45 -16 0 25");
		context.takeScreenshot("sphere_hollow_detailed");
	}

	private void testDome(ClientGameTestContext context) {
		GeometryResult r = use(context, "dome", params("width", "21", "length", "21", "height", "11", "style", "HOLLOW"), c -> {
			c.detailPreset = DetailPreset.CUSTOM;
			c.customDetails = new ArrayList<>(List.of(DetailFeature.RADIAL_RIBS, DetailFeature.CROWN));
		});
		check(r.count(MaterialRole.TRIM) + r.count(MaterialRole.ACCENT) + r.count(MaterialRole.CAP) > 0, "Dome ribs and crown are generated");
		view(context, "0.5 -44 -22 0 25");
		context.takeScreenshot("dome_ribs_crown");
	}

	private void testCorridor(ClientGameTestContext context) {
		GeometryResult r = use(context, "corridor", params("width", "6", "height", "10", "length", "20", "profile", "ARCH", "arch_interval", "5"), c -> {
		});
		check(r.width() == 6 && r.height() == 10 && r.length() == 20, "Arch corridor is exactly 6 wide, 10 high and 20 long, got "
				+ r.width() + " × " + r.height() + " × " + r.length());
		GeometryResult detailed = use(context, "corridor", params("width", "6", "height", "10", "length", "20", "profile", "ARCH", "arch_interval", "5"),
				c -> c.detailPreset = DetailPreset.ARCHITECTURAL);
		check(detailed.values().getOrDefault("arches", 0.0) >= 3, "Corridor has repeating arches");
		view(context, "-10.5 -50 -6 -110 25");
		context.takeScreenshot("corridor_arch_6x10x20");
	}

	private void testMirror(ClientGameTestContext context) {
		use(context, "rectangle", params("width", "5", "length", "3", "fill", "FILLED"), c -> {
			c.mirror.enabled = true;
			c.mirror.axis = MirrorAxis.X;
			c.mirror.mode = MirrorMode.DUPLICATE;
			c.mirror.offsetX = 3;
		});
		boolean exact = context.computeOnClient(client -> {
			BuildSession.Resolved r = BuildSession.get().resolved();
			List<Placement> placements = r.result().placements();
			int plane = r.mirrorPlaneX();
			int originals = 0;

			for (Placement p : placements) {
				if (p.mirrored()) continue;

				originals++;

				if (r.result().at(plane - 1 - p.x(), p.y(), p.z()) == null) return false;
			}

			return originals * 2 == placements.size();
		});
		check(exact, "Mirror with offset places an exact reflected copy of every block");
		view(context, "0.5 -48 -10 0 50");
		context.takeScreenshot("mirror_x_offset");
	}

	/** (x, z) world columns of the current preview. */
	private static java.util.Set<List<Integer>> worldColumns(ClientGameTestContext context) {
		return context.computeOnClient(client -> {
			BuildSession.Resolved r = BuildSession.get().resolved();
			java.util.Set<List<Integer>> columns = new java.util.HashSet<>();

			for (int i = 0; i < r.states().length; i++) {
				BlockPos pos = r.worldPos(i);
				columns.add(List.of(pos.getX(), pos.getZ()));
			}

			return columns;
		});
	}

	private void testSpiralFollowsCircle(ClientGameTestContext context) {
		use(context, "circle", params("size", "33", "fill", "FILLED"), c -> {
		});
		java.util.Set<List<Integer>> circle = worldColumns(context);
		GeometryResult spiral = use(context, "spiral", params("follow_circle", "true", "circle_width", "33", "stair_width", "4", "height", "12"), c -> {
			c.materialTypes = new ArrayList<>(List.of(ShapeKind.BLOCKS, ShapeKind.SLABS, ShapeKind.STAIRS));
			c.detailPreset = DetailPreset.ARCHITECTURAL;
		});
		java.util.Set<List<Integer>> columns = worldColumns(context);
		check(circle.containsAll(columns), "Spiral following a 33 × 33 circle never leaves the circle's blocks in the world");
		check(spiral.width() == 33 && spiral.length() == 33, "Spiral footprint is exactly 33 × 33");
		check(spiral.bounds().minY() == 0, "Spiral starts on the centre's layer, nothing below it");
		check(!spiral.guides().isEmpty(), "The master circle outline is available as a guide");
		view(context, "0.5 -38 -26 0 40");
		context.takeScreenshot("spiral_follows_circle_33");
	}

	private void testEvenCentreAndOffsets(ClientGameTestContext context) {
		GeometryResult circle = use(context, "circle", params("size", "32", "fill", "OUTLINE"), c -> {
		});
		GeometryResult spiral = use(context, "spiral", params("follow_circle", "true", "circle_width", "32", "stair_width", "3"), c -> {
		});
		check(circle.centreCells().size() == 4 && spiral.centreCells().size() == 4, "32 × 32 circle and spiral both have a 2×2 centre");

		for (int[] cell : circle.centreCells()) {
			check(spiral.centreCells().stream().anyMatch(c -> c[0] == cell[0] && c[2] == cell[2]), "Circle and spiral share centre cell " + cell[0] + "," + cell[2]);
		}

		view(context, "0.5 -48 -14 0 45");
		context.takeScreenshot("spiral_32_two_by_two_centre");

		use(context, "circle", params("size", "33", "fill", "FILLED"), c -> {
			c.offsetX = 7;
			c.offsetY = -3;
			c.offsetZ = 4;
		});
		java.util.Set<List<Integer>> circleMoved = worldColumns(context);
		int circleMinY = context.computeOnClient(client -> BuildSession.get().resolved().worldPos(0).getY());
		use(context, "spiral", params("follow_circle", "true", "circle_width", "33"), c -> {
			c.offsetX = 7;
			c.offsetY = -3;
			c.offsetZ = 4;
		});
		check(circleMoved.containsAll(worldColumns(context)), "With offsets X +7, Z +4 the spiral still sits inside the moved circle");
		BlockPos origin = context.computeOnClient(client -> BuildSession.get().origin());
		check(origin.equals(CENTRE.offset(7, -3, 4)), "Offsets are relative to the selected centre: origin " + origin);
		check(circleMinY == CENTRE.getY() - 3, "Offset Y -3 moves the circle down exactly 3 blocks");
	}

	private void testCorridorMaterials(ClientGameTestContext context) {
		use(context, "corridor", params("width", "9", "height", "10", "length", "12", "structure_part", "BLOCKS", "curve_part", "STAIRS",
				"trim_part", "SLABS"), c -> {
			c.materialTypes = new ArrayList<>(List.of(ShapeKind.BLOCKS, ShapeKind.SLABS, ShapeKind.STAIRS));
			c.detailPreset = DetailPreset.SIMPLE;
		});
		boolean ok = context.computeOnClient(client -> {
			BuildSession.Resolved r = BuildSession.get().resolved();
			boolean stairs = false;
			boolean slabs = false;

			for (int i = 0; i < r.states().length; i++) {
				BlockShape shape = r.result().placements().get(i).shape();
				BlockState state = r.states()[i];

				if (shape.isStairs()) {
					if (!(state.getBlock() instanceof StairBlock)) return false;
					stairs = true;
				}

				if (shape.isSlab()) {
					if (!(state.getBlock() instanceof SlabBlock)) return false;
					slabs = true;
				}
			}

			return stairs && slabs;
		});
		check(ok, "Arch corridor with block structure, stair curve and slab trim resolves to real stair and slab blocks");
		view(context, "-10.5 -50 -2 -110 25");
		context.takeScreenshot("corridor_stairs_slabs");
	}

	private void testLabels(ClientGameTestContext context) {
		use(context, "rectangle", params("width", "6", "length", "1", "fill", "FILLED"), c -> c.labels.format = DimensionFormat.SIMPLIFIED);
		List<String> simplified = labelTexts(context);
		check(simplified.contains("6"), "A 6 × 1 section is labelled \"6\" with the simplified format, got " + simplified);
		context.runOnClient(client -> HowToBuildConfig.get().labels.format = DimensionFormat.FULL);
		List<String> full = labelTexts(context);
		check(full.contains("6 × 1"), "Changing the format updates the label immediately to \"6 × 1\", got " + full);
		context.runOnClient(client -> {
			HowToBuildConfig.get().labels.format = DimensionFormat.CUSTOM;
			HowToBuildConfig.get().labels.template = "Size {width}";
		});
		check(labelTexts(context).contains("Size 6"), "Custom label templates are applied");
		view(context, "0.5 -55 -5 0 45");
		context.takeScreenshot("labels_rectangle_6");
	}

	private static List<String> labelTexts(ClientGameTestContext context) {
		return context.computeOnClient(client -> {
			LabelSet labels = BuildSession.get().labels();
			return labels == null ? List.of() : List.of(labels.plainTexts);
		});
	}

	private void testBlockPicker(ClientGameTestContext context) {
		context.setScreen(() -> new BlockPickerScreen(new HowToBuildScreen(), MaterialRole.PRIMARY, ShapeKind.SLABS, entry -> {
		}));
		context.waitForScreen(BlockPickerScreen.class);
		check(context.computeOnClient(client -> {
			List<BlockCatalog.Entry> entries = ((BlockPickerScreen) client.gui.screen()).entries();
			return !entries.isEmpty() && entries.stream().allMatch(e -> e.block() instanceof SlabBlock);
		}), "Slabs filter shows only real slab blocks");
		context.takeScreenshot("block_picker_slabs");

		check(context.computeOnClient(client -> {
			List<BlockCatalog.Entry> entries = BlockCatalog.filter("", MaterialCategory.ALL, EnumSet.of(ShapeKind.SLABS, ShapeKind.STAIRS));
			boolean union = entries.stream().allMatch(e -> e.block() instanceof SlabBlock || e.block() instanceof StairBlock);
			boolean both = entries.stream().anyMatch(e -> e.block() instanceof SlabBlock) && entries.stream().anyMatch(e -> e.block() instanceof StairBlock);
			return union && both;
		}), "Slabs + Stairs shows the union of both types");
		check(context.computeOnClient(client -> BlockCatalog.filter("oak", MaterialCategory.ALL, EnumSet.of(ShapeKind.STAIRS)).stream()
				.anyMatch(e -> e.id().equals("minecraft:oak_stairs"))), "Search finds oak stairs");
		check(context.computeOnClient(client -> BlockCatalog.filter("", MaterialCategory.WALLS, EnumSet.noneOf(ShapeKind.class)).stream()
				.allMatch(e -> e.block() instanceof WallBlock)), "Walls category shows only wall blocks");
		check(context.computeOnClient(client -> BlockCatalog.filter("", MaterialCategory.ALL, EnumSet.of(ShapeKind.BLOCKS)).stream()
				.noneMatch(e -> e.block() instanceof SlabBlock || e.block() instanceof StairBlock)), "Blocks filter excludes slabs and stairs");
		context.runOnClient(client -> ((BlockPickerScreen) client.gui.screen()).setFilter("stone", MaterialCategory.ALL,
				EnumSet.of(ShapeKind.BLOCKS, ShapeKind.STAIRS)));
		context.waitTicks(2);
		context.takeScreenshot("block_picker_search_stone");
		context.setScreen(() -> null);
		context.waitTicks(2);
	}

	private void testLargeShapes(ClientGameTestContext context) {
		for (String fill : List.of("FILLED", "OUTLINE")) {
			use(context, "circle", params("size", "100", "fill", fill), c -> {
			});
			view(context, "0.5 -5 -75 0 35");
			int revision = context.computeOnClient(client -> BuildSession.get().revision());
			context.runOnClient(client -> RenderStats.reset());
			context.waitTicks(40);
			check(revision == context.computeOnClient(client -> BuildSession.get().revision()), "Geometry is cached between frames");
			long frames = context.computeOnClient(client -> RenderStats.frames());
			double millis = context.computeOnClient(client -> RenderStats.averageMillisPerFrame());
			System.out.printf("[howtobuild] 100x100 circle %s: %d frames, %.3f ms CPU per frame%n", fill, frames, millis);
			check(frames > 0 && millis < 25, "100×100 " + fill + " circle renders cheaply (" + millis + " ms/frame)");
			context.takeScreenshot("circle_100_" + fill.toLowerCase(java.util.Locale.ROOT));
		}

		use(context, "sphere", params("diameter_x", "64", "diameter_y", "64", "diameter_z", "64", "style", "HOLLOW"), c -> {
		});
		view(context, "0.5 -20 -70 0 15");
		context.runOnClient(client -> RenderStats.reset());
		context.waitTicks(30);
		double millis = context.computeOnClient(client -> RenderStats.averageMillisPerFrame());
		System.out.printf("[howtobuild] 64 hollow sphere: %.3f ms CPU per frame%n", millis);
		check(millis < 50, "64-block hollow sphere renders cheaply (" + millis + " ms/frame)");
		context.takeScreenshot("sphere_64_hollow");
	}

	private void testCentreSelectionKey(ClientGameTestContext context) {
		BlockPos expected = new BlockPos(3, -60, 5);
		use(context, "circle", params("size", "9"), c -> {
		});
		context.runOnClient(client -> BuildSession.get().clear());
		singleplayer.getServer().runCommand("tp @a 3.5 -60 5.5 0 90");
		context.waitTicks(10);
		context.getInput().pressKey(KeyBindings.SELECT_CENTRE);
		context.waitTicks(3);
		check(context.computeOnClient(client -> CentreSelectionHandler.get().isActive()), "Select key enters centre selection mode");
		BlockPos marker = context.computeOnClient(client -> CentreSelectionHandler.get().markerPos());
		check(expected.equals(marker), "Marker targets the block above the looked-at ground, got " + marker);
		context.takeScreenshot("centre_selection_marker");
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		check(!context.computeOnClient(client -> CentreSelectionHandler.get().isActive()), "Left-click confirms the selection");
		check(expected.equals(context.computeOnClient(client -> BuildSession.get().anchor())), "Confirmed centre becomes the preview centre");

		context.getInput().pressKey(KeyBindings.SELECT_CENTRE);
		context.waitTicks(2);
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		context.waitTicks(3);
		check(!context.computeOnClient(client -> CentreSelectionHandler.get().isActive()), "Right-click cancels selection");
		check(expected.equals(context.computeOnClient(client -> BuildSession.get().anchor())), "Cancelling keeps the previous centre");
	}

	private void testGuiOpenCloseRepeatedly(ClientGameTestContext context) {
		for (int i = 0; i < 3; i++) {
			context.getInput().pressKey(KeyBindings.OPEN_SETTINGS);
			context.waitForScreen(HowToBuildScreen.class);
			context.waitTicks(2);
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitForScreen(null);
		}
	}

	// ----------------------------------------------------------------- command build

	private void testCommandBuild(ClientGameTestContext context) {
		singleplayer.getServer().runCommand("tp @a 8.5 -50 80.5 0 45");
		singleplayer.getConnection().waitForChunksRender();
		context.waitTicks(20);
		CommandPermission.Status status = context.computeOnClient(client -> CommandPermission.check());
		check(status == CommandPermission.Status.AVAILABLE, "Operator is detected as allowed to build with commands, got " + status);

		context.runOnClient(client -> {
			HowToBuildConfig c = HowToBuildConfig.get();
			reset(c);
			c.tool = "spiral";
			c.setSetting(c.activeTool(), "follow_circle", "true");
			c.setSetting(c.activeTool(), "circle_width", "9");
			c.setSetting(c.activeTool(), "stair_width", "2");
			c.setSetting(c.activeTool(), "height", "8");
			c.setSetting(c.activeTool(), "revolutions", "1");
			c.materialTypes = new ArrayList<>(List.of(ShapeKind.BLOCKS, ShapeKind.SLABS, ShapeKind.STAIRS));
			// Column (long runs of identical blocks → /fill), rails and both landings (the old Y − 1 bug).
			c.detailPreset = DetailPreset.DETAILED;
			c.sanitize();
			BuildSession.get().setAnchor(BUILD_CENTRE);
		});
		awaitGeometry(context);

		BuildAnalysis analysis = context.computeOnClient(client -> {
			HowToBuildConfig c = HowToBuildConfig.get();
			return BuildAnalysis.analyse(BuildSession.get().resolved(), new MaterialResolver(c.materials), c.build);
		});
		check(analysis.plan().fills() > 0 && analysis.plan().setblocks() > 0, "Plan uses both /fill and /setblock");
		check(analysis.plan().commands().stream().anyMatch(cmd -> cmd.contains("facing=")), "Plan preserves stair block states");
		check(!analysis.destructive(), "Building in open air replaces nothing");
		List<BlockState> groundBefore = ground(singleplayer);

		Map<BlockPos, BlockState> expected = context.computeOnClient(client -> {
			BuildSession.Resolved r = BuildSession.get().resolved();
			Map<BlockPos, BlockState> map = new LinkedHashMap<>();

			for (int i = 0; i < r.states().length; i++) {
				map.put(r.worldPos(i), r.states()[i]);
			}

			return map;
		});

		check(context.computeOnClient(client -> CommandExecutor.get().start(analysis.plan(), analysis.undo(), "test", 20)), "Command build starts");
		awaitExecutor(context);
		context.takeScreenshot("command_build_spiral");
		int mismatches = singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			int bad = 0;

			for (Map.Entry<BlockPos, BlockState> e : expected.entrySet()) {
				if (!level.getBlockState(e.getKey()).equals(e.getValue())) bad++;
			}

			return bad;
		});
		check(mismatches == 0, "Every block built by commands matches the preview exactly, including stair and slab states (" + mismatches + " mismatches)");
		check(expected.keySet().stream().allMatch(pos -> pos.getY() >= BUILD_CENTRE.getY()), "No planned block lies below the centre's layer");
		check(groundBefore.equals(ground(singleplayer)), "The ground under the staircase is untouched by the command build");

		check(context.computeOnClient(client -> CommandExecutor.get().undo(20)), "Undo starts");
		awaitExecutor(context);
		int remaining = singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			int left = 0;

			for (BlockPos pos : expected.keySet()) {
				if (!level.getBlockState(pos).isAir()) left++;
			}

			return left;
		});
		check(remaining == 0, "Undo restores the previous blocks (" + remaining + " left)");
	}

	private void awaitExecutor(ClientGameTestContext context) {
		for (int i = 0; i < 1200; i++) {
			CommandExecutor.State state = context.computeOnClient(client -> CommandExecutor.get().state());

			if (state == CommandExecutor.State.FINISHED) {
				context.waitTicks(20);
				return;
			}

			if (state == CommandExecutor.State.FAILED || state == CommandExecutor.State.STOPPED || state == CommandExecutor.State.PAUSED) {
				String error = context.computeOnClient(client -> String.valueOf(CommandExecutor.get().lastError() == null ? null
						: CommandExecutor.get().lastError().getString()));
				throw new AssertionError("Command build " + state + ": " + error);
			}

			context.waitTicks(1);
		}

		throw new AssertionError("Command build timed out");
	}

	// ----------------------------------------------------------------- utilities

	private static boolean isEmptyAtCentre(GeometryResult r) {
		var b = r.bounds();
		return r.at((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2, (b.minZ() + b.maxZ()) / 2) == null;
	}

	/** The ground layers directly below the command-build area. */
	private static List<BlockState> ground(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			List<BlockState> states = new ArrayList<>();

			for (BlockPos pos : BlockPos.betweenClosed(BUILD_CENTRE.getX() - 6, BUILD_CENTRE.getY() - 3, BUILD_CENTRE.getZ() - 6,
					BUILD_CENTRE.getX() + 6, BUILD_CENTRE.getY() - 1, BUILD_CENTRE.getZ() + 6)) {
				states.add(level.getBlockState(pos));
			}

			return states;
		});
	}

	private static List<BlockState> snapshot(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			List<BlockState> states = new ArrayList<>();

			for (BlockPos pos : BlockPos.betweenClosed(-60, -64, -60, 60, -40, 60)) {
				states.add(level.getBlockState(pos));
			}

			return states;
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}

		System.out.println("[howtobuild] PASS: " + message);
	}
}
