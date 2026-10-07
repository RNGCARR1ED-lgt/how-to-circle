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
import com.howtobuild.geometry.Grid3D;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Solids;
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
 * Sphere (or ellipsoid, when the three diameters differ), centred on the anchor in all three axes. Filled or a shell
 * of the given thickness, with optional slicing, an opening and architectural details.
 */
public final class SphereTool implements BuildTool, Mirrorable, Detailable, MaterialAssignable, Dimensionable, CommandBuildable, SmoothingPass.Smoothable {
	public enum Slice {
		NONE,
		TOP_HALF,
		BOTTOM_HALF,
		EAST_HALF,
		SOUTH_HALF
	}

	public enum Opening {
		NONE,
		TOP,
		SIDE,
		BOTTOM
	}

	@Override
	public String id() {
		return "sphere";
	}

	@Override
	public List<ToolParameter> parameters() {
		return List.of(
				ToolParameter.integer("diameter_x", 1, Solids.MAX_EXTENT, 15),
				ToolParameter.integer("diameter_y", 1, Solids.MAX_EXTENT, 15),
				ToolParameter.integer("diameter_z", 1, Solids.MAX_EXTENT, 15),
				ToolParameter.choice("style", Style.HOLLOW),
				ToolParameter.integer("thickness", 1, 64, 1).visibleWhen(s -> s.getEnum("style", Style.class) == Style.HOLLOW),
				ToolParameter.choice("slice", Slice.NONE).advanced(),
				ToolParameter.choice("opening", Opening.NONE).advanced(),
				ToolParameter.integer("opening_size", 1, 256, 5).advanced().visibleWhen(s -> s.getEnum("opening", Opening.class) != Opening.NONE));
	}

	@Override
	public long estimateBlocks(ToolSettings s) {
		long w = s.getInt("diameter_x");
		long h = s.getInt("diameter_y");
		long l = s.getInt("diameter_z");

		if (s.getEnum("style", Style.class) == Style.FILLED) return w * h * l / 2;

		return Math.min(w * h * l / 2, (w * h + h * l + w * l) * 2L * s.getInt("thickness"));
	}

	@Override
	public ValidationResult validate(ToolSettings s, GenerationContext ctx) {
		ValidationResult r = new ValidationResult();
		int t = s.getInt("thickness");
		int min = Math.min(s.getInt("diameter_x"), Math.min(s.getInt("diameter_y"), s.getInt("diameter_z")));
		r.warnIf(s.getEnum("style", Style.class) == Style.HOLLOW && 2 * t >= min, "Shell thickness " + t + " is at least half the smallest diameter, so the sphere is solid.");
		r.warnIf(s.getEnum("opening", Opening.class) != Opening.NONE && s.getInt("opening_size") >= min, "The opening is as wide as the sphere.");
		return r;
	}

