package com.howtobuild.geometry;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Checks a result before anything is built. Every problem it reports blocks building (see
 * {@link #isBlocking(String)}): these are generator bugs or impossible settings, never something to work around.
 *
 * <ul>
 *     <li>duplicate positions (one final state per position);</li>
 *     <li>blocks below the base layer for base-anchored tools;</li>
 *     <li>blocks in a protected column (terrain protected areas);</li>
 *     <li>blocks outside the tool's declared region;</li>
 *     <li>material indices that do not exist.</li>
 * </ul>
 */
public final class GeometryValidator {
	/** Prefix of every validator message; such messages block building. */
	public static final String PREFIX = "⛔ ";

	private GeometryValidator() {
	}

	public static boolean isBlocking(String message) {
		return message.startsWith(PREFIX);
	}

	public static List<String> validate(GeometryResult result, boolean baseAnchored) {
		List<String> issues = new ArrayList<>();
		LongOpenHashSet seen = new LongOpenHashSet(result.blockCount());
		int duplicates = 0;
		int below = 0;
		int protectedHits = 0;
		int outside = 0;
		int badMaterial = 0;
		boolean checkRegion = !result.region().isEmpty();
		boolean checkProtected = !result.protectedColumns().isEmpty();

		for (Placement p : result.placements()) {
			if (!seen.add(p.key())) duplicates++;
			if (baseAnchored && p.y() < 0) below++;

			long column = Voxels.pack(p.x(), 0, p.z());

			if (checkProtected && result.protectedColumns().contains(column)) protectedHits++;
			if (checkRegion && !p.mirrored() && !result.region().contains(column)) outside++;
			if (p.material() >= result.materials().size()) badMaterial++;
		}

		if (duplicates > 0) issues.add(PREFIX + duplicates + " duplicate block positions.");
		if (below > 0) issues.add(PREFIX + below + " blocks below the base layer. Please report this.");
		if (protectedHits > 0) issues.add(PREFIX + protectedHits + " blocks inside the protected area. Please report this.");
		if (outside > 0) issues.add(PREFIX + outside + " blocks outside the selected region. Please report this.");
		if (badMaterial > 0) issues.add(PREFIX + badMaterial + " blocks reference a missing material.");
		return issues;
	}
}
