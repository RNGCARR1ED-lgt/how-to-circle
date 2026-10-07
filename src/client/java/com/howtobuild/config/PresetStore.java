package com.howtobuild.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonObject;

import com.howtobuild.HowToBuild;

/**
 * Named presets ({@code config/howtobuild/presets/<name>.json}). A preset stores the tool, all tool settings,
 * materials, details, labels, mirror and build settings.
 */
public final class PresetStore {
	private PresetStore() {
	}

	public static Path directory() {
		return HowToBuildConfig.path().getParent().resolve(HowToBuild.MOD_ID).resolve("presets");
	}

	public static String fileName(String name) {
		String safe = name.trim().replaceAll("[^A-Za-z0-9 _-]", "_");
		return (safe.isEmpty() ? "preset" : safe) + ".json";
	}

	public static List<String> list() {
		List<String> names = new ArrayList<>();

		if (!Files.isDirectory(directory())) return names;

		try (Stream<Path> files = Files.list(directory())) {
			files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
				String file = p.getFileName().toString();
				names.add(file.substring(0, file.length() - 5));
			});
		} catch (IOException e) {
			HowToBuild.LOGGER.warn("Could not list presets", e);
		}

		return names;
	}

	public static boolean save(String name, HowToBuildConfig config) {
		try {
			Files.createDirectories(directory());

			try (Writer writer = Files.newBufferedWriter(directory().resolve(fileName(name)))) {
				HowToBuildConfig.gson().toJson(config.toPresetJson(), writer);
			}

			return true;
		} catch (IOException e) {
			HowToBuild.LOGGER.warn("Could not save preset {}", name, e);
			return false;
		}
	}

	public static boolean load(String name) {
		Path path = directory().resolve(fileName(name));

		try (Reader reader = Files.newBufferedReader(path)) {
			JsonObject json = HowToBuildConfig.parse(reader);
			HowToBuildConfig.get().applyPreset(json);
			HowToBuildConfig.save();
			return true;
		} catch (Exception e) {
			HowToBuild.LOGGER.warn("Could not load preset {}", name, e);
			return false;
		}
	}

	public static boolean delete(String name) {
		try {
			return Files.deleteIfExists(directory().resolve(fileName(name)));
		} catch (IOException e) {
			return false;
		}
	}
}
