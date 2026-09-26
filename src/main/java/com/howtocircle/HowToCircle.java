package com.howtocircle;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants. The mod is client-only; its entrypoint is {@code com.howtocircle.client.HowToCircleClient}.
 */
public final class HowToCircle {
	public static final String MOD_ID = "how-to-circle";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private HowToCircle() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
