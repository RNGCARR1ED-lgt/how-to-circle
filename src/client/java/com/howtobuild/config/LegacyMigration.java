package com.howtobuild.config;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;

import com.howtobuild.HowToBuild;
import com.howtobuild.tools.ToolRegistry;

/**
 * Imports settings from How to Circle ({@code config/how-to-circle.json}), the previous name of this mod. The old file
 * is read only and left in place, so nothing is erased.
 */
final class LegacyMigration {
	private LegacyMigration() {
	}

	static void migrate(Path legacy, HowToBuildConfig config) {
		try (Reader reader = Files.newBufferedReader(legacy)) {
			JsonObject old = HowToBuildConfig.parse(reader);
			String shapeType = string(old, "shapeType", "CIRCLE");
			String fill = string(old, "fillMode", "OUTLINE");
			String centre = string(old, "centreSize", "AUTO");
			String plane = string(old, "plane", "HORIZONTAL").equals("VERTICAL") ? "WALL" : "FLOOR";
			int width = integer(old, "width", 15);
			int height = integer(old, "height", 15);

			config.tool = shapeType.equals("OVAL") ? "oval" : "circle";
			config.setSetting(ToolRegistry.get("circle"), "size", Integer.toString(width));
			config.setSetting(ToolRegistry.get("circle"), "fill", fill);
			config.setSetting(ToolRegistry.get("circle"), "centre_size", centre);
			config.setSetting(ToolRegistry.get("circle"), "plane", plane);
			config.setSetting(ToolRegistry.get("oval"), "width", Integer.toString(width));
			config.setSetting(ToolRegistry.get("oval"), "length", Integer.toString(height));
			config.setSetting(ToolRegistry.get("oval"), "fill", fill);
			config.setSetting(ToolRegistry.get("oval"), "centre_size", centre);
			config.setSetting(ToolRegistry.get("oval"), "plane", plane);
			config.rotation = bool(old, "rotated", false) ? 1 : 0;
			config.alignX = bool(old, "alignPositiveU", true);
			config.alignZ = bool(old, "alignPositiveV", true);
			config.offsetY = integer(old, "verticalOffset", 0);
			config.lockToBlockCentre = bool(old, "lockToBlockCentre", true);
			config.hologram.visible = bool(old, "showHologram", true);
			config.hologram.color = integer(old, "hologramColor", config.hologram.color);
			config.hologram.opacity = (float) number(old, "hologramOpacity", config.hologram.opacity);
			config.hologram.seeThroughBlocks = bool(old, "seeThroughBlocks", false);
			config.labels.showDimensions = bool(old, "showDimensions", true);
			config.labels.showPopups = bool(old, "showPopups", true);
			config.labels.billboard = bool(old, "popupsFacePlayer", true);
			config.labels.textSize = (float) number(old, "textSize", 1);
			config.labels.offset = (float) number(old, "labelOffset", 1.25);
			config.labels.opacity = (float) number(old, "labelOpacity", 0.95);
			config.labels.textColor = integer(old, "labelColor", config.labels.textColor);
			config.labels.backgroundColor = integer(old, "labelBackgroundColor", config.labels.backgroundColor);
			config.labels.labelDistance = (float) number(old, "labelDistance", 64);
			config.labels.popupDistance = (float) number(old, "popupDistance", 40);

			if (string(old, "labelFormat", "LONG_BY_SHORT").equals("WIDTH_BY_HEIGHT")) {
				config.labels.order = com.howtobuild.dimensions.LabelFormat.WIDTH_BY_HEIGHT;
			}

			config.sanitize();
			HowToBuild.LOGGER.info("Imported How to Circle settings from {} (the old file was kept)", legacy);
		} catch (Exception e) {
			HowToBuild.LOGGER.warn("Could not import How to Circle settings from {}", legacy, e);
		}
	}

	private static String string(JsonObject o, String key, String fallback) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
	}

	private static int integer(JsonObject o, String key, int fallback) {
		try {
			return o.has(key) ? o.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static double number(JsonObject o, String key, double fallback) {
		try {
			return o.has(key) ? o.get(key).getAsDouble() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static boolean bool(JsonObject o, String key, boolean fallback) {
		try {
			return o.has(key) ? o.get(key).getAsBoolean() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}
}
