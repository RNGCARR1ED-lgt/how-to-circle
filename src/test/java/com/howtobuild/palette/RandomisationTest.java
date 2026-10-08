package com.howtobuild.palette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.howtobuild.TestShapes;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.GeometryValidator;
import com.howtobuild.geometry.MaterialRef;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.transform.MirrorAxis;
import com.howtobuild.transform.MirrorMode;
import com.howtobuild.transform.MirrorSettings;
import com.howtobuild.transform.MirrorTransform;

class RandomisationTest {
	private static GenerationContext with(RandomSettings random) {
		return GenerationContext.DEFAULT.withPalettes(GenerationContext.DEFAULT.palettes().withRandom(random));
	}

	private static Map<String, Integer> counts(GeometryResult r) {
		Map<String, Integer> counts = new HashMap<>();

		for (Placement p : r.placements()) {
			MaterialRef ref = r.material(p);
			counts.merge(ref == null ? "<role>" : ref.value(), 1, Integer::sum);
		}

		return counts;
	}

	@Test
	void everyPatternMatchesThePercentagesExactly() {
		for (RandomPattern pattern : RandomPattern.values()) {
			GeometryResult r = TestShapes.generate("randomise", with(RandomSettings.of(TestShapes.TWO_BLOCKS, pattern, 7)), "width", 20, "length", 20);
			assertEquals(400, r.blockCount());
			Map<String, Integer> counts = counts(r);
			assertEquals(240, counts.get("minecraft:stone"), pattern + ": 60% of 400");
			assertEquals(160, counts.get("minecraft:andesite"), pattern + ": 40% of 400");
		}
	}

	@Test
	void sharesUseLargestRemainders() {
		WeightedPalette thirds = WeightedPalette.of(PaletteEntry.of("a:a", 1), PaletteEntry.of("a:b", 1), PaletteEntry.of("a:c", 1));
		int[] shares = thirds.shares(100, false);
		assertEquals(100, shares[0] + shares[1] + shares[2]);
		assertTrue(Math.abs(shares[0] - shares[2]) <= 1);
		assertEquals(List.of(50, 50), java.util.Arrays.stream(TestShapes.TWO_BLOCKS.shares(100, true)).boxed().toList(), "fully random: equal shares");
	}

	@Test
	void unnormalisedTotalsAreReported() {
		WeightedPalette off = new WeightedPalette(List.of(new PaletteEntry("a:a", 60, true), new PaletteEntry("a:b", 30, true)), false);
		assertNotNull(off.totalWarning());
		WeightedPalette normalised = new WeightedPalette(off.entries(), true);
		assertEquals(null, normalised.totalWarning());
		WeightedPalette disabled = new WeightedPalette(List.of(new PaletteEntry("a:a", 60, false)), true);
		assertTrue(disabled.isEmpty());
	}

	@Test
	void sameSeedSameResultOtherSeedOtherArrangement() {
		RandomSettings a = RandomSettings.of(TestShapes.TWO_BLOCKS, RandomPattern.COMPLETELY_RANDOM, 11);
		GeometryResult first = TestShapes.generate("randomise", with(a));
		GeometryResult second = TestShapes.generate("randomise", with(a));
		GeometryResult other = TestShapes.generate("randomise", with(a.withSeed(12)));
		assertEquals(materials(first), materials(second));
		assertNotEquals(materials(first), materials(other));
		assertEquals(counts(first), counts(other), "a new seed keeps the exact counts");
	}

	private static Map<Long, String> materials(GeometryResult r) {
		Map<Long, String> map = new HashMap<>();
		r.placements().forEach(p -> map.put(p.key(), r.material(p) == null ? "" : r.material(p).value()));
		return map;
	}

	@Test
	void randomisationNeverChangesGeometry() {
		for (String tool : List.of("circle", "spiral", "sphere", "dome", "corridor", "cylinder")) {
			GeometryResult plain = TestShapes.generate(tool);
			GeometryResult random = TestShapes.generate(tool, TestShapes.randomised(GenerationContext.DEFAULT));
			assertEquals(TestShapes.positions(plain), TestShapes.positions(random), tool + ": same positions");

			for (Placement p : plain.placements()) {
				Placement q = random.at(p.x(), p.y(), p.z());
				assertEquals(p.shape(), q.shape(), tool + ": same shapes");
				assertEquals(p.role(), q.role(), tool + ": same roles");
			}
		}
	}

	@Test
	void onlySelectedRolesAreRandomised() {
		RandomSettings steps = RandomSettings.of(TestShapes.TWO_BLOCKS, RandomPattern.NATURAL, 3).withRoles(EnumSet.of(MaterialRole.STEP));
		GeometryResult r = TestShapes.generate("spiral", with(steps).withDetails(com.howtobuild.details.DetailSettings.of(
				com.howtobuild.details.DetailFeature.CENTRAL_COLUMN)));
		assertTrue(r.count(MaterialRole.SUPPORT) > 0);

		for (Placement p : r.placements()) {
			assertEquals(p.role() == MaterialRole.STEP, p.hasMaterial(), "only steps get a palette block: " + p);
		}
	}

	@Test
	void protectedEdgeStaysOneBlock() {
		RandomSettings edge = RandomSettings.of(TestShapes.TWO_BLOCKS, RandomPattern.COMPLETELY_RANDOM, 5).withEdge(true, "minecraft:polished_andesite", 0);
		GeometryResult r = TestShapes.generate("randomise", with(edge), "width", 10, "length", 10);
		int edgeCells = 0;

		for (Placement p : r.placements()) {
			boolean onEdge = r.at(p.x() - 1, p.y(), p.z()) == null || r.at(p.x() + 1, p.y(), p.z()) == null
					|| r.at(p.x(), p.y(), p.z() - 1) == null || r.at(p.x(), p.y(), p.z() + 1) == null;
			String material = r.material(p).value();

			if (onEdge) {
				edgeCells++;
				assertEquals("minecraft:polished_andesite", material);
			} else {
				assertNotEquals("minecraft:polished_andesite", material);
			}
		}

		assertEquals(36, edgeCells);
		assertEquals(64, counts(r).get("minecraft:stone") + counts(r).get("minecraft:andesite"), "the inside keeps exact shares");
	}

