package com.howtobuild.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import com.howtobuild.client.BuildSession;
import com.howtobuild.config.LabelSettings;
import com.howtobuild.config.MaterialSlot;
import com.howtobuild.dimensions.ConnectedSection;
import com.howtobuild.dimensions.DimensionFormat;
import com.howtobuild.dimensions.DimensionFormatter;
import com.howtobuild.dimensions.LabelComponent;
import com.howtobuild.dimensions.SectionDetector;
import com.howtobuild.geometry.Box;
import com.howtobuild.geometry.BoxDecomposer;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.Grid2D;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.Voxels;
import com.howtobuild.materials.MaterialResolver;
import com.howtobuild.tools.BuildTool;

/**
 * All labels of the current preview, built from the generated block coordinates (never from tool parameters), so a
 * label can never disagree with the geometry. Rebuilt immediately when the geometry or any label setting changes.
 *
 * <ul>
 *     <li><b>Section labels</b>: one per connected section. Flat shapes use the 2D connected-section detector (runs and
 *     stacked rectangles); volumes use exact 3D boxes per material role, labelling sections at least
 *     {@code minSectionSize} long.</li>
 *     <li><b>Dimension lines</b> (flat shapes): CAD-style lines with end ticks beside each section.</li>
 *     <li><b>Overall dimensions</b>: width (X), length (Z) and height (Y) of the whole preview, each naming its axis.</li>
 *     <li><b>Summary</b>: the components the player enabled (tool, sizes, radius, steps, material, …).</li>
 * </ul>
 */
public final class LabelSet {
	public static final int SECTION = 0;
	public static final int LINE = 1;
	public static final int OVERALL = 2;
	public static final int SUMMARY = 3;
	private static final float GAP = 0.3F;
	private static final int MAX_SECTIONS = 1500;
	/** Floats per dimension line: start xyz, end xyz, along axis, across axis (-1 for 3D lines), outward sign. */
	public static final int LINE_STRIDE = 9;

	public final int count;
	/** Label anchor positions relative to the origin, 3 floats each. */
	public final float[] positions;
	public final FormattedCharSequence[] texts;
	/** The same texts as plain strings (for tests and accessibility). */
	public final String[] plainTexts;
	public final int[] widths;
	public final int[] kinds;
	public final float[] priorities;
	/** For section labels: the horizontal axis along which the section is longest (0 = X, 2 = Z), for fixed orientation. */
	public final int[] runAxis;
	/** Bounds of each section label's box (6 ints each) for placement options. */
	public final int[] boxes;

	/**
	 * Dimension lines relative to the origin, {@link #LINE_STRIDE} floats each. For flat shapes the coordinate on the
	 * plane's normal axis is NaN and is chosen per frame: the face of the shape that points towards the camera.
	 */
	public final float[] lines;
	public final int lineCount;
	/** For flat shapes: the normal axis and the low / high coordinates of the slab the lines lie on. */
	public final int planeNormalAxis;
	public final float planeLow;
	public final float planeHigh;
	public final float centreX;
	public final float centreY;
	public final float centreZ;

	private LabelSet(Builder b, int planeNormalAxis, float planeLow, float planeHigh, float cx, float cy, float cz) {
		this.count = b.count;
		this.positions = flatten(b.positions, 3);
		this.texts = b.texts.toArray(new FormattedCharSequence[0]);
		this.plainTexts = b.plain.toArray(new String[0]);
		this.widths = b.widths.toIntArray();
		this.kinds = b.kinds.toIntArray();
		this.priorities = b.priorities.toFloatArray();
		this.runAxis = b.runAxis.toIntArray();
		this.boxes = b.boxes.toIntArray();
		this.lines = flatten(b.lines, LINE_STRIDE);
		this.lineCount = b.lines.size();
		this.planeNormalAxis = planeNormalAxis;
		this.planeLow = planeLow;
		this.planeHigh = planeHigh;
		this.centreX = cx;
		this.centreY = cy;
		this.centreZ = cz;
	}

	private static float[] flatten(List<float[]> list, int stride) {
		float[] result = new float[list.size() * stride];

		for (int i = 0; i < list.size(); i++) {
			System.arraycopy(list.get(i), 0, result, i * stride, stride);
		}

		return result;
	}

