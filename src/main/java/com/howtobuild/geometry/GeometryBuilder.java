package com.howtobuild.geometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Mutable voxel map used while generating. Later writes replace earlier ones, so details naturally override the base
 * structure at the same position. Keys are {@link Voxels#pack packed} offsets relative to the anchor.
 */
public final class GeometryBuilder {
	private final Long2ObjectOpenHashMap<Placement> cells = new Long2ObjectOpenHashMap<>();
	private final List<String> warnings = new ArrayList<>();
	private final Map<String, Double> values = new LinkedHashMap<>();
	private final List<int[]> centreCells = new ArrayList<>();
	private final List<int[]> guides = new ArrayList<>();
	private final List<MaterialRef> materials = new ArrayList<>();
	private final java.util.Map<MaterialRef, Integer> materialIndex = new java.util.HashMap<>();
	private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap groups = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
	private final List<Annotation> annotations = new ArrayList<>();
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet region = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet protectedColumns = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	public void set(int x, int y, int z, MaterialRole role, BlockShape shape) {
		cells.put(Voxels.pack(x, y, z), new Placement(x, y, z, role, shape, 0, false));
	}

	public void set(int x, int y, int z, MaterialRole role) {
		set(x, y, z, role, BlockShape.FULL);
	}

	public void put(Placement placement) {
		cells.put(placement.key(), placement);
	}

	/** Places a block only if the position is empty. */
	public boolean setIfAbsent(int x, int y, int z, MaterialRole role, BlockShape shape) {
		long key = Voxels.pack(x, y, z);

		if (cells.containsKey(key)) return false;

		cells.put(key, new Placement(x, y, z, role, shape, 0, false));
		return true;
	}

	/** Changes the role of an existing block, keeping its shape. Returns false if the position is empty. */
	public boolean setRole(int x, int y, int z, MaterialRole role) {
		long key = Voxels.pack(x, y, z);
		Placement existing = cells.get(key);

		if (existing == null) return false;

		cells.put(key, existing.withRole(role));
		return true;
	}

	public void remove(int x, int y, int z) {
		cells.remove(Voxels.pack(x, y, z));
	}

	public boolean has(int x, int y, int z) {
		return cells.containsKey(Voxels.pack(x, y, z));
	}

	public Placement get(int x, int y, int z) {
		return cells.get(Voxels.pack(x, y, z));
	}

	public int size() {
		return cells.size();
	}

	public boolean isEmpty() {
		return cells.isEmpty();
	}

	public void forEach(Consumer<Placement> action) {
		for (Placement placement : cells.values()) {
			action.accept(placement);
		}
	}

	/** A snapshot of all placements (safe to iterate while modifying the builder). */
	public List<Placement> snapshot() {
		return new ArrayList<>(cells.values());
	}

	public Long2ObjectMap<Placement> cells() {
		return cells;
	}

	public void clear() {
		cells.clear();
	}

	public void warn(String warning) {
		if (!warnings.contains(warning)) warnings.add(warning);
	}

	public List<String> warnings() {
		return warnings;
	}

	/** Records a tool-specific dimension value (radius, revolutions, steps, …) for labels and the GUI. */
	public void value(String key, double value) {
		values.put(key, value);
	}

	public Map<String, Double> values() {
		return values;
	}

	/**
	 * Adds a guide cell: part of a reference outline (e.g. the master circle a spiral follows) that is drawn in its own
	 * hologram style but never built.
	 */
	public void guide(int x, int y, int z) {
		guide(x, y, z, Guide.OUTLINE);
	}

	/** Adds a guide cell of a given kind (see {@link Guide}): {x, y, z, kind}. */
	public void guide(int x, int y, int z, int kind) {
		guides.add(new int[] {x, y, z, kind});
	}

	/** Index of a material reference in this result's material list (added once). */
	public int material(MaterialRef ref) {
		return materialIndex.computeIfAbsent(ref, r -> {
			materials.add(r);
			return materials.size() - 1;
		});
	}

	public List<MaterialRef> materials() {
		return materials;
	}

	/** Replaces the material list (used when transforms rewrite exact states); indices must stay valid. */
	public void replaceMaterials(List<MaterialRef> newMaterials) {
		materials.clear();
		materialIndex.clear();

		for (MaterialRef ref : newMaterials) {
			materials.add(ref);
			materialIndex.putIfAbsent(ref, materials.size() - 1);
		}
	}

	/** Assigns a group id (e.g. the spiral step) to a position, for grouped randomisation. */
	public void group(int x, int y, int z, int id) {
		groups.put(Voxels.pack(x, y, z), id);
	}

	public it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap groups() {
		return groups;
	}

	/** Declares column (x, z) part of the tool's region: nothing may be generated outside a declared region. */
	public void regionColumn(int x, int z) {
		region.add(Voxels.pack(x, 0, z));
	}

	public it.unimi.dsi.fastutil.longs.LongOpenHashSet region() {
		return region;
	}

	/** Declares column (x, z) protected: nothing may ever be generated in it, at any height. */
	public void protectColumn(int x, int z) {
		protectedColumns.add(Voxels.pack(x, 0, z));
	}

	public it.unimi.dsi.fastutil.longs.LongOpenHashSet protectedColumns() {
		return protectedColumns;
	}

	/** A positioned text label (e.g. a contour height) shown with the hologram's labels. */
	public void annotate(int x, int y, int z, String text) {
		annotations.add(new Annotation(x, y, z, text));
	}

	public List<Annotation> annotations() {
		return annotations;
	}

	public List<int[]> guides() {
		return guides;
	}

	public void centreCell(int x, int y, int z) {
		centreCells.add(new int[] {x, y, z});
	}

	public List<int[]> centreCells() {
		return centreCells;
	}

	/** Adds the 1 or 2 centre blocks along each of the given extents (an extent of 0 means "only offset 0"). */
	public void centreCells(int extentX, boolean alignX, int extentY, boolean alignY, int extentZ, boolean alignZ) {
		int[] xs = extentX <= 0 ? new int[] {0} : Centring.centreOffsets(extentX, alignX);
		int[] ys = extentY <= 0 ? new int[] {0} : Centring.centreOffsets(extentY, alignY);
		int[] zs = extentZ <= 0 ? new int[] {0} : Centring.centreOffsets(extentZ, alignZ);

		for (int x : xs) {
			for (int y : ys) {
				for (int z : zs) {
					centreCell(x, y, z);
				}
			}
		}
	}
}
