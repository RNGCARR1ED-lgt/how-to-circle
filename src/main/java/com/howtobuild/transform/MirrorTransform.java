package com.howtobuild.transform;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import com.howtobuild.geometry.BlockShape;
import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.StairShapes;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.Placement;

/**
 * Exact block-grid mirroring.
 *
 * <p>Coordinates are doubled so every plane position is an integer: block {@code x} spans {@code [2x, 2x + 2]} and its
 * centre is {@code 2x + 1}. The plane passes through the middle of a 1-block mirror centre ({@code 2c + 1}) or through
 * the shared face of a 2-block centre ({@code 2c + 2} or {@code 2c}), shifted by {@code 2 × offset}. Reflecting a block
 * centre {@code 2x + 1} about plane {@code P} gives {@code 2P - 2x - 1}, i.e. block {@code x' = P - x - 1}. All
 * arithmetic is integer, so the mirror is exact.
 *
 * <p>Stair states are mirrored too (facing reversed when it points across the plane, left/right shapes swapped);
 * slabs and full blocks are unchanged.
 */
public final class MirrorTransform {
	private MirrorTransform() {
	}

	/**
	 * Reflects block index {@code x} across a plane at doubled corner coordinate {@code planeDoubled} (the plane through
	 * the middle of block {@code c} has {@code planeDoubled = 2c + 1}).
	 */
	public static int reflect(int x, int planeDoubled) {
		return planeDoubled - 1 - x;
	}

	/**
	 * Doubled corner coordinate of the plane for a mirror centre at block {@code centre}: through the middle of the
	 * block for a 1-wide centre, or through the shared face of a 2-wide centre, plus {@code offset} blocks.
	 */
	public static int planeDoubled(int centre, boolean twoWide, boolean alignPositive, int offset) {
		int plane = 2 * centre + 1;

		if (twoWide) plane += alignPositive ? 1 : -1;

		return plane + 2 * offset;
	}

	/**
	 * Applies the mirror to a result. {@code centreX/centreZ} are the mirror centre block, relative to the result's
	 * anchor. Mirrored placements are flagged {@link Placement#mirrored()}.
	 */
	public static GeometryResult apply(GeometryResult source, MirrorSettings settings, int centreX, int centreZ) {
		if (!settings.enabled() || source.isEmpty()) return source;

		int planeX = planeDoubled(centreX, settings.twoWideX(), settings.alignX(), settings.offsetX());
		int planeZ = planeDoubled(centreZ, settings.twoWideZ(), settings.alignZ(), settings.offsetZ());
		MirrorAxis axis = settings.axis();
		List<Placement> out = new ArrayList<>();
		LongOpenHashSet occupied = new LongOpenHashSet();

		if (settings.mode().keepsOriginal()) {
			for (Placement p : source.placements()) {
				out.add(p);
				occupied.add(p.key());
			}
		}

		List<boolean[]> copies = new ArrayList<>();

		if (axis.mirrorsX()) copies.add(new boolean[] {true, false});
		if (axis.mirrorsZ()) copies.add(new boolean[] {false, true});
		if (axis.mirrorsX() && axis.mirrorsZ()) copies.add(new boolean[] {true, true});

		for (boolean[] copy : copies) {
			for (Placement p : source.placements()) {
				Placement m = mirror(p, copy[0], copy[1], planeX, planeZ);

				if (occupied.add(m.key())) out.add(m);
			}
		}

		// Where copies meet, stair corners are re-derived from their new neighbours, exactly as Minecraft would.
		GeometryBuilder builder = new GeometryBuilder();
		out.forEach(builder::put);
		StairShapes.resolve(builder);
		List<int[]> centres = new ArrayList<>(source.centreCells());
		return new GeometryResult(source.toolId(), builder.snapshot(), centres, source.values(), source.warnings(), source.planeNormalAxis());
	}

	/** Mirrors one placement across the X and/or Z plane, including its block state. */
	public static Placement mirror(Placement p, boolean acrossX, boolean acrossZ, int planeX, int planeZ) {
		int x = acrossX ? reflect(p.x(), planeX) : p.x();
		int z = acrossZ ? reflect(p.z(), planeZ) : p.z();
		BlockShape shape = p.shape();

		if (acrossX) shape = shape.mirrored(true);
		if (acrossZ) shape = shape.mirrored(false);

		return p.asMirror(x, p.y(), z, shape);
	}
}