	public static LabelSet build(BuildSession.Resolved resolved, BuildTool tool, LabelSettings settings, Map<MaterialRole, MaterialSlot> materials) {
		GeometryResult result = resolved.result();
		Builder b = new Builder(Minecraft.getInstance().font);
		DimensionFormatter.Style style = new DimensionFormatter.Style(settings.format, settings.order, settings.template, settings.units, settings.decimals);
		List<Placement> originals = result.placements().stream().filter(p -> !p.mirrored()).toList();
		Box all = result.bounds();

		if (all == null || originals.isEmpty()) return new LabelSet(b, -1, 0, 0, 0, 0, 0);

		float cx = (all.minX() + all.maxX() + 1) / 2F;
		float cy = (all.minY() + all.maxY() + 1) / 2F;
		float cz = (all.minZ() + all.maxZ() + 1) / 2F;
		int normal = result.planeNormalAxis();
		Map<String, String> extra = new HashMap<>();
		extra.put("tool", Component.translatable(tool.translationKey()).getString());
		extra.put("material", materialName(materials, MaterialRole.PRIMARY));

		for (Map.Entry<String, Double> v : result.values().entrySet()) {
			extra.put(v.getKey(), DimensionFormatter.number(v.getValue(), style));
		}

		float planeLow = 0;
		float planeHigh = 0;

		if (normal >= 0) {
			planeLow = minAlong(originals, normal);
			planeHigh = planeLow + 1;
			planarSections(b, originals, normal, settings, style, extra);
		} else {
			volumeSections(b, originals, settings, style, extra, materials);
		}

		if (settings.showOverall) overall(b, all, normal, settings, style);
		if (settings.showSummary) summary(b, result, all, settings, style, extra, materials);

		return new LabelSet(b, normal, planeLow, planeHigh, cx, cy, cz);
	}

	private static int minAlong(List<Placement> placements, int axis) {
		int min = Integer.MAX_VALUE;

		for (Placement p : placements) {
			min = Math.min(min, axis == 0 ? p.x() : axis == 1 ? p.y() : p.z());
		}

		return min;
	}

	private static int coord(Placement p, int axis) {
		return axis == 0 ? p.x() : axis == 1 ? p.y() : p.z();
	}

	/** Flat shapes: 2D connected sections in the shape's plane, with popups and CAD dimension lines. */
	private static void planarSections(Builder b, List<Placement> placements, int normal, LabelSettings settings,
			DimensionFormatter.Style style, Map<String, String> extra) {
		int axisA = normal == 0 ? 2 : 0;
		int axisB = normal == 1 ? 2 : 1;
		int minA = Integer.MAX_VALUE;
		int minB = Integer.MAX_VALUE;
		int maxA = Integer.MIN_VALUE;
		int maxB = Integer.MIN_VALUE;
		LongOpenHashSet cells = new LongOpenHashSet();

		for (Placement p : placements) {
			int a = coord(p, axisA);
			int bb = coord(p, axisB);
			minA = Math.min(minA, a);
			maxA = Math.max(maxA, a);
			minB = Math.min(minB, bb);
			maxB = Math.max(maxB, bb);
			cells.add(((long) a << 32) | (bb & 0xFFFFFFFFL));
		}

		final int fMinA = minA;
		final int fMinB = minB;
		final int w = maxA - minA + 1;
		final int h = maxB - minB + 1;
		int plane = coord(placements.getFirst(), normal);
		Grid2D grid = new Grid2D() {
			@Override
			public int width() {
				return w;
			}

			@Override
			public int height() {
				return h;
			}

			@Override
			public boolean contains(int u, int v) {
				return u >= 0 && v >= 0 && u < w && v < h && cells.contains(((long) (u + fMinA) << 32) | ((v + fMinB) & 0xFFFFFFFFL));
			}
		};

		DimensionFormatter.Style lineStyle = settings.showPopups
				? new DimensionFormatter.Style(DimensionFormat.SIMPLIFIED, style.order(), style.template(), style.units(), style.decimals())
				: style;

		for (ConnectedSection s : SectionDetector.detect(grid)) {
			int a0 = minA + s.u();
			int a1 = a0 + s.width() - 1;
			int b0 = minB + s.v();
			int b1 = b0 + s.height() - 1;
			Box box = box(axisA, a0, a1, axisB, b0, b1, normal, plane, plane);
			String text = DimensionFormatter.format(box, normal, style, extra);

			if (settings.showPopups) {
				b.section(box, text, s.blockCount(), axisA == 0 && s.width() >= s.height() || axisA == 2 && s.height() > s.width() ? 0 : 2);
			}

			if (!settings.showDimensions) continue;

			// Dimension lines on the side of the section facing away from the shape centre.
			boolean minusB = 2 * s.v() + s.height() < h;
			boolean minusA = 2 * s.u() + s.width() < w;
			float lineB = minusB ? b0 - GAP : b1 + 1 + GAP;
			float lineA = minusA ? a0 - GAP : a1 + 1 + GAP;

			switch (s.kind()) {
				case SINGLE -> {
					if (!settings.showPopups) b.line(axisA, a0, a1 + 1, axisB, lineB, minusB ? -1 : 1, normal, text);
				}
				case LINE_ALONG_WIDTH -> b.line(axisA, a0, a1 + 1, axisB, lineB, minusB ? -1 : 1, normal,
						DimensionFormatter.format(box, normal, lineStyle, extra));
				case LINE_ALONG_HEIGHT -> b.line(axisB, b0, b1 + 1, axisA, lineA, minusA ? -1 : 1, normal,
						DimensionFormatter.format(box, normal, lineStyle, extra));
				case RECTANGLE -> {
					b.line(axisA, a0, a1 + 1, axisB, lineB, minusB ? -1 : 1, normal, DimensionFormatter.number(s.width(), style));
					b.line(axisB, b0, b1 + 1, axisA, lineA, minusA ? -1 : 1, normal, DimensionFormatter.number(s.height(), style));
				}
			}
		}
	}

