package com.howtobuild.render;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import com.howtobuild.input.CentreSelectionHandler;

/**
 * HUD overlay shown while centre selection mode is active: what is targeted and how to confirm or cancel.
 */
public final class SelectionHud {
	private SelectionHud() {
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		CentreSelectionHandler selection = CentreSelectionHandler.get();

		if (!selection.isActive()) return;

		Font font = Minecraft.getInstance().font;
		String title = Component.translatable("hud.how-to-circle.selecting").getString();
		String status = selection.statusLine().getString();
		String help = Component.translatable("hud.how-to-circle.help").getString();
		int width = Math.max(font.width(title), Math.max(font.width(status), font.width(help))) + 16;
		int centreX = graphics.guiWidth() / 2;
		int top = 12;
		int left = centreX - width / 2;

		graphics.fill(left, top, left + width, top + 40, 0xB0101C24);
		graphics.fill(left, top, left + width, top + 1, 0xFF33D6FF);
		graphics.centeredText(font, title, centreX, top + 4, 0xFF33D6FF);
		graphics.centeredText(font, status, centreX, top + 16, 0xFFFFFFFF);
		graphics.centeredText(font, help, centreX, top + 28, 0xFFB8C7CC);
	}
}