	@Test
	void edgeVariationRandomisesSomeEdgeBlocks() {
		RandomSettings edge = RandomSettings.of(TestShapes.TWO_BLOCKS, RandomPattern.COMPLETELY_RANDOM, 5).withEdge(true, "minecraft:polished_andesite", 50);
		long kept = counts(TestShapes.generate("randomise", with(edge), "width", 30, "length", 30)).get("minecraft:polished_andesite");
		assertTrue(kept > 116 / 4 && kept < 116 * 3 / 4, "about half of the 116 edge blocks stay: " + kept);
	}

	@Test
	void perStepSymmetryGivesEachStepOneMaterial() {
		RandomSettings perStep = RandomSettings.of(TestShapes.TWO_BLOCKS, RandomPattern.COMPLETELY_RANDOM, 9).withRoles(EnumSet.of(MaterialRole.STEP))
				.withSymmetry(RandomSymmetry.PER_STEP);
		GeometryResult r = TestShapes.generate("spiral", with(perStep), "stair_width", 4);
		Map<Integer, Set<String>> byStep = new HashMap<>();

		for (Placement p : r.placements()) {
			if (p.role() != MaterialRole.STEP) continue;

			assertTrue(r.groups().containsKey(p.key()), "steps carry their step number");
			byStep.computeIfAbsent(r.groups().get(p.key()), k -> new HashSet<>()).add(r.material(p).value());
		}

		assertTrue(byStep.size() > 4);
		byStep.forEach((step, materials) -> assertEquals(1, materials.size(), "step " + step));
		assertEquals(2, byStep.values().stream().flatMap(Set::stream).distinct().count(), "both blocks are used");
	}

	@Test
	void perRevolutionSymmetryRepeatsEveryTurn() {
		RandomSettings perTurn = RandomSettings.of(TestShapes.TWO_BLOCKS, RandomPattern.COMPLETELY_RANDOM, 9).withRoles(EnumSet.of(MaterialRole.STEP))
				.withSymmetry(RandomSymmetry.PER_REVOLUTION);
		GeometryResult r = TestShapes.generate("spiral", with(perTurn), "revolutions", 2, "height", 16);
		long period = Math.round(r.values().get("steps") / r.values().get("revolutions"));
		Map<Long, Set<String>> byPhase = new HashMap<>();

		for (Placement p : r.placements()) {
			if (p.role() == MaterialRole.STEP) byPhase.computeIfAbsent(Math.floorMod(r.groups().get(p.key()), period), k -> new HashSet<>()).add(r.material(p).value());
		}

		byPhase.forEach((phase, materials) -> assertEquals(1, materials.size(), "step " + phase + " repeats each turn"));
	}

	@Test
	void mirrorRandomisationModes() {
		RandomSettings mirrored = RandomSettings.of(TestShapes.TWO_BLOCKS, RandomPattern.COMPLETELY_RANDOM, 4);
		GeometryResult r = TestShapes.generate("randomise", with(mirrored), "width", 12, "length", 12);
		MirrorSettings settings = new MirrorSettings(true, MirrorAxis.X, MirrorMode.DUPLICATE, false, false, true, true, 20, 0);
		GeometryResult copy = MirrorTransform.apply(r, settings, 0, 0);
		assertEquals(copy, Randomiser.randomiseMirrored(copy, mirrored), "Mirrored: copies repeat the original");

		RandomSettings independent = new RandomSettings(true, mirrored.palette(), mirrored.mode(), mirrored.pattern(), 4, 3, 8, 0.8, 3, 1, 0,
				EnumSet.allOf(MaterialRole.class), false, "", 0, RandomSymmetry.INDEPENDENT, MirrorRandomisation.INDEPENDENT);
		GeometryResult own = Randomiser.randomiseMirrored(copy, independent);
		assertEquals(TestShapes.positions(copy), TestShapes.positions(own));
		int differ = 0;
		int mirroredBlocks = 0;

		for (Placement p : own.placements()) {
			if (!p.mirrored()) {
				assertEquals(copy.material(copy.at(p.x(), p.y(), p.z())), own.material(p), "originals keep their materials");
				continue;
			}

			mirroredBlocks++;
			// In the Mirrored arrangement this block repeats its original's material.
			if (!copy.material(copy.at(p.x(), p.y(), p.z())).equals(own.material(p))) differ++;
		}

		assertEquals(144, mirroredBlocks);
		assertTrue(differ > 20, "independent copies differ from the original: " + differ);
		Map<String, Integer> mirrorCounts = new HashMap<>();
		own.placements().stream().filter(Placement::mirrored).forEach(p -> mirrorCounts.merge(own.material(p).value(), 1, Integer::sum));
		assertEquals(Map.of("minecraft:stone", 86, "minecraft:andesite", 58), mirrorCounts, "the copy has exact shares too");
	}

	@Test
	void randomisedResultsPassValidation() {
		for (RandomPattern pattern : RandomPattern.values()) {
			GeometryResult r = TestShapes.generate("spiral", with(RandomSettings.of(TestShapes.TWO_BLOCKS, pattern, 1)));
			assertFalse(r.warnings().stream().anyMatch(GeometryValidator::isBlocking), pattern + ": " + r.warnings());
		}
	}
}