	private static Box box(int axisA, int a0, int a1, int axisB, int b0, int b1, int axisN, int n0, int n1) {
		int[] min = new int[3];
		int[] max = new int[3];
		min[axisA] = a0;
		max[axisA] = a1;
		min[axisB] = b0;
		max[axisB] = b1;
		min[axisN] = n0;
		max[axisN] = n1;
		return new Box(min[0], min[1], min[2], max[0], max[1], max[2]);
	}

	/** Volumes: exact 3D boxes per material role; only sections of a useful size get a label. */
	private static void volumeSections(Builder b, List<Placement> placements, LabelSettings settings, DimensionFormatter.Style style,
			Map<String, String> extra, Map<MaterialRole, MaterialSlot> materials) {
		if (!settings.showPopups) return;

		Map<MaterialRole, LongOpenHashSet> byRole = new EnumMap<>(MaterialRole.class);

		for (Placement p : placements) {
			byRole.computeIfAbsent(p.role(), r -> new LongOpenHashSet()).add(Voxels.pack(p.x(), p.y(), p.z()));
		}

		List<Object[]> candidates = new ArrayList<>();

		for (Map.Entry<MaterialRole, LongOpenHashSet> e : byRole.entrySet()) {
			for (Box box : BoxDecomposer.decompose(e.getValue())) {
				if (box.longestSide() >= settings.minSectionSize) candidates.add(new Object[] {box, e.getKey()});
			}
		}

		candidates.sort(Comparator.comparingLong((Object[] o) -> -((Box) o[0]).volume()));

		for (int i = 0; i < Math.min(MAX_SECTIONS, candidates.size()); i++) {
			Box box = (Box) candidates.get(i)[0];
			MaterialRole role = (MaterialRole) candidates.get(i)[1];
			Map<String, String> local = new HashMap<>(extra);
			local.put("material", materialName(materials, role));
			local.put("section", role.key());
			b.section(box, DimensionFormatter.format(box, -1, style, local), box.volume(), box.sizeX() >= box.sizeZ() ? 0 : 2);
		}
	}

	private static void overall(Builder b, Box all, int normal, LabelSettings settings, DimensionFormatter.Style style) {
		float x0 = all.minX();
		float x1 = all.maxX() + 1;
		float y0 = all.minY();
		float y1 = all.maxY() + 1;
		float z0 = all.minZ();
		float z1 = all.maxZ() + 1;
		float d = 0.8F;
		boolean named = settings.format == DimensionFormat.NAMED;

		String w = (named ? "WIDTH: " : "W ") + all.sizeX() + style.units();
		String l = (named ? "LENGTH: " : "L ") + all.sizeZ() + style.units();
		String h = (named ? "HEIGHT: " : "H ") + all.sizeY() + style.units();

		// Width along the north edge, length along the west edge, height up the north-west corner.
		if (normal != 0 || all.sizeX() > 1) b.overallLine(0, x0, x1, x0, y0, z0 - d, w);
		if (normal != 2 || all.sizeZ() > 1) b.overallLine(2, z0, z1, x0 - d, y0, z0, l);
		if (all.sizeY() > 1) b.overallLine(1, y0, y1, x0 - d, y0, z0 - d, h);
	}

