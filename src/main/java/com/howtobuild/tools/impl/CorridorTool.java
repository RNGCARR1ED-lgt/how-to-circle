package com.howtobuild.tools.impl;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailPreset;
import com.howtobuild.details.DetailSettings;
import com.howtobuild.details.SmoothingPass;
import com.howtobuild.geometry.Centring;
import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.Mask2D;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.ToolParameter;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.ValidationResult;
import com.howtobuild.tools.capability.CommandBuildable;
import com.howtobuild.tools.capability.Detailable;
import com.howtobuild.tools.capability.Dimensionable;
import com.howtobuild.tools.capability.MaterialAssignable;
import com.howtobuild.tools.capability.Mirrorable;

/**
 * Corridor: a cross-section profile ({@code width} across × {@code height} up) extruded along {@code length}.
 *
 * <ul>
 *     <li><b>Arch</b>: vertical walls up to the springline, then a half-ellipse arch with a rise of half the width.</li>
 *     <li><b>Dome</b>: a half-ellipse vault rising directly from the floor.</li>
 *     <li><b>Sphere</b>: a full elliptical (round) tunnel.</li>
 * </ul>
 * Repeated details (arch frames, ribs, keystones, columns, recesses) are distributed evenly along the length every
 * {@code arch_interval} blocks, centred so both ends match.
 */
public final class CorridorTool implements BuildTool, Mirrorable, Detailable, MaterialAssignable, Dimensionable, CommandBuildable, SmoothingPass.Smoothable {
	public enum Profile {
		ARCH,
		DOME,
		SPHERE
	}

	public enum CorridorStyle {
		FILLED,
		HOLLOW,
		SHELL,
		OPEN
	}

	public enum Axis {
		ALONG_Z,
		ALONG_X
	}

	private enum Part {
		NONE,
		LEFT,
		RIGHT,
		CEILING,
		FLOOR
	}

	@Override
	public String id() {
		return "corridor";
	}

	@Override
	public List<ToolParameter> parameters() {
		return List.of(
				ToolParameter.integer("width", 1, 128, 6),
				ToolParameter.integer("height", 1, 128, 10),
				ToolParameter.integer("length", 1, 512, 20),
				ToolParameter.integer("thickness", 1, 16, 1),
				ToolParameter.choice("profile", Profile.ARCH),
				ToolParameter.choice("corridor_style", CorridorStyle.HOLLOW),
				ToolParameter.integer("arch_interval", 2, 64, 5),
				ToolParameter.bool("floor", true).advanced(),
				ToolParameter.bool("ceiling", true).advanced(),
				ToolParameter.bool("left_wall", true).advanced(),
				ToolParameter.bool("right_wall", true).advanced(),
				ToolParameter.choice("axis", Axis.ALONG_Z).section("centre"));
	}

	@Override
	public long estimateBlocks(ToolSettings s) {
		return (long) (s.getInt("width") + 2) * (s.getInt("height") + 2) * s.getInt("length");
	}

	@Override
	public ValidationResult validate(ToolSettings s, GenerationContext ctx) {
		ValidationResult r = new ValidationResult();
		int w = s.getInt("width");
		int h = s.getInt("height");
		int t = s.getInt("thickness");
		r.warnIf(2 * t >= w, "⚠ Wall thickness " + t + " leaves no space inside a " + w + "-wide corridor.");
		r.warnIf(t >= h, "⚠ Wall thickness " + t + " leaves no headroom inside a " + h + "-high corridor.");
		r.warnIf(s.getEnum("profile", Profile.class) == Profile.ARCH && h < (w + 1) / 2,
				"⚠ The corridor is lower than half its width, so the arch is flattened.");
		return r;
	}

	/** Whether cross-section cell (u, v) lies inside the profile of the given size. */
	static boolean inside(Profile profile, int u, int v, int w, int h) {
		if (w <= 0 || h <= 0 || u < 0 || v < 0 || u >= w || v >= h) return false;

		long x = 2L * u + 1 - w;

		return switch (profile) {
			case SPHERE -> com.howtobuild.geometry.Solids.ellipsoid(u, v, 0, w, h, 1);
			case DOME -> halfEllipse(x, v, w, h);
			case ARCH -> {
				int rise = Math.min(h, (w + 1) / 2);
				int spring = h - rise;
				yield v < spring || halfEllipse(x, v - spring, w, rise);
			}
		};
	}