	@Override
	public void generate(ToolSettings s, GenerationContext ctx, GeometryBuilder out) {
		int w = s.getInt("diameter_x");
		int h = s.getInt("diameter_y");
		int l = s.getInt("diameter_z");
		int t = s.getInt("thickness");
		boolean hollow = s.getEnum("style", Style.class) == Style.HOLLOW;
		Slice slice = s.getEnum("slice", Slice.class);
		Opening opening = s.getEnum("opening", Opening.class);
		int openingSize = s.getInt("opening_size");
		DetailSettings d = ctx.details();
		int minX = Centring.minOffset(w, ctx.alignX());
		int minY = Centring.minOffset(h, ctx.alignY());
		int minZ = Centring.minOffset(l, ctx.alignZ());
		int meridians = Shapes3D.meridians(Math.max(w, l), d.interval());
		int latitudeSpacing = Math.max(2, d.interval());

		Grid3D solid = new Grid3D(w, h, l);

		for (int j = 0; j < h; j++) {
			for (int k = 0; k < l; k++) {
				for (int i = 0; i < w; i++) {
					if (Solids.ellipsoid(i, j, k, w, h, l)) solid.set(i, j, k);
				}
			}
		}

		Grid3D body = hollow ? solid.shell(t, false) : solid;
		Grid3D outerLayer = solid.shell(1, false);

		for (int j = 0; j < h; j++) {
			long y = Solids.doubled(j, h);

			for (int k = 0; k < l; k++) {
				long z = Solids.doubled(k, l);

				for (int i = 0; i < w; i++) {
					if (!body.get(i, j, k)) continue;

					long x = Solids.doubled(i, w);

					if (!keep(slice, x, y, z) || cut(opening, openingSize, x, y, z)) continue;

					boolean surface = outerLayer.get(i, j, k);
					MaterialRole role = MaterialRole.PRIMARY;

					if (surface) {
						int fromCentre = (int) Math.floorDiv(Math.abs(y) + 1, 2);
						boolean latitude = d.has(DetailFeature.LATITUDE_RINGS) && fromCentre > 0 && fromCentre % latitudeSpacing == 0;
						boolean meridian = d.has(DetailFeature.LONGITUDE_RINGS) && Shapes3D.onMeridian(x, z, meridians);

						if (d.has(DetailFeature.SEGMENTATION)) {
							int band = (int) Math.floorDiv(y + h, 2L * latitudeSpacing);
							int sector = Shapes3D.sector(x, z, meridians);

							if (((band + sector) & 1) == 1) role = MaterialRole.TRIM;
						}

						if (latitude || meridian) role = MaterialRole.TRIM;
						if (latitude && meridian && d.has(DetailFeature.ACCENTS)) role = MaterialRole.ACCENT;
						if (d.has(DetailFeature.CROSS_BANDS) && (Math.abs(x) <= 1 || Math.abs(z) <= 1)) role = MaterialRole.TRIM;
						if (d.has(DetailFeature.EQUATOR_BAND) && Math.abs(y) <= 1) role = MaterialRole.ACCENT;
						if (d.has(DetailFeature.POLE_CAPS) && Math.abs(y) * 100 >= 76L * h) role = MaterialRole.CAP;
					}

					out.set(minX + i, minY + j, minZ + k, role);
				}
			}
		}

		out.centreCells(w, ctx.alignX(), h, ctx.alignY(), l, ctx.alignZ());
		out.value("diameter_x", w);
		out.value("diameter_y", h);
		out.value("diameter_z", l);

		if (w == h && h == l) {
			out.value("diameter", w);
			out.value("radius", w / 2.0);
		}

		out.value("thickness", hollow ? t : Math.min(w, Math.min(h, l)) / 2.0);
	}

	private static boolean keep(Slice slice, long x, long y, long z) {
		return switch (slice) {
			case NONE -> true;
			case TOP_HALF -> y >= -1;
			case BOTTOM_HALF -> y <= 1;
			case EAST_HALF -> x >= -1;
			case SOUTH_HALF -> z >= -1;
		};
	}

	/** Whether the cell is removed by the opening (a round hole of the given diameter through one side). */
	private static boolean cut(Opening opening, int size, long x, long y, long z) {
		long r2 = (long) size * size; // doubled radius squared = size²

		return switch (opening) {
			case NONE -> false;
			case TOP -> y > 0 && x * x + z * z <= r2;
			case BOTTOM -> y < 0 && x * x + z * z <= r2;
			case SIDE -> x > 0 && y * y + z * z <= r2;
		};
	}

	@Override
	public int smoothingOpenFaces(ToolSettings settings) {
		return SmoothingPass.OPEN_ALL;
	}

	@Override
	public Set<DetailFeature> supportedDetails() {
		return EnumSet.of(DetailFeature.LATITUDE_RINGS, DetailFeature.LONGITUDE_RINGS, DetailFeature.EQUATOR_BAND,
				DetailFeature.POLE_CAPS, DetailFeature.CROSS_BANDS, DetailFeature.SEGMENTATION, DetailFeature.ACCENTS,
				DetailFeature.SMOOTH_CURVES);
	}

	@Override
	public Set<DetailFeature> presetDetails(DetailPreset preset) {
		return switch (preset) {
			case NONE, CUSTOM -> EnumSet.noneOf(DetailFeature.class);
			case SIMPLE -> EnumSet.of(DetailFeature.EQUATOR_BAND);
			case DETAILED -> EnumSet.of(DetailFeature.EQUATOR_BAND, DetailFeature.LATITUDE_RINGS, DetailFeature.POLE_CAPS);
			case ARCHITECTURAL -> EnumSet.of(DetailFeature.LATITUDE_RINGS, DetailFeature.LONGITUDE_RINGS, DetailFeature.ACCENTS, DetailFeature.POLE_CAPS);
			case DECORATIVE -> EnumSet.of(DetailFeature.SEGMENTATION, DetailFeature.EQUATOR_BAND, DetailFeature.CROSS_BANDS);
		};
	}

	@Override
	public Set<MaterialRole> roles() {
		return EnumSet.of(MaterialRole.PRIMARY, MaterialRole.TRIM, MaterialRole.ACCENT, MaterialRole.CAP);
	}

	@Override
	public List<String> dimensionKeys() {
		return List.of("diameter", "diameter_x", "diameter_y", "diameter_z", "radius", "thickness");
	}
}
