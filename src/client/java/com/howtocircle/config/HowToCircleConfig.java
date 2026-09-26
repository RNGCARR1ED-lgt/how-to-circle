package com.howtocircle.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

import com.howtocircle.HowToCircle;
import com.howtocircle.dimensions.LabelFormat;
import com.howtocircle.geometry.CentreSize;
import com.howtocircle.geometry.FillMode;
import com.howtocircle.geometry.ResolvedDimensions;
import com.howtocircle.geometry.ShapePlacement;
import com.howtocircle.geometry.ShapeType;

/**
 * All user settings, persisted as JSON in {@code config/how-to-circle.json}.
 *
 * <p>Fields are public for Gson; always call {@link #sanitize()} after changing them from untrusted input.
 */
public final class HowToCircleConfig {
	public static final int[] COLOR_PRESETS = {
			0x33D6FF, // hologram cyan
			0x4D7CFF, // blue
			0x9B6BFF, // purple
			0xFF5CC8, // magenta
			0xFF5454, // red
			0xFF9F3A, // orange
			0xFFE14D, // yellow
			0x7CFF5C, // lime
			0x2EC27E, // green
			0xFFFFFF // white
	};

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static HowToCircleConfig instance = new HowToCircleConfig();

	// Circle settings
	public ShapeType shapeType = ShapeType.CIRCLE;
	public int width = 15;
	public int height = 15;
	public FillMode fillMode = FillMode.OUTLINE;
	public CentreSize centreSize = CentreSize.AUTO;

	// Position settings
	public ShapePlacement.Plane plane = ShapePlacement.Plane.HORIZONTAL;
	public boolean rotated = false;
	public boolean alignPositiveU = true;
	public boolean alignPositiveV = true;
	public int verticalOffset = 0;
	public boolean lockToBlockCentre = true;

	// Hologram appearance
	public boolean showHologram = true;
	public int hologramColor = COLOR_PRESETS[0];
	public float hologramOpacity = 0.28F;
	public boolean showBlockGrid = true;
	public boolean seeThroughBlocks = false;

	// Dimension settings
	public boolean showDimensions = true;
	public boolean showPopups = true;
	public boolean popupsFacePlayer = true;
	public LabelFormat labelFormat = LabelFormat.LONG_BY_SHORT;
	public float textSize = 1.0F;
	public float labelOffset = 1.25F;
	public float labelOpacity = 0.95F;
	public int labelColor = 0xFFFFFF;
	public int labelBackgroundColor = 0x0B2530;
	public float labelDistance = 48F;
	public float popupDistance = 24F;

	public static HowToCircleConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(HowToCircle.MOD_ID + ".json");
	}

	public static void load() {
		Path path = path();

		if (!Files.exists(path)) {
			instance = new HowToCircleConfig();
			save();
			return;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			HowToCircleConfig loaded = GSON.fromJson(reader, HowToCircleConfig.class);
			instance = loaded != null ? loaded : new HowToCircleConfig();
		} catch (IOException | JsonParseException e) {
			HowToCircle.LOGGER.warn("Could not read {}, using defaults", path, e);
			instance = new HowToCircleConfig();
		}

		instance.sanitize();
	}

	public static void save() {
		instance.sanitize();

		try {
			Files.createDirectories(path().getParent());

			try (Writer writer = Files.newBufferedWriter(path())) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			HowToCircle.LOGGER.warn("Could not save {}", path(), e);
		}
	}

	/** Clamps every value into its valid range and replaces missing enum values (e.g. from an old or edited file). */
	public void sanitize() {
		if (shapeType == null) shapeType = ShapeType.CIRCLE;
		if (fillMode == null) fillMode = FillMode.OUTLINE;
		if (centreSize == null) centreSize = CentreSize.AUTO;
		if (plane == null) plane = ShapePlacement.Plane.HORIZONTAL;
		if (labelFormat == null) labelFormat = LabelFormat.LONG_BY_SHORT;

		width = clamp(width, ResolvedDimensions.MIN_SIZE, ResolvedDimensions.MAX_SIZE);
		height = clamp(height, ResolvedDimensions.MIN_SIZE, ResolvedDimensions.MAX_SIZE);
		verticalOffset = clamp(verticalOffset, -256, 256);
		hologramColor &= 0xFFFFFF;
		labelColor &= 0xFFFFFF;
		labelBackgroundColor &= 0xFFFFFF;
		hologramOpacity = clamp(hologramOpacity, 0.05F, 0.9F);
		textSize = clamp(textSize, 0.5F, 3.0F);
		labelOffset = clamp(labelOffset, 0.25F, 4.0F);
		labelOpacity = clamp(labelOpacity, 0.1F, 1.0F);
		labelDistance = clamp(labelDistance, 8F, 256F);
		popupDistance = clamp(popupDistance, 4F, 256F);
	}

	public ResolvedDimensions resolveDimensions() {
		return ResolvedDimensions.resolve(shapeType, width, height, centreSize);
	}

	public ShapePlacement placement() {
		return new ShapePlacement(plane, rotated, alignPositiveU, alignPositiveV, verticalOffset);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clamp(float value, float min, float max) {
		if (Float.isNaN(value)) return min;
		return Math.max(min, Math.min(max, value));
	}
}