	/** Upper half-ellipse with semi-axes w/2 (doubled x) and {@code rise}, rows counted up from its base. */
	private static boolean halfEllipse(long x, int row, int w, int rise) {
		if (Math.abs(x) <= 1) return true;
		if (row == 0 && Math.abs(x) < w) return true;

		long y = 2L * row + 1;
		long r = 2L * rise;
		return x * x * r * r + y * y * (long) w * w <= (long) w * w * r * r;
	}

	@Override
	public void generate(ToolSettings s, GenerationContext ctx, GeometryBuilder out) {
		int w = s.getInt("width");
		int h = s.getInt("height");
		int length = s.getInt("length");
		int t = s.getInt("thickness");
		Profile profile = s.getEnum("profile", Profile.class);
		CorridorStyle style = s.getEnum("corridor_style", CorridorStyle.class);
		int interval = s.getInt("arch_interval");
		boolean alongZ = s.getEnum("axis", Axis.class) == Axis.ALONG_Z;
		DetailSettings d = ctx.details();

		Mask2D outer = Mask2D.of(w, h, (u, v) -> inside(profile, u, v, w, h));
		// Erosion gives walls exactly t blocks thick along the curve; the floor edge stays open for arch and dome profiles.
		Mask2D interior = outer.eroded(t, profile != Profile.SPHERE);
		int minAcross = Centring.minOffset(w, alongZ ? ctx.alignX() : ctx.alignZ());
		int minAlong = Centring.minOffset(length, alongZ ? ctx.alignZ() : ctx.alignX());
		int archOffset = ((length - 1) % interval) / 2;
		int lowestInner = lowestRow(interior);

		Part[][] parts = new Part[w][h];

		for (int v = 0; v < h; v++) {
			for (int u = 0; u < w; u++) {
				parts[u][v] = classify(profile, style, u, v, w, h, outer, interior, lowestInner, s);
			}
		}

		for (int i = 0; i < length; i++) {
			boolean arch = (i - archOffset) % interval == 0 && i >= archOffset;
			boolean end = i == 0 || i == length - 1;
			int archIndex = (i - archOffset) / interval;
			boolean midBay = Math.floorMod(i - archOffset, interval) == interval / 2 && interval >= 3;
			int segment = Math.floorDiv(i - archOffset, interval);

			for (int v = 0; v < h; v++) {
				for (int u = 0; u < w; u++) {
					Part part = parts[u][v];

					if (part == Part.NONE) continue;

					MaterialRole role = part == Part.FLOOR ? MaterialRole.FLOOR : MaterialRole.PRIMARY;

					if (part == Part.FLOOR && d.has(DetailFeature.FLOOR_BORDER) && (u == t || u == w - 1 - t)) role = MaterialRole.TRIM;
					if ((part == Part.LEFT || part == Part.RIGHT) && d.has(DetailFeature.WALL_PANELS) && (segment & 1) == 1) role = MaterialRole.TRIM;
					if (part == Part.CEILING && d.has(DetailFeature.CEILING_RIBS) && Math.abs(2 * u + 1 - w) <= 1) role = MaterialRole.TRIM;
					if (arch && d.has(DetailFeature.RIBBING) && part != Part.FLOOR) role = MaterialRole.TRIM;

					put(out, alongZ, minAcross + u, v, minAlong + i, role);
				}
			}

			boolean frame = (arch && (d.has(DetailFeature.ARCH_FRAMES) || d.has(DetailFeature.ALTERNATING_ARCHES)))
					|| (end && d.has(DetailFeature.ENTRY_FRAME));

			if (frame) {
				MaterialRole frameRole = d.has(DetailFeature.ALTERNATING_ARCHES) && (archIndex & 1) == 1 ? MaterialRole.ACCENT : MaterialRole.TRIM;

				for (int v = 0; v <= h; v++) {
					for (int u = -1; u <= w; u++) {
						if (inside(profile, u, v, w, h)) continue;

						boolean touches = inside(profile, u - 1, v, w, h) || inside(profile, u + 1, v, w, h)
								|| inside(profile, u, v - 1, w, h);

						if (touches && !(profile == Profile.SPHERE && v == 0)) put(out, alongZ, minAcross + u, v, minAlong + i, frameRole);
					}
				}
			}

			if (arch && d.has(DetailFeature.KEYSTONE)) {
				int top = h - 1;

				while (top >= 0 && !inside(profile, (w - 1) / 2, top, w, h)) top--;

				int keyV = frame ? top + 1 : top;

				for (int u : new int[] {(w - 1) / 2, w / 2}) {
					put(out, alongZ, minAcross + u, keyV, minAlong + i, MaterialRole.ACCENT);
				}
			}

			if (arch && d.has(DetailFeature.SIDE_COLUMNS) && w - 2 * t > 2) {
				int columnTop = profile == Profile.ARCH ? Math.max(1, h - Math.min(h, (w + 1) / 2)) : Math.max(1, (2 * h) / 3);

				for (int v = lowestInner + (s.getBool("floor") ? 1 : 0); v < columnTop; v++) {
					for (int u = 0; u < w; u++) {
						boolean besideWall = interior.contains(u, v) && (!interior.contains(u - 1, v) || !interior.contains(u + 1, v));

						if (besideWall) put(out, alongZ, minAcross + u, v, minAlong + i, MaterialRole.SUPPORT);
					}
				}
			}

			if (midBay && d.has(DetailFeature.LIGHT_RECESSES) && style != CorridorStyle.FILLED) {
				int v = Math.min(Math.max(2, lowestInner + 2), h - 2);

				for (int side = 0; side < 2; side++) {
					int u = side == 0 ? 0 : w - 1;
					int inward = side == 0 ? 1 : -1;

					// Innermost wall cell at this height.
					int wall = u;

					while (wall >= 0 && wall < w && parts[wall][v] != Part.LEFT && parts[wall][v] != Part.RIGHT) wall += inward;

					while (wall + inward >= 0 && wall + inward < w && (parts[wall + inward][v] == Part.LEFT || parts[wall + inward][v] == Part.RIGHT)) {
						wall += inward;
					}

					if (wall < 0 || wall >= w) continue;

					remove(out, alongZ, minAcross + wall, v, minAlong + i);
					put(out, alongZ, minAcross + wall - inward, v, minAlong + i, MaterialRole.ACCENT);
				}
			}
		}

		out.centreCells(alongZ ? w : length, ctx.alignX(), 0, true, alongZ ? length : w, ctx.alignZ());
		out.value("width", w);
		out.value("height", h);
		out.value("length", length);
		out.value("thickness", t);
		out.value("arches", length > archOffset ? (length - 1 - archOffset) / interval + 1 : 0);
	}

