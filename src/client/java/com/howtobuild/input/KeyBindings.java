package com.howtobuild.input;

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.howtobuild.HowToBuild;

/**
 * Configurable key bindings. They appear under "How to Circle" in Options → Controls → Key Binds.
 */
public final class KeyBindings {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(HowToBuild.id("main"));

	public static final KeyMapping OPEN_SETTINGS = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.how-to-circle.open_settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));

	public static final KeyMapping SELECT_CENTRE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.how-to-circle.select_centre", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY));

	public static final KeyMapping TOGGLE_HOLOGRAM = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.how-to-circle.toggle_hologram", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

	private KeyBindings() {
	}

	/** Forces class initialisation (and therefore registration) during client start-up. */
	public static void init() {
	}
}
