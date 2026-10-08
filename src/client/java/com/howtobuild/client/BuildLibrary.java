package com.howtobuild.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.howtobuild.HowToBuild;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.geometry.Box;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.WorldTransform;
import com.howtobuild.saves.BuildFile;
import com.howtobuild.saves.HwbCodec;
import com.howtobuild.saves.SavedBuilds;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.ToolRegistry;

/**
 * Saving, listing, loading and comparing saved builds ({@code .minecraft/howtobuild/builds/*.hwb}). Reading and writing
 * files happens on a background thread; results come back on the client thread. Saves are atomic, so a failed save
 * never damages an existing build.
 */
public final class BuildLibrary {
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "How to Build saves");
		thread.setDaemon(true);
		return thread;
	});
	private static final Map<String, Info> INFO = new ConcurrentHashMap<>();

	/** What a save captures. */
	public enum Source {
		/** The preview as shown, including mirrored copies. */
		AS_SHOWN,
		/** The generated shape only (no mirrored copies). */
		GENERATED,
		/** Exactly what Build would place (mirror copies only when the mirror builds them). */
		FINAL,
		/** The real world blocks inside the preview's bounds. */
		WORLD_REGION
	}

	/** Summary of a saved build for the list. */
	public record Info(String name, @Nullable BuildFile file, long fileSize, @Nullable String error) {
		public boolean ok() {
			return file != null;
		}
	}

	/** World comparison of the current preview. */
	public record Comparison(int correct, int missing, int incorrect, int extra) {
	}

	private BuildLibrary() {
	}

	public static void init() {
		SavedBuilds.setDirectory(directory());
	}

	public static Path directory() {
		return FabricLoader.getInstance().getGameDir().resolve(HowToBuild.MOD_ID).resolve("builds");
	}

	// ----------------------------------------------------------------- listing

	/** The saved builds, with metadata loaded in the background (entries appear as they are read). */
	public static List<String> names() {
		return SavedBuilds.list();
	}

	public static @Nullable Info info(String name) {
		return INFO.get(name);
	}

	/** Reads the metadata of every saved build in the background, then calls {@code done} on the client thread. */
	public static void refresh(Runnable done) {
		CompletableFuture.runAsync(() -> {
			List<String> names = SavedBuilds.list();
			INFO.keySet().retainAll(names);

			for (String name : names) {
				SavedBuilds.invalidate(name);
				long size = SavedBuilds.path(name).map(p -> {
					try {
						return Files.size(p);
					} catch (IOException e) {
						return -1L;
					}
				}).orElse(-1L);
				BuildFile file = SavedBuilds.get(name).orElse(null);
				INFO.put(name, new Info(name, file, size, file == null ? SavedBuilds.error(name).orElse("unreadable") : null));
			}
		}, IO).whenComplete((v, e) -> Minecraft.getInstance().execute(done));
	}

	// ----------------------------------------------------------------- saving

	/**
	 * Captures the current preview (exact block states) or, with {@code procedural}, the current tool and all its
	 * settings as a preset that regenerates. Returns null with nothing to save.
	 */
	public static @Nullable BuildFile capture(String name, String description, Source source, boolean procedural) {
		HowToBuildConfig config = HowToBuildConfig.get();
		BuildTool tool = config.activeTool();
		BuildSession session = BuildSession.get();
		BuildSession.Resolved r = session.resolved();
		int[] offset = {config.offsetX, config.offsetY, config.offsetZ};

		if (procedural) {
			JsonObject preset = config.toPresetJson();
			return new BuildFile(name, config.author, description, tool.id(), System.currentTimeMillis(), BuildFile.Origin.CENTER,
					r == null ? List.of() : r.result().centreCells(), offset, config.random.seed, preset.toString(), true, List.of(), new int[0]);
		}

		if (r == null) return null;

		List<String> palette = new ArrayList<>();
		Map<String, Integer> index = new java.util.HashMap<>();
		List<int[]> blocks = new ArrayList<>();

		if (source == Source.WORLD_REGION) {
			ClientLevel level = Minecraft.getInstance().level;
			Box b = r.result().bounds();

			if (level == null || b == null) return null;

			WorldTransform t = r.transform();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

			for (int y = b.minY(); y <= b.maxY(); y++) {
				for (int z = b.minZ(); z <= b.maxZ(); z++) {
					for (int x = b.minX(); x <= b.maxX(); x++) {
						if (blocks.size() >= HwbCodec.MAX_BLOCKS) break;

						BlockState state = level.getBlockState(pos.set(t.x(x), t.y(y), t.z(z)));

						if (!state.isAir()) blocks.add(new int[] {x, y, z, paletteIndex(palette, index, BlockStateParser.serialize(state))});
					}
				}
			}
		} else {
			List<Placement> placements = r.result().placements();

			for (int i = 0; i < placements.size(); i++) {
				Placement p = placements.get(i);

				if (source == Source.GENERATED && p.mirrored()) continue;
				if (source == Source.FINAL && !r.buildable()[i]) continue;

				blocks.add(new int[] {p.x(), p.y(), p.z(), paletteIndex(palette, index, BlockStateParser.serialize(r.states()[i]))});
			}
		}

		int[] flat = new int[blocks.size() * 4];

		for (int i = 0; i < blocks.size(); i++) {
			System.arraycopy(blocks.get(i), 0, flat, i * 4, 4);
		}

		JsonObject settings = new JsonObject();
		settings.addProperty("tool", tool.id());
		settings.add("settings", HowToBuildConfig.gson().toJsonTree(config.settings(tool).asMap()));
		settings.addProperty("source", source.name());
		return new BuildFile(name, config.author, description, source == Source.WORLD_REGION ? "capture" : tool.id(), System.currentTimeMillis(),
				BuildFile.Origin.CENTER, r.result().centreCells(), offset, config.random.seed, settings.toString(), false, palette, flat);
	}

	/**
	 * Re-expresses a build relative to another origin: with the minimum corner as origin, the build's lowest north-west
	 * corner is placed on the selected block. Centre cells move with the blocks, so flips stay exact.
	 */
	public static BuildFile withOrigin(BuildFile file, BuildFile.Origin origin) {
		if (origin != BuildFile.Origin.MIN_CORNER || file.blockCount() == 0) return file;

		int[] b = file.bounds();
		int[] blocks = file.blocks().clone();

		for (int i = 0; i < blocks.length; i += 4) {
			blocks[i] -= b[0];
			blocks[i + 1] -= b[1];
			blocks[i + 2] -= b[2];
		}

		List<int[]> centres = file.centreCells().stream().map(c -> new int[] {c[0] - b[0], c[1] - b[1], c[2] - b[2]}).toList();
		return new BuildFile(file.name(), file.author(), file.description(), file.tool(), file.created(), origin, centres, file.offset(), file.seed(),
				file.settings(), file.procedural(), file.palette(), blocks);
	}

	private static int paletteIndex(List<String> palette, Map<String, Integer> index, String state) {
		return index.computeIfAbsent(state, s -> {
			palette.add(s);
			return palette.size() - 1;
		});
	}

	/** Saves in the background; {@code done} receives null on success or the error message, on the client thread. */
	public static void save(String name, BuildFile file, Consumer<@Nullable String> done) {
		CompletableFuture.supplyAsync(() -> {
			try {
				SavedBuilds.save(name, file);
				return (String) null;
			} catch (IOException | RuntimeException e) {
				HowToBuild.LOGGER.warn("Could not save build {}", name, e);
				return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			}
		}, IO).thenAccept(error -> Minecraft.getInstance().execute(() -> done.accept(error)));
	}

	public static void delete(String name, Runnable done) {
		CompletableFuture.runAsync(() -> {
			try {
				SavedBuilds.delete(name);
			} catch (IOException e) {
				HowToBuild.LOGGER.warn("Could not delete build {}", name, e);
			}

			INFO.remove(name);
		}, IO).whenComplete((v, e) -> Minecraft.getInstance().execute(done));
	}

	// ----------------------------------------------------------------- loading

	/**
	 * Loads a build as the active hologram: exact builds become the Saved Build tool (move, rotate, mirror and build it
	 * like any shape), procedural presets restore their tool and settings. Returns an error message or null.
	 */
	public static @Nullable String load(String name) {
		Info info = INFO.get(name);
		BuildFile file = info != null && info.file() != null ? info.file() : SavedBuilds.get(name).orElse(null);

		if (file == null) return SavedBuilds.error(name).orElse("not found");

		HowToBuildConfig config = HowToBuildConfig.get();

		if (file.procedural()) {
			try {
				config.applyPreset(JsonParser.parseString(file.settings()).getAsJsonObject());
			} catch (RuntimeException e) {
				return "invalid preset: " + e.getMessage();
			}
		} else {
			BuildTool saved = ToolRegistry.get("saved_build");
			config = HowToBuildConfig.get();
			config.tool = saved.id();
			config.setSetting(saved, "build", name);
		}

		HowToBuildConfig.save();
		return null;
	}

	// ----------------------------------------------------------------- comparison

	/**
	 * Compares the preview with the world: correct (same state), missing (air or replaceable), incorrect (another
	 * block) and extra (non-air blocks inside the preview's bounds that are not part of it).
	 */
	public static Comparison compare() {
		BuildSession.Resolved r = BuildSession.get().resolved();
		ClientLevel level = Minecraft.getInstance().level;

		if (r == null || level == null) return new Comparison(0, 0, 0, 0);

		int correct = 0;
		int missing = 0;
		int incorrect = 0;
		WorldTransform t = r.transform();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		List<Placement> placements = r.result().placements();
		it.unimi.dsi.fastutil.longs.LongOpenHashSet own = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

		for (int i = 0; i < placements.size(); i++) {
			if (!r.buildable()[i]) continue;

			Placement p = placements.get(i);
			own.add(p.key());
			BlockState actual = level.getBlockState(pos.set(t.x(p.x()), t.y(p.y()), t.z(p.z())));

			if (actual.equals(r.states()[i])) correct++;
			else if (actual.isAir() || actual.canBeReplaced()) missing++;
			else incorrect++;
		}

		int extra = 0;
		Box b = r.result().bounds();

		if (b != null && (long) b.sizeX() * b.sizeY() * b.sizeZ() <= 4_000_000L) {
			for (int y = b.minY(); y <= b.maxY(); y++) {
				for (int z = b.minZ(); z <= b.maxZ(); z++) {
					for (int x = b.minX(); x <= b.maxX(); x++) {
						if (own.contains(com.howtobuild.geometry.Voxels.pack(x, y, z))) continue;

						BlockState actual = level.getBlockState(pos.set(t.x(x), t.y(y), t.z(z)));

						if (!actual.isAir() && !actual.canBeReplaced()) extra++;
					}
				}
			}
		}

		return new Comparison(correct, missing, incorrect, extra);
	}
}