	private static int lowestRow(Mask2D mask) {
		for (int v = 0; v < mask.height(); v++) {
			for (int u = 0; u < mask.width(); u++) {
				if (mask.contains(u, v)) return v;
			}
		}

		return 0;
	}

	private static Part classify(Profile profile, CorridorStyle style, int u, int v, int w, int h, Mask2D outer, Mask2D interiorMask,
			int lowestInner, ToolSettings s) {
		if (!outer.contains(u, v)) return Part.NONE;

		boolean interior = interiorMask.contains(u, v);

		if (style == CorridorStyle.FILLED) return Part.CEILING;

		if (interior) {
			boolean floorRow = v == lowestInner;
			return floorRow && style == CorridorStyle.HOLLOW && s.getBool("floor") ? Part.FLOOR : Part.NONE;
		}

		// Shell cell: decide which part of the cross-section it belongs to.
		double cx = u + 0.5 - w / 2.0;
		Part part;

		if (profile == Profile.ARCH && v < h - Math.min(h, (w + 1) / 2)) {
			part = cx < 0 ? Part.LEFT : Part.RIGHT;
		} else {
			double baseV = switch (profile) {
				case ARCH -> h - Math.min(h, (w + 1) / 2);
				case DOME -> 0;
				case SPHERE -> h / 2.0;
			};
			double rise = profile == Profile.SPHERE ? h / 2.0 : Math.max(1, h - baseV);
			double nx = cx / (w / 2.0);
			double ny = (v + 0.5 - baseV) / rise;

			if (ny < 0 && -ny > Math.abs(nx)) part = Part.FLOOR;
			else if (ny > Math.abs(nx)) part = Part.CEILING;
			else part = cx < 0 ? Part.LEFT : Part.RIGHT;
		}

		if (profile == Profile.DOME && v == 0 && part == Part.FLOOR) part = cx < 0 ? Part.LEFT : Part.RIGHT;

		return switch (part) {
			case LEFT -> s.getBool("left_wall") ? Part.LEFT : Part.NONE;
			case RIGHT -> s.getBool("right_wall") ? Part.RIGHT : Part.NONE;
			case CEILING -> style == CorridorStyle.OPEN || !s.getBool("ceiling") ? Part.NONE : Part.CEILING;
			case FLOOR -> style == CorridorStyle.HOLLOW && s.getBool("floor") ? Part.FLOOR : Part.NONE;
			case NONE -> Part.NONE;
		};
	}

