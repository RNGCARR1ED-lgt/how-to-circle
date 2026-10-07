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
import com.howtobuild.geometry.Mask2D;
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
 * Dome: the upper half of an ellipsoid with a {@code width × length} footprint and the given height, resting on the
 * anchor level. A hemisphere has height = width / 2; lower is shallow, higher is deep.
 */
public final class DomeTool implements BuildTool, Mirrorable, Detailable, MaterialAssignable, Dimensionable, CommandBuildable, SmoothingPass.Smoothable {
	@Override
	public String id() {
		return "dome";
	}

	@Override
	public List<ToolParameter> parameters() {
		return List.of(
				ToolParameter.integer("width", 1, Solids.MAX_EXTENT, 21),
				ToolParameter.integer("length", 1, Solids.MAX_EXTENT, 21),
				ToolParameter.integer("height", 1, Solids.MAX_EXTENT, 11),
				ToolParameter.choice("style", Style.HOLLOW),
				ToolParameter.integer("thickness", 1, 64, 1).visibleWhen(s -> s.getEnum("style", Style.class) == Style.HOLLOW),
				ToolParameter.bool("floor", false).advanced(),
				ToolParameter.integer("open_top", 0, 256, 0).advanced(),
				ToolParameter.bool("cutaway", false).advanced());
	}

	@Override
	public long estimateBlocks(ToolSettings s) {
		long w = s.getInt("width");
		long h = s.getInt("height");
		long l = s.getInt("length");

		if (s.getEnum("style", Style.class) == Style.FILLED) return w * h * l * 2 / 3;

		return Math.min(w * h * l * 2 / 3, (w * l + 2 * h * (w + l)) * 2L * s.getInt("thickness"));
	}

	@Override
	public ValidationResult validate(ToolSettings s, GenerationContext ctx) {
		ValidationResult r = new ValidationResult();
		int t = s.getInt("thickness");
		int w = s.getInt("width");
		int l = s.getInt("length");
		r.warnIf(s.getEnum("style", Style.class) == Style.HOLLOW && (2 * t >= Math.min(w, l) || t >= s.getInt("height")),
				"Shell thickness " + t + " fills the dome completely.");
		r.warnIf(s.getInt("open_top") >= Math.min(w, l), "The top opening is as wide as the dome.");
		return r;
	}

	@Override
	public void generate(ToolSettings s, GenerationContext ctx, GeometryBuilder out) {
		int w = s.getInt("width");
		int l = s.getInt("length");
		int h = s.getInt("height");
		int t = s.getInt("thickness");
		boolean hollow = s.getEnum("style", Style.class) == Style.HOLLOW;
		boolean floor = s.getBool("floor");
		int oculus = s.getInt("open_top");
		boolean cutaway = s.getBool("cutaway");
		DetailSettings d = ctx.details();
		int minX = Centring.minOffset(w, ctx.alignX());
		int minZ = Centring.minOffset(l, ctx.alignZ());
		int meridians = Shapes3D.meridians(Math.max(w, l), d.interval());
		int crownStart = h - Math.max(1, h / 6);

		Grid3D solid = new Grid3D(w, h, l);

		for (int j = 0; j < h; j++) {
			for (int k = 0; k < l; k++) {
				for (int i = 0; i < w; i++) {
					if (Solids.dome(i, j, k, w, h, l)) solid.set(i, j, k);
				}
			}
		}

		// The base is open: erosion treats the ground under the dome as solid, so no floor appears unless requested.
		Grid3D body = hollow ? solid.shell(t, true) : solid;
		Grid3D outerLayer = solid.shell(1, true);

		for (int j = 0; j < h; j++) {
			for (int k = 0; k < l; k++) {
				long z = Solids.doubled(k, l);

				for (int i = 0; i < w; i++) {
					if (!solid.get(i, j, k)) continue;

					boolean shell = body.get(i, j, k);
					boolean isFloor = floor && j == 0;

					if (!shell && !isFloor) continue;

					long x = Solids.doubled(i, w);

					if (oculus > 0 && x * x + z * z <= (long) oculus * oculus && j >= h / 2) continue;
					if (cutaway && x > 1 && z > 1) continue;

					MaterialRole role = MaterialRole.PRIMARY;
					boolean surface = outerLayer.get(i, j, k);

					if (surface) {
						boolean ring = d.has(DetailFeature.HORIZONTAL_BANDS) && j > 0 && j % d.interval() == 0;
						boolean rib = (d.has(DetailFeature.RADIAL_RIBS) || (d.has(DetailFeature.VERTICAL_BANDS) && j < (2 * h) / 3))
								&& Shapes3D.onMeridian(x, z, meridians);

						if (d.has(DetailFeature.ALTERNATING) && (Shapes3D.sector(x, z, meridians) & 1) == 1) role = MaterialRole.ACCENT;
						if (d.has(DetailFeature.SEGMENTATION) && ((Shapes3D.sector(x, z, meridians) + j / Math.max(2, d.interval())) & 1) == 1) role = MaterialRole.TRIM;
						if (ring || rib) role = MaterialRole.TRIM;
						if (ring && rib && d.has(DetailFeature.ACCENTS)) role = MaterialRole.ACCENT;
						if (d.has(DetailFeature.CROWN) && j >= crownStart) role = MaterialRole.CAP;
					}

					out.set(minX + i, j, minZ + k, isFloor && !shell ? MaterialRole.FLOOR : role);
				}
			}
		}

		if (d.has(DetailFeature.CROWN) && oculus == 0) {
			// A finial on the very top.
			for (int[] c : centreColumns(w, l, ctx)) {
				out.set(c[0], h, c[1], MaterialRole.ACCENT);
			}
		}

		if (d.has(DetailFeature.BASE_RING)) {
			Mask2D rim = Mask2D.ellipse(w + 2, l + 2).outline();

			for (int v = 0; v < rim.height(); v++) {
				for (int u = 0; u < rim.width(); u++) {
					if (rim.contains(u, v) && !(cutaway && Solids.doubled(u - 1, w) > 1 && Solids.doubled(v - 1, l) > 1)) {
						out.set(minX + u - 1, 0, minZ + v - 1, MaterialRole.TRIM);
					}
				}
			}
		}

		out.centreCells(w, ctx.alignX(), 0, true, l, ctx.alignZ());
		out.value("width", w);
		out.value("length", l);
		out.value("height", h);
		out.value("thickness", hollow ? t : h);

		if (w == l) {
			out.value("diameter", w);
			out.value("radius", w / 2.0);
		}
	}

