package com.howtobuild;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants. The mod is client-only; its entrypoint is {@code com.howtobuild.client.HowToBuildClient}.
 */
public final class HowToBuild {
	public static final String MOD_ID = "howtobuild";
	public static final String NAME = "How to Build";
	/** Mod id used by How to Circle, the previous name of this mod (for config migration). */
	public static final String LEGACY_MOD_ID = "how-to-circle";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private HowToBuild() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