	private static void put(GeometryBuilder out, boolean alongZ, int across, int y, int along, MaterialRole role) {
		if (alongZ) out.set(across, y, along, role);
		else out.set(along, y, across, role);
	}

	private static void remove(GeometryBuilder out, boolean alongZ, int across, int y, int along) {
		if (alongZ) out.remove(across, y, along);
		else out.remove(along, y, across);
	}

	@Override
	public int smoothingOpenFaces(ToolSettings settings) {
		boolean alongZ = settings.getEnum("axis", Axis.class) == Axis.ALONG_Z;
		return SmoothingPass.OPEN_POS_Y | (alongZ ? SmoothingPass.OPEN_NEG_X | SmoothingPass.OPEN_POS_X : SmoothingPass.OPEN_NEG_Z | SmoothingPass.OPEN_POS_Z);
	}

	@Override
	public Set<DetailFeature> supportedDetails() {
		return EnumSet.of(DetailFeature.RIBBING, DetailFeature.ARCH_FRAMES, DetailFeature.KEYSTONE, DetailFeature.CEILING_RIBS,
				DetailFeature.SIDE_COLUMNS, DetailFeature.FLOOR_BORDER, DetailFeature.WALL_PANELS, DetailFeature.LIGHT_RECESSES,
				DetailFeature.ALTERNATING_ARCHES, DetailFeature.ENTRY_FRAME, DetailFeature.SMOOTH_CURVES);
	}

	@Override
	public Set<DetailFeature> presetDetails(DetailPreset preset) {
		return switch (preset) {
			case NONE, CUSTOM -> EnumSet.noneOf(DetailFeature.class);
			case SIMPLE -> EnumSet.of(DetailFeature.ARCH_FRAMES);
			case DETAILED -> EnumSet.of(DetailFeature.ARCH_FRAMES, DetailFeature.KEYSTONE, DetailFeature.FLOOR_BORDER, DetailFeature.ENTRY_FRAME);
			case ARCHITECTURAL -> EnumSet.of(DetailFeature.ARCH_FRAMES, DetailFeature.KEYSTONE, DetailFeature.SIDE_COLUMNS,
					DetailFeature.CEILING_RIBS, DetailFeature.FLOOR_BORDER, DetailFeature.ENTRY_FRAME);
			case DECORATIVE -> EnumSet.of(DetailFeature.ALTERNATING_ARCHES, DetailFeature.KEYSTONE, DetailFeature.WALL_PANELS,
					DetailFeature.LIGHT_RECESSES, DetailFeature.FLOOR_BORDER);
		};
	}

	@Override
	public Set<MaterialRole> roles() {
		return EnumSet.of(MaterialRole.PRIMARY, MaterialRole.TRIM, MaterialRole.ACCENT, MaterialRole.SUPPORT, MaterialRole.FLOOR);
	}

	@Override
	public List<String> dimensionKeys() {
		return List.of("width", "height", "length", "thickness", "arches");
	}
}
