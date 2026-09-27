package com.howtocircle.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import com.howtocircle.HowToCircle;
import com.howtocircle.config.HowToCircleConfig;
import com.howtocircle.gui.CircleSettingsScreen;
import com.howtocircle.input.CentreSelectionHandler;
import com.howtocircle.input.KeyBindings;
import com.howtocircle.render.DimensionLabelRenderer;
import com.howtocircle.render.HologramRenderTypes;
import com.howtocircle.render.HologramRenderer;
import com.howtocircle.render.RenderStats;
import com.howtocircle.render.SelectionHud;

/**
 * Client entrypoint: loads the config, registers key bindings, rendering and input handlers.
 */
public final class HowToCircleClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HowToCircleConfig.load();
		KeyBindings.init();
		HologramRenderTypes.init();
		CentreSelectionHandler.get().register();

		ClientTickEvents.START_CLIENT_TICK.register(client -> CentreSelectionHandler.get().onStartTick(client));
		ClientTickEvents.END_CLIENT_TICK.register(HowToCircleClient::handleKeys);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			HologramManager.get().onDisconnect();
			CentreSelectionHandler.get().cancelSilently();
		});

		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			long start = System.nanoTime();
			HologramRenderer.render(context);
			DimensionLabelRenderer.render(context);
			RenderStats.record(System.nanoTime() - start);
		});
		HudElementRegistry.addLast(HowToCircle.id("selection_hud"), SelectionHud::extract);

		HowToCircle.LOGGER.info("How to Circle loaded");
	}

	private static void handleKeys(Minecraft client) {
		while (KeyBindings.OPEN_SETTINGS.consumeClick()) {
			if (client.player != null && client.gui.screen() == null) {
				client.gui.setScreen(new CircleSettingsScreen());
			}
		}

		while (KeyBindings.SELECT_CENTRE.consumeClick()) {
			CentreSelectionHandler.get().toggle(false);
		}

		while (KeyBindings.TOGGLE_HOLOGRAM.consumeClick()) {
			HowToCircleConfig config = HowToCircleConfig.get();
			config.showHologram = !config.showHologram;
			HowToCircleConfig.save();

			if (client.player != null) {
				client.player.sendSystemMessage(Component.translatable(config.showHologram
						? "message.how-to-circle.hologram_shown" : "message.how-to-circle.hologram_hidden"));
			}
		}
	}
}
