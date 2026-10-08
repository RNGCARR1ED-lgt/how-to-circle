package com.howtobuild.saves;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.howtobuild.TestShapes;
import com.howtobuild.commands.MaterialCounter;
import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.Facing;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.Placement;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.transform.MirrorAxis;
import com.howtobuild.transform.MirrorMode;
import com.howtobuild.transform.MirrorSettings;
import com.howtobuild.transform.MirrorTransform;

class SavedBuildTest {
	private static byte[] bytes(BuildFile file) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		HwbCodec.write(file, out);
		return out.toByteArray();
	}

	private static void assertSame(BuildFile a, BuildFile b) {
		assertEquals(a.name(), b.name());
		assertEquals(a.author(), b.author());
		assertEquals(a.tool(), b.tool());
		assertEquals(a.origin(), b.origin());
		assertEquals(a.palette(), b.palette());
		assertArrayEquals(a.blocks(), b.blocks());
		assertArrayEquals(a.offset(), b.offset());
		assertEquals(a.centreCells().size(), b.centreCells().size());

		for (int i = 0; i < a.centreCells().size(); i++) {
			assertArrayEquals(a.centreCells().get(i), b.centreCells().get(i));
		}
	}

	@Test
	void roundTripKeepsEverything() throws IOException {
		BuildFile file = TestShapes.sampleBuild();
		BuildFile back = HwbCodec.read(new ByteArrayInputStream(bytes(file)));
		assertSame(file, back);
	}

	@Test
	void damagedFilesAreRejectedNotThrown() throws IOException {
		byte[] good = bytes(TestShapes.sampleBuild());
		assertThrows(HwbFormatException.class, () -> HwbCodec.read(new ByteArrayInputStream(new byte[] {'N', 'O', 'P', 'E'})));
		assertThrows(HwbFormatException.class, () -> HwbCodec.read(new ByteArrayInputStream(java.util.Arrays.copyOf(good, good.length / 2))));
		byte[] newer = good.clone();
		newer[3] = (byte) (HwbCodec.VERSION + 1);
		assertThrows(HwbFormatException.class, () -> HwbCodec.read(new ByteArrayInputStream(newer)));
	}

	@Test
	void invalidBuildsAreNeverWritten() {
		BuildFile badIndex = new BuildFile("x", "", "", "", 0, BuildFile.Origin.CENTER, List.of(), null, 0, "", false, List.of("minecraft:stone"),
				new int[] {0, 0, 0, 5});
		BuildFile badState = new BuildFile("x", "", "", "", 0, BuildFile.Origin.CENTER, List.of(), null, 0, "", false, List.of("Not A State!"),
				new int[] {0, 0, 0, 0});
		assertThrows(HwbFormatException.class, () -> bytes(badIndex));
		assertThrows(HwbFormatException.class, () -> bytes(badState));
	}

	@Test
	void aFailedSaveKeepsTheOldFile(@TempDir Path dir) throws IOException {
		Path target = dir.resolve("tower.hwb");
		HwbCodec.save(TestShapes.sampleBuild(), target);
		byte[] before = Files.readAllBytes(target);
		BuildFile broken = new BuildFile("x", "", "", "", 0, BuildFile.Origin.CENTER, List.of(), null, 0, "", false, List.of("minecraft:stone"),
				new int[] {0, 0, 0, 1});
		assertThrows(IOException.class, () -> HwbCodec.save(broken, target));
		assertArrayEquals(before, Files.readAllBytes(target), "the old file is untouched");
		assertSame(TestShapes.sampleBuild(), HwbCodec.load(target));
		try (var files = Files.list(dir)) {
			assertEquals(List.of(target), files.toList(), "no temporary file is left behind");
		}
	}

	@Test
	void folderListingAndCache(@TempDir Path dir) throws IOException {
		SavedBuilds.setDirectory(dir);

		try {
			SavedBuilds.save("My Tower", TestShapes.sampleBuild());
			Files.writeString(dir.resolve("broken.hwb"), "garbage");
			assertEquals(List.of("broken", "My Tower"), SavedBuilds.list());
			assertEquals(TestShapes.sampleBuild().blockCount(), SavedBuilds.get("My Tower").orElseThrow().blockCount());
			assertTrue(SavedBuilds.get("broken").isEmpty());
			assertTrue(SavedBuilds.error("broken").isPresent(), "a damaged file is reported");
			SavedBuilds.delete("My Tower");
			assertTrue(SavedBuilds.get("My Tower").isEmpty());
		} finally {
			SavedBuilds.setDirectory(null);
			SavedBuilds.put(TestShapes.SAVED_FIXTURE, TestShapes.sampleBuild());
		}
	}

	@Test
	void savedEqualsPlaced() {
		BuildFile file = TestShapes.sampleBuild();
		GeometryResult r = TestShapes.generate("saved_build");
		assertEquals(file.blockCount(), r.blockCount());

		for (int i = 0; i < file.blocks().length; i += 4) {
			int[] b = file.blocks();
			Placement p = r.at(b[i], b[i + 1], b[i + 2]);
			assertEquals(file.palette().get(b[i + 3]), r.material(p).value(), "exact state at " + b[i] + "," + b[i + 1] + "," + b[i + 2]);
		}

		assertEquals(4, r.centreCells().size(), "the 2×2 centre is kept");
		assertEquals(Facing.NORTH, r.at(0, 1, -1).shape().facing(), "stairs are shaped from their state");
		assertEquals(BlockShape.TOP_SLAB, r.at(0, 1, 2).shape());
	}

	@Test
	void flipsTransformPositionsAndStates() {
		GeometryResult flipped = TestShapes.generate("saved_build", "flip_x", true);
		// The 2×2 centre spans x 0..1, so x mirrors to 1 − x.
		assertEquals("minecraft:oak_door[facing=west,half=lower,hinge=right,open=false,powered=false]", flipped.material(flipped.at(-1, 1, 1)).value());
		assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", flipped.material(flipped.at(1, 1, -1)).value());
		GeometryResult upside = TestShapes.generate("saved_build", "upside_down", true);
		assertEquals("minecraft:oak_slab[type=bottom,waterlogged=false]", upside.material(upside.at(0, 1, 2)).value());
		assertEquals("minecraft:stone", upside.material(upside.at(0, 2, 0)).value(), "the floor is now on top");
		GeometryResult z = TestShapes.generate("saved_build", "flip_z", true);
		assertEquals("minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]", z.material(z.at(0, 1, 2)).value());
	}

	@Test
	void rotationAndMirrorTransformExactStates() {
		GeometryResult turned = TestShapes.generate("saved_build", GenerationContext.DEFAULT.withRotation(1));
		long stairsEast = turned.placements().stream().filter(p -> turned.material(p).value().startsWith("minecraft:oak_stairs[facing=east")).count();
		assertEquals(2, stairsEast, "a quarter turn turns north-facing stairs east");
		assertEquals(TestShapes.sampleBuild().blockCount(), turned.blockCount());

		GeometryResult base = TestShapes.generate("saved_build");
		MirrorSettings mirror = new MirrorSettings(true, MirrorAxis.Z, MirrorMode.DUPLICATE, false, false, true, true, 0, -10);
		GeometryResult both = MirrorTransform.apply(base, mirror, 0, 0);
		assertTrue(both.placements().stream().filter(Placement::mirrored).anyMatch(p -> both.material(p).value().startsWith("minecraft:oak_stairs[facing=south")),
				"mirrored stairs face the other way");
	}

	@Test
	void materialCountsComeFromTheExactStates() {
		BuildFile file = TestShapes.sampleBuild();
		List<String> states = new java.util.ArrayList<>();

		for (int i = 3; i < file.blocks().length; i += 4) {
			states.add(file.palette().get(file.blocks()[i]));
		}

		List<MaterialCounter.Count> counts = MaterialCounter.count(states);
		assertEquals(file.blockCount(), MaterialCounter.total(counts));
		assertEquals("minecraft:stone", counts.getFirst().block());
		assertEquals(16, counts.getFirst().count());
		MaterialCounter.Count stairs = counts.stream().filter(c -> c.block().equals("minecraft:oak_stairs")).findFirst().orElseThrow();
		assertEquals(Map.of("north", 2L), MaterialCounter.byProperty(stairs, "facing"));
		assertFalse(counts.stream().anyMatch(c -> c.block().contains("[")));
	}
}
