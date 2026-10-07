package com.howtobuild.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import com.howtobuild.HowToBuild;
import com.howtobuild.building.CommandExecutor;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.gui.CommandBuildScreen;
import com.howtobuild.gui.HowToBuildScreen;
import com.howtobuild.input.CentreSelectionHandler;
import com.howtobuild.input.KeyBindings;
import com.howtobuild.render.DimensionLabelRenderer;
import com.howtobuild.render.HologramRenderTypes;
import com.howtobuild.render.HologramRenderer;
import com.howtobuild.render.RenderStats;
import com.howtobuild.render.SelectionHud;

/**
 * Client entrypoint: loads (and if needed migrates) the config, registers key bindings, the background generation tick,
 * rendering, HUD, centre selection and the command executor.
 */
public final class HowToBuildClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HowToBuildConfig.load();
		KeyBindings.init();
		HologramRenderTypes.init();
		CentreSelectionHandler.get().register();
		CommandExecutor.get().register();

		ClientTickEvents.START_CLIENT_TICK.register(client -> CentreSelectionHandler.get().onStartTick(client));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			handleKeys(client);
			BuildSession.get().tick();
			CommandExecutor.get().tick(client);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			BuildSession.get().onDisconnect();
			CentreSelectionHandler.get().cancelSilently();
			CommandExecutor.get().onDisconnect();
		});

		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			long start = System.nanoTime();
			HologramRenderer.render(context);
			DimensionLabelRenderer.render(context);
			RenderStats.record(System.nanoTime() - start);
		});
		HudElementRegistry.addLast(HowToBuild.id("hud"), SelectionHud::extract);

		HowToBuild.LOGGER.info("How to Build loaded");
	}

	private static void handleKeys(Minecraft client) {
		while (KeyBindings.OPEN_SETTINGS.consumeClick()) {
			if (client.player != null && client.gui.screen() == null) {
				client.gui.setScreen(new HowToBuildScreen());
			}
		}

		while (KeyBindings.SELECT_CENTRE.consumeClick()) {
			CentreSelectionHandler.get().toggle(CentreSelectionHandler.Target.SHAPE, false);
		}

		while (KeyBindings.SELECT_MIRROR_CENTRE.consumeClick()) {
			CentreSelectionHandler.get().toggle(CentreSelectionHandler.Target.MIRROR, false);
		}

		while (KeyBindings.OPEN_BUILD.consumeClick()) {
			if (client.player != null && client.gui.screen() == null) {
				client.gui.setScreen(new CommandBuildScreen(null));
			}
		}

		while (KeyBindings.PAUSE_BUILD.consumeClick()) {
			CommandExecutor.get().togglePause();
		}

		while (KeyBindings.TOGGLE_HOLOGRAM.consumeClick()) {
			HowToBuildConfig config = HowToBuildConfig.get();
			config.hologram.visible = !config.hologram.visible;
			HowToBuildConfig.save();

			if (client.player != null) {
				client.player.sendSystemMessage(Component.translatable(config.hologram.visible
						? "message.howtobuild.hologram_shown" : "message.howtobuild.hologram_hidden"));
			}
		}
	}
}
