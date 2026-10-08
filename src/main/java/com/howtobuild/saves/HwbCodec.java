package com.howtobuild.saves;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The {@code .hwb} file format: How to Build's own compact build format.
 *
 * <pre>
 * "HWB" + format version (1 byte), then gzip:
 *   name, author, description, tool, created, origin, offset[3], seed, settings JSON, procedural flag
 *   centre cell count, then x y z for each
 *   palette size, then one exact block state string per entry
 *   block count, then x y z (relative to the origin) and palette index for each block
 * </pre>
 * Every block refers to the palette by index, so a state is written once however often it is used. Reading validates
 * the version, palette syntax, indices and sizes and reports problems as {@link HwbFormatException}; it never returns
 * a half-read build. Writing goes to a temporary file that atomically replaces the old one, so a failed save never
 * destroys an existing build.
 */
public final class HwbCodec {
	public static final String EXTENSION = ".hwb";
	public static final int VERSION = 1;
	public static final int MAX_BLOCKS = 4_000_000;
	public static final int MAX_PALETTE = 65_536;
	private static final byte[] MAGIC = {'H', 'W', 'B'};
	private static final Pattern STATE = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*\\])?");

	private HwbCodec() {
	}

	public static void write(BuildFile file, OutputStream raw) throws IOException {
		raw.write(MAGIC);
		raw.write(VERSION);
		GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(raw));
		DataOutputStream out = new DataOutputStream(gzip);
		out.writeUTF(file.name());
		out.writeUTF(file.author());
		out.writeUTF(file.description());
		out.writeUTF(file.tool());
		out.writeLong(file.created());
		out.writeByte(file.origin().ordinal());

		for (int v : file.offset()) {
			out.writeInt(v);
		}

		out.writeLong(file.seed());
		writeLongString(out, file.settings());
		out.writeBoolean(file.procedural());
		out.writeInt(file.centreCells().size());

		for (int[] c : file.centreCells()) {
			out.writeInt(c[0]);
			out.writeInt(c[1]);
			out.writeInt(c[2]);
		}

		out.writeInt(file.palette().size());

		for (String state : file.palette()) {
			out.writeUTF(state);
		}

		out.writeInt(file.blockCount());
		int[] b = file.blocks();

		for (int i = 0; i < b.length; i += 4) {
			out.writeInt(b[i]);
			out.writeInt(b[i + 1]);
			out.writeInt(b[i + 2]);
			out.writeInt(b[i + 3]);
		}

		out.flush();
		gzip.finish();
		gzip.flush();
	}

	public static BuildFile read(InputStream raw) throws IOException {
		InputStream in = new BufferedInputStream(raw);
		byte[] magic = in.readNBytes(3);

		if (magic.length != 3 || magic[0] != 'H' || magic[1] != 'W' || magic[2] != 'B') throw new HwbFormatException("Not a How to Build file.");

		int version = in.read();

		if (version < 1) throw new HwbFormatException("Missing format version.");
		if (version > VERSION) throw new HwbFormatException("Made by a newer version of How to Build (format " + version + ").");

		try (DataInputStream data = new DataInputStream(new GZIPInputStream(in))) {
			String name = data.readUTF();
			String author = data.readUTF();
			String description = data.readUTF();
			String tool = data.readUTF();
			long created = data.readLong();
			int originIndex = data.readByte();

			if (originIndex < 0 || originIndex >= BuildFile.Origin.values().length) throw new HwbFormatException("Unknown origin " + originIndex + ".");

			int[] offset = {data.readInt(), data.readInt(), data.readInt()};
			long seed = data.readLong();
			String settings = readLongString(data);
			boolean procedural = data.readBoolean();
			int centres = data.readInt();

			if (centres < 0 || centres > 64) throw new HwbFormatException("Invalid centre (" + centres + " cells).");

			List<int[]> centreCells = new ArrayList<>();

			for (int i = 0; i < centres; i++) {
				centreCells.add(new int[] {data.readInt(), data.readInt(), data.readInt()});
			}

			int paletteSize = data.readInt();

			if (paletteSize < 0 || paletteSize > MAX_PALETTE) throw new HwbFormatException("Invalid palette size " + paletteSize + ".");

			List<String> palette = new ArrayList<>();

			for (int i = 0; i < paletteSize; i++) {
				String state = data.readUTF();

				if (!STATE.matcher(state).matches()) throw new HwbFormatException("Invalid block state in palette: " + state);

				palette.add(state);
			}

			int count = data.readInt();

			if (count < 0 || count > MAX_BLOCKS) throw new HwbFormatException("Invalid block count " + count + ".");
			if (count > 0 && paletteSize == 0) throw new HwbFormatException("Blocks without a palette.");

			int[] blocks = new int[count * 4];

			for (int i = 0; i < count; i++) {
				blocks[i * 4] = data.readInt();
				blocks[i * 4 + 1] = data.readInt();
				blocks[i * 4 + 2] = data.readInt();
				int index = data.readInt();

				if (index < 0 || index >= paletteSize) throw new HwbFormatException("Block " + i + " refers to palette entry " + index + " of " + paletteSize + ".");

				blocks[i * 4 + 3] = index;
			}

			return new BuildFile(name, author, description, tool, created, BuildFile.Origin.values()[originIndex], centreCells, offset, seed, settings,
					procedural, palette, blocks);
		} catch (java.io.EOFException | java.util.zip.ZipException e) {
			throw new HwbFormatException("The file is truncated or damaged.");
		}
	}

	/** Writes to {@code target} through a temporary file, so an existing build is only replaced by a complete one. */
	public static void save(BuildFile file, Path target) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");

		try (OutputStream out = Files.newOutputStream(temp)) {
			write(file, out);
		}

		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static BuildFile load(Path path) throws IOException {
		try (InputStream in = Files.newInputStream(path)) {
			return read(in);
		}
	}

	/** A safe file name for a build name. */
	public static String fileName(String name) {
		String safe = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9 _-]", "_");
		return (safe.isEmpty() ? "build" : safe) + EXTENSION;
	}

	private static void writeLongString(DataOutputStream out, String s) throws IOException {
		byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	private static String readLongString(DataInputStream in) throws IOException {
		int length = in.readInt();

		if (length < 0 || length > 16_000_000) throw new HwbFormatException("Invalid settings length.");

		return new String(in.readNBytes(length), java.nio.charset.StandardCharsets.UTF_8);
	}
}
