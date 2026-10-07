package com.howtobuild.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The exact, immutable output of a tool: every block position (relative to the anchor), its role and shape, the centre
 * blocks, bounds, measured dimensions, tool values and validation warnings.
 *
 * <p>Everything downstream (hologram, labels, mirror, command building, progress tracking) reads this one object, so
 * all outputs always agree with each other.
 */
public final class GeometryResult {
	public static final Comparator<Placement> ORDER = Comparator.comparingInt(Placement::y)
			.thenComparingInt(Placement::z).thenComparingInt(Placement::x).thenComparing(Placement::mirrored);

	private final String toolId;
	private final List<Placement> placements;
	private final List<int[]> centreCells;
	private final Map<String, Double> values;
	private final List<String> warnings;
	private final Box bounds;
	private final int planeNormalAxis;
	private Long2ObjectOpenHashMap<Placement> index;

	public GeometryResult(String toolId, List<Placement> placements, List<int[]> centreCells, Map<String, Double> values,
			List<String> warnings, int planeNormalAxis) {
		List<Placement> sorted = new ArrayList<>(placements);
		sorted.sort(ORDER);
		this.toolId = toolId;
		this.placements = Collections.unmodifiableList(sorted);
		this.centreCells = List.copyOf(centreCells);
		this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		this.warnings = List.copyOf(warnings);
		this.planeNormalAxis = planeNormalAxis;

		Box box = null;

		for (Placement p : sorted) {
			box = box == null ? Box.of(p.x(), p.y(), p.z()) : box.include(p.x(), p.y(), p.z());
		}

		this.bounds = box;
	}

	public static GeometryResult empty(String toolId, List<String> warnings) {
		return new GeometryResult(toolId, List.of(), List.of(), Map.of(), warnings, -1);
	}

	public static GeometryResult of(String toolId, GeometryBuilder builder, int planeNormalAxis) {
		return new GeometryResult(toolId, builder.snapshot(), builder.centreCells(), builder.values(), builder.warnings(), planeNormalAxis);
	}

	public String toolId() {
		return toolId;
	}

	public List<Placement> placements() {
		return placements;
	}

	public int blockCount() {
		return placements.size();
	}

	public boolean isEmpty() {
		return placements.isEmpty();
	}

	public List<int[]> centreCells() {
		return centreCells;
	}

	public Map<String, Double> values() {
		return values;
	}

	public List<String> warnings() {
		return warnings;
	}

	/** Bounds of all placements, or {@code null} when empty. */
	public Box bounds() {
		return bounds;
	}

	/** For flat (one block thick) tools, the axis perpendicular to the shape (0 = X, 1 = Y, 2 = Z); otherwise -1. */
	public int planeNormalAxis() {
		return planeNormalAxis;
	}

	/** Width along X measured from the generated coordinates: {@code maxX - minX + 1}. */
	public int width() {
		return bounds == null ? 0 : bounds.sizeX();
	}

	/** Height along Y measured from the generated coordinates. */
	public int height() {
		return bounds == null ? 0 : bounds.sizeY();
	}

	/** Length along Z measured from the generated coordinates. */
	public int length() {
		return bounds == null ? 0 : bounds.sizeZ();
	}

	public Placement at(int x, int y, int z) {
		if (index == null) {
			Long2ObjectOpenHashMap<Placement> map = new Long2ObjectOpenHashMap<>(placements.size());

			for (Placement p : placements) {
				map.putIfAbsent(p.key(), p);
			}

			index = map;
		}

		return index.get(Voxels.pack(x, y, z));
	}

	public long count(MaterialRole role) {
		return placements.stream().filter(p -> p.role() == role).count();
	}

	public long count(BlockShape.Kind kind) {
		return placements.stream().filter(p -> p.shape().kind() == kind).count();
	}
}