	private static List<int[]> centreColumns(int w, int l, GenerationContext ctx) {
		int[] xs = Centring.centreOffsets(w, ctx.alignX());
		int[] zs = Centring.centreOffsets(l, ctx.alignZ());
		java.util.ArrayList<int[]> list = new java.util.ArrayList<>();

		for (int x : xs) {
			for (int z : zs) {
				list.add(new int[] {x, z});
			}
		}

		return list;
	}

	@Override
	public int smoothingOpenFaces(ToolSettings settings) {
		return SmoothingPass.OPEN_ALL & ~SmoothingPass.OPEN_NEG_Y;
	}

	@Override
	public Set<DetailFeature> supportedDetails() {
		return EnumSet.of(DetailFeature.HORIZONTAL_BANDS, DetailFeature.RADIAL_RIBS, DetailFeature.VERTICAL_BANDS,
				DetailFeature.CROWN, DetailFeature.BASE_RING, DetailFeature.ACCENTS, DetailFeature.ALTERNATING,
				DetailFeature.SEGMENTATION, DetailFeature.SMOOTH_CURVES);
	}

	@Override
	public Set<DetailFeature> presetDetails(DetailPreset preset) {
		return switch (preset) {
			case NONE, CUSTOM -> EnumSet.noneOf(DetailFeature.class);
			case SIMPLE -> EnumSet.of(DetailFeature.BASE_RING);
			case DETAILED -> EnumSet.of(DetailFeature.RADIAL_RIBS, DetailFeature.CROWN, DetailFeature.BASE_RING);
			case ARCHITECTURAL -> EnumSet.of(DetailFeature.RADIAL_RIBS, DetailFeature.HORIZONTAL_BANDS, DetailFeature.CROWN,
					DetailFeature.BASE_RING, DetailFeature.ACCENTS);
			case DECORATIVE -> EnumSet.of(DetailFeature.SEGMENTATION, DetailFeature.CROWN, DetailFeature.BASE_RING);
		};
	}

	@Override
	public Set<MaterialRole> roles() {
		return EnumSet.of(MaterialRole.PRIMARY, MaterialRole.TRIM, MaterialRole.ACCENT, MaterialRole.CAP, MaterialRole.FLOOR);
	}

	@Override
	public List<String> dimensionKeys() {
		return List.of("diameter", "width", "length", "height", "thickness");
	}
}
