package com.howtobuild.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import com.howtobuild.HowToBuild;
import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailPreset;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.details.MaterialPattern;
import com.howtobuild.details.MaterialVariation;
import com.howtobuild.details.SlabMode;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.ShapeKind;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.ToolRegistry;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.capability.Detailable;

/**
 * Everything the player configures, persisted to {@code config/howtobuild.json}. World-specific state (the hologram
 * centre) is deliberately not stored here, so a structure never appears in an unrelated world.
 */
public final class HowToBuildConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static HowToBuildConfig instance = new HowToBuildConfig();

	// GUI state
	public String tool = "circle";
	public String tab = "GEOMETRY";
	public boolean advanced = false;
	public List<String> favorites = new ArrayList<>(List.of("circle", "spiral"));
	/** GUI sections the player collapsed (by section key). */
	public List<String> collapsedSections = new ArrayList<>();

	// Per-tool parameter values (raw strings, sanitised against each tool's parameter definitions).
	public Map<String, Map<String, String>> toolSettings = new LinkedHashMap<>();

	// Shared generation settings
	public List<ShapeKind> materialTypes = new ArrayList<>(List.of(ShapeKind.BLOCKS));
	public SlabMode slabMode = SlabMode.AUTOMATIC;
	public int rotation = 0;
	public boolean alignX = true;
	public boolean alignY = true;
	public boolean alignZ = true;
	public int offsetX = 0;
	public int offsetY = 0;
	public int offsetZ = 0;
	public boolean lockToBlockCentre = true;

	// Details
	public DetailPreset detailPreset = DetailPreset.NONE;
	public List<DetailFeature> customDetails = new ArrayList<>();
	public int detailInterval = 4;
	public MaterialPattern pattern = MaterialPattern.NONE;
	public int patternSize = 1;
	public MaterialVariation variation = MaterialVariation.NONE;
	public long seed = 1;

	public Map<MaterialRole, MaterialSlot> materials = defaultMaterials();
	public LabelSettings labels = new LabelSettings();
	public MirrorConfig mirror = new MirrorConfig();
	public BuildConfig build = new BuildConfig();
	public HologramConfig hologram = new HologramConfig();

	public static HowToBuildConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(HowToBuild.MOD_ID + ".json");
	}

	public static Path legacyPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(HowToBuild.LEGACY_MOD_ID + ".json");
	}

	public static Map<MaterialRole, MaterialSlot> defaultMaterials() {
		Map<MaterialRole, MaterialSlot> m = new EnumMap<>(MaterialRole.class);
		m.put(MaterialRole.PRIMARY, new MaterialSlot("minecraft:stone_bricks", "minecraft:stone_brick_slab", "minecraft:stone_brick_stairs"));
		m.put(MaterialRole.TRIM, new MaterialSlot("minecraft:polished_andesite", "minecraft:polished_andesite_slab", "minecraft:polished_andesite_stairs"));
		m.put(MaterialRole.ACCENT, new MaterialSlot("minecraft:chiseled_stone_bricks", "minecraft:smooth_stone_slab", "minecraft:stone_brick_stairs"));
		m.put(MaterialRole.STEP, new MaterialSlot("minecraft:oak_planks", "minecraft:oak_slab", "minecraft:oak_stairs"));
		m.put(MaterialRole.SUPPORT, new MaterialSlot("minecraft:cobblestone", "minecraft:cobblestone_slab", "minecraft:cobblestone_stairs"));
		m.put(MaterialRole.CAP, new MaterialSlot("minecraft:deepslate_tiles", "minecraft:deepslate_tile_slab", "minecraft:deepslate_tile_stairs"));
		m.put(MaterialRole.INNER, new MaterialSlot("minecraft:smooth_stone", "minecraft:smooth_stone_slab", "minecraft:stone_stairs"));
		m.put(MaterialRole.RAIL, new MaterialSlot("minecraft:dark_oak_planks", "minecraft:dark_oak_slab", "minecraft:dark_oak_stairs"));
		m.put(MaterialRole.FLOOR, new MaterialSlot("minecraft:spruce_planks", "minecraft:spruce_slab", "minecraft:spruce_stairs"));
		return m;
	}

	public static void load() {
		Path path = path();

		if (!Files.exists(path)) {
			instance = new HowToBuildConfig();

			if (Files.exists(legacyPath())) {
				LegacyMigration.migrate(legacyPath(), instance);
			}

			save();
			return;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			HowToBuildConfig loaded = GSON.fromJson(reader, HowToBuildConfig.class);
			instance = loaded != null ? loaded : new HowToBuildConfig();
		} catch (IOException | JsonParseException e) {
			HowToBuild.LOGGER.warn("Could not read {}, using defaults (the file is left untouched)", path, e);
			instance = new HowToBuildConfig();
			return;
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
			HowToBuild.LOGGER.warn("Could not save {}", path(), e);
		}
	}

	public static Gson gson() {
		return GSON;
	}

	public void sanitize() {
		if (ToolRegistry.byId(tool).isEmpty()) tool = "circle";
		if (tab == null) tab = "GEOMETRY";
		if (favorites == null) favorites = new ArrayList<>();
		if (collapsedSections == null) collapsedSections = new ArrayList<>();
		favorites.removeIf(id -> id == null || ToolRegistry.byId(id).isEmpty());
		if (toolSettings == null) toolSettings = new LinkedHashMap<>();

		migrateSpiralRadius();

		for (BuildTool t : ToolRegistry.all()) {
			toolSettings.put(t.id(), new LinkedHashMap<>(settings(t).asMap()));
		}

		if (materialTypes == null) materialTypes = new ArrayList<>();
		materialTypes.removeIf(java.util.Objects::isNull);
		materialTypes = new ArrayList<>(EnumSet.copyOf(materialTypes.isEmpty() ? List.of(ShapeKind.BLOCKS) : materialTypes));
		if (slabMode == null) slabMode = SlabMode.AUTOMATIC;
		rotation &= 3;
		offsetX = clamp(offsetX, -512, 512);
		offsetY = clamp(offsetY, -512, 512);
		offsetZ = clamp(offsetZ, -512, 512);
		if (detailPreset == null) detailPreset = DetailPreset.NONE;
		if (customDetails == null) customDetails = new ArrayList<>();
		customDetails.removeIf(java.util.Objects::isNull);
		detailInterval = clamp(detailInterval, 2, 64);
		if (pattern == null) pattern = MaterialPattern.NONE;
		patternSize = clamp(patternSize, 1, 32);
		if (variation == null) variation = MaterialVariation.NONE;

		Map<MaterialRole, MaterialSlot> defaults = defaultMaterials();
		if (materials == null) materials = new EnumMap<>(MaterialRole.class);
		materials = new EnumMap<>(materials.isEmpty() ? defaults : materials);

		for (MaterialRole role : MaterialRole.values()) {
			MaterialSlot slot = materials.get(role);

			if (slot == null) {
				materials.put(role, defaults.get(role));
			} else {
				if (slot.block == null) slot.block = defaults.get(role).block;
				if (slot.slab == null) slot.slab = defaults.get(role).slab;
				if (slot.stairs == null) slot.stairs = defaults.get(role).stairs;
				if (slot.variants == null) slot.variants = new ArrayList<>();
			}
		}

		if (labels == null) labels = new LabelSettings();
		labels.sanitize();
		if (mirror == null) mirror = new MirrorConfig();
		mirror.sanitize();
		if (build == null) build = new BuildConfig();
		build.sanitize();
		if (hologram == null) hologram = new HologramConfig();
		hologram.sanitize();
	}

	/**
	 * Earlier versions sized the spiral by an outer radius r (always 2r + 1 blocks across, or 2r with a forced 2×2
	 * centre). The spiral is now sized by its exact diameter; keep existing staircases the same size.
	 */
	private void migrateSpiralRadius() {
		Map<String, String> spiral = toolSettings.get("spiral");

		if (spiral == null || !spiral.containsKey("outer_radius") || spiral.containsKey("diameter")) return;

		try {
			int r = Integer.parseInt(spiral.get("outer_radius").trim());
			boolean twoByTwo = "TWO_BY_TWO".equals(spiral.get("centre_size"));
			spiral.put("diameter", Integer.toString(twoByTwo ? 2 * r : 2 * r + 1));
		} catch (NumberFormatException e) {
			// Leave the default diameter.
		}

		spiral.remove("outer_radius");
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	public BuildTool activeTool() {
		return ToolRegistry.get(tool);
	}

	public ToolSettings settings(BuildTool t) {
		return ToolSettings.of(t.parameters(), toolSettings == null ? Map.of() : toolSettings.getOrDefault(t.id(), Map.of()));
	}

	public void setSetting(BuildTool t, String id, String value) {
		ToolSettings updated = settings(t).with(id, value);
		toolSettings.put(t.id(), new LinkedHashMap<>(updated.asMap()));
	}

	public EnumSet<ShapeKind> materialTypeSet() {
		return materialTypes.isEmpty() ? EnumSet.of(ShapeKind.BLOCKS) : EnumSet.copyOf(materialTypes);
	}

	/** The detail features in effect for a tool: the preset's features, or the custom selection. */
	public EnumSet<DetailFeature> detailFeatures(BuildTool t) {
		if (!(t instanceof Detailable detailable)) return EnumSet.noneOf(DetailFeature.class);

		EnumSet<DetailFeature> set = detailPreset == DetailPreset.CUSTOM
				? (customDetails.isEmpty() ? EnumSet.noneOf(DetailFeature.class) : EnumSet.copyOf(customDetails))
				: copy(detailable.presetDetails(detailPreset));
		set.retainAll(detailable.supportedDetails());
		return set;
	}

	private static EnumSet<DetailFeature> copy(java.util.Set<DetailFeature> features) {
		return features.isEmpty() ? EnumSet.noneOf(DetailFeature.class) : EnumSet.copyOf(features);
	}

	public GenerationContext generationContext(BuildTool t) {
		DetailSettings details = new DetailSettings(detailFeatures(t), detailInterval, pattern, patternSize, variation, seed);
		return new GenerationContext(materialTypeSet(), details, slabMode, alignX, alignY, alignZ, rotation);
	}

	/** Serialises the parts of the config that make up a preset. */
	public JsonObject toPresetJson() {
		JsonObject json = GSON.toJsonTree(this).getAsJsonObject();
		json.remove("tab");
		json.remove("advanced");
		json.remove("favorites");
		json.remove("collapsedSections");
		return json;
	}

	/** Applies a preset over this config (GUI state and favourites are kept). */
	public void applyPreset(JsonObject preset) {
		JsonObject merged = GSON.toJsonTree(this).getAsJsonObject();

		for (Map.Entry<String, JsonElement> entry : preset.entrySet()) {
			if (!entry.getKey().equals("tab") && !entry.getKey().equals("advanced") && !entry.getKey().equals("favorites")) {
				merged.add(entry.getKey(), entry.getValue());
			}
		}

		HowToBuildConfig loaded = GSON.fromJson(merged, HowToBuildConfig.class);
		loaded.tab = tab;
		loaded.advanced = advanced;
		loaded.favorites = favorites;
		loaded.sanitize();
		instance = loaded;
	}

	static JsonObject parse(Reader reader) {
		return JsonParser.parseReader(reader).getAsJsonObject();
	}
}
