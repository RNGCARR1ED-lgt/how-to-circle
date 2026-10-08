package com.howtobuild.saves;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The saved-builds folder and a cache of loaded builds. Files are read on first use (by the generation thread, never
 * the render thread) and re-read only when they change on disk. A damaged file is reported, never thrown.
 */
public final class SavedBuilds {
	private static volatile Path directory;
	private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, String> ERRORS = new ConcurrentHashMap<>();

	private record Entry(long modified, long size, BuildFile file) {
	}

	private SavedBuilds() {
	}

	public static void setDirectory(Path dir) {
		directory = dir;
		CACHE.clear();
		ERRORS.clear();
	}

	public static Optional<Path> directory() {
		return Optional.ofNullable(directory);
	}

	/** The file a build name is stored in. */
	public static Optional<Path> path(String name) {
		Path dir = directory;
		return dir == null ? Optional.empty() : Optional.of(dir.resolve(HwbCodec.fileName(name)));
	}

	/** Names (file names without the extension) of every saved build, sorted. */
	public static List<String> list() {
		Path dir = directory;
		List<String> names = new ArrayList<>();

		if (dir == null || !Files.isDirectory(dir)) return names;

		try (Stream<Path> files = Files.list(dir)) {
			files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(HwbCodec.EXTENSION))
					.map(n -> n.substring(0, n.length() - HwbCodec.EXTENSION.length())).sorted(String.CASE_INSENSITIVE_ORDER).forEach(names::add);
		} catch (IOException e) {
			// An unreadable folder lists as empty.
		}

		return names;
	}

	/** The build with this name: from memory (just saved or put by a test), otherwise loaded from the folder. */
	public static Optional<BuildFile> get(String name) {
		if (name == null || name.isBlank()) return Optional.empty();

		String key = key(name);
		Entry cached = CACHE.get(key);

		if (cached != null && cached.modified() < 0) return Optional.of(cached.file());

		Optional<Path> path = path(name);

		if (path.isEmpty() || !Files.isRegularFile(path.get())) return cached == null ? Optional.empty() : Optional.of(cached.file());

		try {
			long modified = Files.getLastModifiedTime(path.get()).toMillis();
			long size = Files.size(path.get());

			if (cached != null && cached.modified() == modified && cached.size() == size) return Optional.of(cached.file());

			BuildFile file = HwbCodec.load(path.get());
			CACHE.put(key, new Entry(modified, size, file));
			ERRORS.remove(key);
			return Optional.of(file);
		} catch (IOException | RuntimeException e) {
			ERRORS.put(key, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
			return Optional.empty();
		}
	}

	/** Why the build could not be loaded, if it failed. */
	public static Optional<String> error(String name) {
		return Optional.ofNullable(ERRORS.get(key(name)));
	}

	/** Keeps a build in memory under this name (it takes precedence over the folder). */
	public static void put(String name, BuildFile file) {
		CACHE.put(key(name), new Entry(-1, -1, file));
		ERRORS.remove(key(name));
	}

	/** Forgets a cached build (after it was saved, deleted or replaced on disk). */
	public static void invalidate(String name) {
		CACHE.remove(key(name));
		ERRORS.remove(key(name));
	}

	/** Saves atomically and refreshes the cache. */
	public static Path save(String name, BuildFile file) throws IOException {
		Path target = path(name).orElseThrow(() -> new IOException("The saved-builds folder is not available."));
		HwbCodec.save(file, target);
		invalidate(name);
		return target;
	}

	public static void delete(String name) throws IOException {
		Optional<Path> path = path(name);

		if (path.isPresent()) Files.deleteIfExists(path.get());

		invalidate(name);
	}

	private static String key(String name) {
		return HwbCodec.fileName(name).toLowerCase(java.util.Locale.ROOT);
	}
}