	private static void summary(Builder b, GeometryResult result, Box all, LabelSettings settings, DimensionFormatter.Style style,
			Map<String, String> extra, Map<MaterialRole, MaterialSlot> materials) {
		List<String> parts = new ArrayList<>();
		Map<String, Double> v = result.values();

		for (LabelComponent c : settings.components) {
			switch (c) {
				case TOOL -> parts.add(extra.get("tool"));
				case WIDTH -> parts.add("W " + all.sizeX() + style.units());
				case HEIGHT -> {
					if (all.sizeY() > 1) parts.add("H " + all.sizeY() + style.units());
				}
				case LENGTH -> parts.add("L " + all.sizeZ() + style.units());
				case THICKNESS -> value(parts, v, "thickness", "T ", style);
				case RADIUS -> value(parts, v, "radius", "R ", style);
				case DIAMETER -> value(parts, v, "diameter", "Ø ", style);
				case STEPS -> {
					if (v.containsKey("steps")) parts.add(DimensionFormatter.number(v.get("steps"), style) + " steps");
				}
				case REVOLUTIONS -> {
					if (v.containsKey("revolutions")) parts.add(DimensionFormatter.number(v.get("revolutions"), style) + " turns");
				}
				case MATERIAL -> parts.add(materialName(materials, MaterialRole.PRIMARY));
				case SECTION -> parts.add(result.blockCount() + " blocks");
			}
		}

		if (parts.isEmpty()) return;

		b.add(new float[] {(all.minX() + all.maxX() + 1) / 2F, all.maxY() + 1 + settings.offset + 0.9F, (all.minZ() + all.maxZ() + 1) / 2F},
				String.join(" · ", parts), SUMMARY, 2e9F, 0, new int[] {all.minX(), all.minY(), all.minZ(), all.maxX(), all.maxY(), all.maxZ()});
	}

	private static void value(List<String> parts, Map<String, Double> values, String key, String prefix, DimensionFormatter.Style style) {
		if (values.containsKey(key)) parts.add(prefix + DimensionFormatter.number(values.get(key), style));
	}

	private static String materialName(Map<MaterialRole, MaterialSlot> materials, MaterialRole role) {
		MaterialSlot slot = materials.get(role);
		return slot == null ? "" : MaterialResolver.block(slot.block).map(block -> block.getName().getString()).orElse(slot.block);
	}

	private static final class Builder {
		final Font font;
		int count;
		final List<float[]> positions = new ArrayList<>();
		final List<FormattedCharSequence> texts = new ArrayList<>();
		final List<String> plain = new ArrayList<>();
		final IntArrayList widths = new IntArrayList();
		final IntArrayList kinds = new IntArrayList();
		final FloatArrayList priorities = new FloatArrayList();
		final IntArrayList runAxis = new IntArrayList();
		final IntArrayList boxes = new IntArrayList();
		final List<float[]> lines = new ArrayList<>();

		Builder(Font font) {
			this.font = font;
		}

		void add(float[] pos, String text, int kind, float priority, int axis, int[] box) {
			positions.add(pos);
			texts.add(Component.literal(text).getVisualOrderText());
			plain.add(text);
			widths.add(font.width(text));
			kinds.add(kind);
			priorities.add(priority);
			runAxis.add(axis);
			boxes.addElements(boxes.size(), box, 0, 6);
			count++;
		}

		void section(Box box, String text, long blocks, int axis) {
			add(new float[] {(box.minX() + box.maxX() + 1) / 2F, box.maxY() + 1, (box.minZ() + box.maxZ() + 1) / 2F}, text, SECTION,
					1e6F + blocks, axis, new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
		}

		/** A CAD dimension line along {@code along} from {@code from} to {@code to}, offset across at {@code pos}. */
		void line(int along, float from, float to, int across, float pos, int outward, int normal, String text) {
			float[] a = new float[3];
			float[] c = new float[3];
			a[along] = from;
			c[along] = to;
			a[across] = pos;
			c[across] = pos;
			a[normal] = Float.NaN; // filled in at render time with the face towards the camera
			c[normal] = Float.NaN;
			lines.add(new float[] {a[0], a[1], a[2], c[0], c[1], c[2], along, across, outward});
			float[] mid = {(a[0] + c[0]) / 2, (a[1] + c[1]) / 2, (a[2] + c[2]) / 2};
			mid[across] += outward * 0.35F;
			add(mid, text, LINE, 1000 + Math.abs(to - from), along, new int[6]);
		}

		/** A 3D measurement line along {@code along} from {@code from} to {@code to}, through the point (x, y, z). */
		void overallLine(int along, float from, float to, float x, float y, float z, String text) {
			float[] a = {x, y, z};
			float[] c = {x, y, z};
			a[along] = from;
			c[along] = to;
			lines.add(new float[] {a[0], a[1], a[2], c[0], c[1], c[2], along, -1, 0});
			add(new float[] {(a[0] + c[0]) / 2, (a[1] + c[1]) / 2 + (along == 1 ? 0 : 0.35F), (a[2] + c[2]) / 2}, text, OVERALL, 1e9F, along, new int[6]);
		}
	}
}
