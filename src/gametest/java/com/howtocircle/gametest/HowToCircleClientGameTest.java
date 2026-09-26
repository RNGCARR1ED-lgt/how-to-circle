package com.howtocircle.gametest;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import com.howtocircle.client.HologramManager;
import com.howtocircle.config.HowToCircleConfig;
import com.howtocircle.dimensions.LabelFormat;
import com.howtocircle.geometry.CentreSize;
import com.howtocircle.geometry.FillMode;
import com.howtocircle.geometry.ResolvedDimensions;
import com.howtocircle.geometry.ShapePlacement;
import com.howtocircle.geometry.ShapeType;
import com.howtocircle.gui.CircleSettingsScreen;
import com.howtocircle.input.CentreSelectionHandler;
import com.howtocircle.input.KeyBindings;
import com.howtocircle.render.HologramGeometry;
import com.howtocircle.render.RenderStats;

/**
 * End-to-end test in a real Minecraft 26.2 client: opens the GUI, generates circles and ovals of every parity, toggles
 * labels, renders large shapes, uses the centre selection key binding and verifies that no real block was changed.
 * Screenshots are written to {@code build/run/clientGameTest/screenshots}.
 */
@SuppressWarnings("UnstableApiUsage")
public class HowToCircleClientGameTest implements FabricClientGameTest {
	private static final BlockPos CENTRE = new BlockPos(0, -60, 0);

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> resetConfig());

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			this.singleplayer = singleplayer;
			singleplayer.getServer().runCommand("time set noon");
			singleplayer.getServer().runCommand("tp @a 0.5 -60 0.5 0 90");
			singleplayer.getConnection().waitForChunksRender();
			context.waitTicks(5);

			List<BlockState> before = snapshot(singleplayer);

			testGuiOpensAndGenerates(context);
			testCircle(context, 15, FillMode.OUTLINE, 1, "circle_15_outline");
			testCircle(context, 15, FillMode.FILLED, 1, "circle_15_filled");
			testCircle(context, 16, FillMode.OUTLINE, 2, "circle_16_outline_2x2_centre");
			testOval(context, 21, 13, FillMode.FILLED, "1×1", "oval_21x13_filled");
			testOval(context, 20, 12, FillMode.OUTLINE, "2×2", "oval_20x12_outline");
			testOval(context, 21, 12, FillMode.OUTLINE, "1×2", "oval_21x12_mixed_parity");
			testForcedCentre(context);
			testLabelToggles(context);
			testVerticalAndRotation(context);
			testLargeShapes(context, singleplayer);
			testClearAndRegenerate(context);
			testCentreSelectionKey(context, singleplayer);
			testGuiOpenCloseRepeatedly(context);

			List<BlockState> after = snapshot(singleplayer);
			check(before.equals(after), "No real blocks were placed or modified");
		}
	}

	private static void resetConfig() {
		HowToCircleConfig config = HowToCircleConfig.get();
		config.shapeType = ShapeType.CIRCLE;
		config.width = 15;
		config.height = 15;
		config.fillMode = FillMode.OUTLINE;
		config.centreSize = CentreSize.AUTO;
		config.plane = ShapePlacement.Plane.HORIZONTAL;
		config.rotated = false;
		config.alignPositiveU = true;
		config.alignPositiveV = true;
		config.verticalOffset = 0;
		config.lockToBlockCentre = true;
		config.showHologram = true;
		config.showDimensions = true;
		config.showPopups = true;
		config.popupsFacePlayer = true;
		config.labelFormat = LabelFormat.LONG_BY_SHORT;
		config.seeThroughBlocks = false;
		HologramManager.get().clear();
	}

	private TestSingleplayerContext singleplayer;

	private void view(ClientGameTestContext context, String tp) {
		singleplayer.getServer().runCommand("tp @a " + tp);
		// Give the client time to receive the new position and render a few frames.
		context.waitTicks(15);
	}

	private void testGuiOpensAndGenerates(ClientGameTestContext context) {
		context.getInput().pressKey(KeyBindings.OPEN_SETTINGS);
		context.waitForScreen(CircleSettingsScreen.class);
		context.takeScreenshot("how_to_circle_gui");
		context.clickScreenButton("gui.how-to-circle.use_position");
		context.clickScreenButton("gui.how-to-circle.generate");
		BlockPos anchor = context.computeOnClient(client -> HologramManager.get().anchor());
		check(CENTRE.equals(anchor), "Use my position centres the hologram on the player, got " + anchor);
		context.setScreen(() -> null);
		context.waitTicks(2);
	}

	private void testCircle(ClientGameTestContext context, int diameter, FillMode fill, int centre, String screenshot) {
		context.runOnClient(client -> {
			HowToCircleConfig config = HowToCircleConfig.get();
			config.shapeType = ShapeType.CIRCLE;
			config.width = diameter;
			config.height = diameter;
			config.fillMode = fill;
			HologramManager.get().setAnchor(CENTRE);
		});
		view(context, "0.5 -45 -16 0 45");
		HologramGeometry geometry = context.computeOnClient(client -> HologramManager.get().geometry());
		check(geometry != null, "Hologram geometry exists for " + screenshot);
		ResolvedDimensions dims = context.computeOnClient(client -> HologramManager.get().dimensions());
		check(dims.width() == diameter && dims.height() == diameter, "Dimensions " + diameter);
		check(dims.centreWidth() == centre && dims.centreHeight() == centre, "Centre " + centre + "x" + centre);
		check(geometry.sectionCount > 0 && geometry.dimensionLineCount >= geometry.sectionCount, "Sections and dimension lines generated");
		context.takeScreenshot(screenshot);
	}

	private void testOval(ClientGameTestContext context, int width, int height, FillMode fill, String centre, String screenshot) {
		context.runOnClient(client -> {
			HowToCircleConfig config = HowToCircleConfig.get();
			config.shapeType = ShapeType.OVAL;
			config.width = width;
			config.height = height;
			config.fillMode = fill;
			HologramManager.get().setAnchor(CENTRE);
		});
		view(context, "0.5 -42 -20 0 45");
		ResolvedDimensions dims = context.computeOnClient(client -> HologramManager.get().dimensions());
		check(dims.width() == width && dims.height() == height, "Oval dimensions " + width + "x" + height);
		check(centre.equals(dims.centreLabel()), "Oval centre " + centre + ", got " + dims.centreLabel());
		HologramGeometry geometry = context.computeOnClient(client -> HologramManager.get().geometry());
		float[] b = geometry.bounds;
		check(Math.round(b[3] - b[0]) == width && Math.round(b[5] - b[2]) == height, "Oval spans exactly " + width + "x" + height + " blocks in the world");
		context.takeScreenshot(screenshot);
	}

	private void testForcedCentre(ClientGameTestContext context) {
		ResolvedDimensions forced = context.computeOnClient(client -> {
			HowToCircleConfig config = HowToCircleConfig.get();
			config.shapeType = ShapeType.CIRCLE;
			config.width = 16;
			config.centreSize = CentreSize.ONE_BY_ONE;
			return HologramManager.get().dimensions();
		});
		check(forced.width() == 17 && forced.adjusted(), "Forcing a 1x1 centre on 16 visibly adjusts it to 17");
		context.runOnClient(client -> HowToCircleConfig.get().centreSize = CentreSize.AUTO);
	}

	private void testLabelToggles(ClientGameTestContext context) {
		context.runOnClient(client -> {
			HowToCircleConfig config = HowToCircleConfig.get();
			config.shapeType = ShapeType.OVAL;
			config.width = 21;
			config.height = 13;
			config.fillMode = FillMode.FILLED;
			config.showDimensions = false;
			config.showPopups = false;
		});
		view(context, "0.5 -45 -16 0 45");
		context.takeScreenshot("labels_off");
		context.runOnClient(client -> HowToCircleConfig.get().showDimensions = true);
		context.waitTicks(10);
		context.takeScreenshot("dimension_labels_only");
		context.runOnClient(client -> {
			HowToCircleConfig.get().showPopups = true;
			HowToCircleConfig.get().popupsFacePlayer = false;
		});
		context.waitTicks(10);
		context.takeScreenshot("popups_fixed_orientation");
		context.runOnClient(client -> HowToCircleConfig.get().popupsFacePlayer = true);
	}

	private void testVerticalAndRotation(ClientGameTestContext context) {
		context.runOnClient(client -> {
			HowToCircleConfig config = HowToCircleConfig.get();
			config.plane = ShapePlacement.Plane.VERTICAL;
			config.verticalOffset = 7;
			config.fillMode = FillMode.OUTLINE;
		});
		view(context, "0.5 -52 -22 0 5");
		HologramGeometry geometry = context.computeOnClient(client -> HologramManager.get().geometry());
		check(geometry.axisV == 1, "Vertical plane puts the height on the Y axis");
		context.takeScreenshot("vertical_wall");
		context.runOnClient(client -> {
			HowToCircleConfig config = HowToCircleConfig.get();
			config.plane = ShapePlacement.Plane.HORIZONTAL;
			config.verticalOffset = 0;
			config.rotated = true;
		});
		HologramGeometry rotated = context.computeOnClient(client -> HologramManager.get().geometry());
		check(rotated.axisU == 2, "Rotating puts the width on the Z axis");
		context.runOnClient(client -> HowToCircleConfig.get().rotated = false);
	}

	private void testLargeShapes(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		for (FillMode fill : FillMode.values()) {
			context.runOnClient(client -> {
				HowToCircleConfig config = HowToCircleConfig.get();
				config.shapeType = ShapeType.CIRCLE;
				config.width = 100;
				config.height = 100;
				config.fillMode = fill;
			});
			view(context, "0.5 -5 -75 0 35");
			int rebuilds = context.computeOnClient(client -> HologramManager.get().rebuildCount());
			context.runOnClient(client -> RenderStats.reset());
			context.waitTicks(40);
			int rebuildsAfter = context.computeOnClient(client -> HologramManager.get().rebuildCount());
			check(rebuilds == rebuildsAfter, "Geometry is cached between frames (no rebuilds while nothing changes)");
			long frames = context.computeOnClient(client -> RenderStats.frames());
			double millis = context.computeOnClient(client -> RenderStats.averageMillisPerFrame());
			int sections = context.computeOnClient(client -> HologramManager.get().sections().size());
			System.out.printf("[how-to-circle] 100x100 %s: %d sections, %d frames, %.3f ms CPU per frame for hologram + labels%n",
					fill, sections, frames, millis);
			check(frames > 0, "Hologram render callback ran for 100x100 " + fill);
			check(millis < 25, "100x100 " + fill + " hologram stays cheap to render (" + millis + " ms/frame)");
			context.takeScreenshot("circle_100_" + fill.name().toLowerCase(java.util.Locale.ROOT));
		}
	}

	private void testClearAndRegenerate(ClientGameTestContext context) {
		context.runOnClient(client -> HologramManager.get().clear());
		check(context.computeOnClient(client -> HologramManager.get().geometry() == null), "Clear removes the hologram");
		context.runOnClient(client -> {
			HowToCircleConfig config = HowToCircleConfig.get();
			config.width = 15;
			config.height = 15;
			config.fillMode = FillMode.OUTLINE;
			HologramManager.get().setAnchor(CENTRE);
		});
		check(context.computeOnClient(client -> HologramManager.get().geometry() != null), "Regenerating after clear works");
	}

	private void testCentreSelectionKey(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		BlockPos expected = new BlockPos(3, -60, 5);
		context.runOnClient(client -> HologramManager.get().clear());
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
		BlockPos anchor = context.computeOnClient(client -> HologramManager.get().anchor());
		check(expected.equals(anchor), "Confirmed centre becomes the hologram centre, got " + anchor);

		// Cancel path
		context.getInput().pressKey(KeyBindings.SELECT_CENTRE);
		context.waitTicks(2);
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		context.waitTicks(3);
		check(!context.computeOnClient(client -> CentreSelectionHandler.get().isActive()), "Right-click cancels selection");
		check(expected.equals(context.computeOnClient(client -> HologramManager.get().anchor())), "Cancelling keeps the previous centre");
	}

	private void testGuiOpenCloseRepeatedly(ClientGameTestContext context) {
		for (int i = 0; i < 3; i++) {
			context.getInput().pressKey(KeyBindings.OPEN_SETTINGS);
			context.waitForScreen(CircleSettingsScreen.class);
			context.waitTicks(2);
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitForScreen(null);
		}
	}

	private static List<BlockState> snapshot(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			List<BlockState> states = new ArrayList<>();

			for (BlockPos pos : BlockPos.betweenClosed(-60, -64, -60, 60, -55, 60)) {
				states.add(level.getBlockState(pos));
			}

			return states;
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}

		System.out.println("[how-to-circle] PASS: " + message);
	}
}
