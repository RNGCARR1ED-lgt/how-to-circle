package com.howtobuild.input;

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.howtobuild.HowToBuild;

/**
 * Configurable key bindings. They appear under "How to Build" in Options → Controls → Key Binds.
 */
public final class KeyBindings {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(HowToBuild.id("main"));

	public static final KeyMapping OPEN_SETTINGS = register("open_settings", GLFW.GLFW_KEY_H);
	public static final KeyMapping SELECT_CENTRE = register("select_centre", GLFW.GLFW_KEY_J);
	public static final KeyMapping SELECT_MIRROR_CENTRE = register("select_mirror_centre", GLFW.GLFW_KEY_UNKNOWN);
	public static final KeyMapping TOGGLE_HOLOGRAM = register("toggle_hologram", GLFW.GLFW_KEY_UNKNOWN);
	public static final KeyMapping OPEN_BUILD = register("open_build", GLFW.GLFW_KEY_UNKNOWN);
	public static final KeyMapping PAUSE_BUILD = register("pause_build", GLFW.GLFW_KEY_UNKNOWN);

	private KeyBindings() {
	}

	private static KeyMapping register(String name, int key) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + HowToBuild.MOD_ID + "." + name, InputConstants.Type.KEYSYM, key, CATEGORY));
	}

	/** Forces class initialisation (and therefore registration) during client start-up. */
	public static void init() {
	}
}
