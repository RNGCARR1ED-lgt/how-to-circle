package com.howtobuild.tools.impl;

import java.util.ArrayList;
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
 * Cylinder: an elliptical cross-section (width × length, the circle generator's exact cells) extruded along a height,
 * standing upright or lying along X or Z. Filled or hollow with a wall thickness; caps can close either end.
 */
public final class CylinderTool implements BuildTool, Mirrorable, Detailable, MaterialAssignable, Dimensionable, CommandBuildable, SmoothingPass.Smoothable {
	public enum Orientation {
		VERTICAL,
		ALONG_X,
		ALONG_Z
	}

	public enum Caps {
		NONE,
		BOTH,
		TOP,
		BOTTOM
	}

	@Override
	public String id() {
		return "cylinder";
	}

	@Override
	public List<ToolParameter> parameters() {
		return List.of(
				ToolParameter.integer("width", 1, Solids.MAX_EXTENT, 11),
				ToolParameter.integer("length", 1, Solids.MAX_EXTENT, 11),
				ToolParameter.integer("height", 1, 384, 12),
				ToolParameter.choice("style", Style.HOLLOW),
				ToolParameter.integer("thickness", 1, 64, 1).visibleWhen(s -> s.getEnum("style", Style.class) == Style.HOLLOW),
				ToolParameter.choice("caps", Caps.NONE).visibleWhen(s -> s.getEnum("style", Style.class) == Style.HOLLOW),
				ToolParameter.choice("orientation", Orientation.VERTICAL).section("centre"));
	}

	@Override
	public long estimateBlocks(ToolSettings s) {
		long w = s.getInt("width");
		long l = s.getInt("length");
		long h = s.getInt("height");

		if (s.getEnum("style", Style.class) == Style.FILLED) return w * l * h;

		return Math.min(w * l * h, (2 * (w + l) * s.getInt("thickness") + w * l) * h);
	}

	@Override
	public ValidationResult validate(ToolSettings s, GenerationContext ctx) {
		ValidationResult r = new ValidationResult();
		int w = s.getInt("width");
		int l = s.getInt("length");
		int t = s.getInt("thickness");
		r.warnIf(s.getEnum("style", Style.class) == Style.HOLLOW && 2 * t >= Math.min(w, l),
				"Wall thickness " + t + " is at least half the diameter, so the cylinder is solid.");
		return r;
	}

	@Override
	public void generate(ToolSettings s, GenerationContext ctx, GeometryBuilder out) {
		int w = s.getInt("width");
		int l = s.getInt("length");
		int h = s.getInt("height");
		int t = s.getInt("thickness");
		boolean hollow = s.getEnum("style", Style.class) == Style.HOLLOW;
		Caps caps = s.getEnum("caps", Caps.class);
		Orientation orientation = s.getEnum("orientation", Orientation.class);
		DetailSettings d = ctx.details();

		Mask2D section = Mask2D.ellipse(w, l);
		Mask2D wall = hollow ? section.ring(t) : section;
		Mask2D hollowPart = section.eroded(t, false);
		Mask2D innerSurface = hollow && t >= 2 ? section.eroded(t - 1, false).minus(hollowPart, 0, 0) : null;
		Mask2D outline = section.outline();
		int meridians = Shapes3D.meridians(Math.max(w, l), d.interval());

		Mapper map = new Mapper(orientation, w, l, h, ctx);

		for (int layer = 0; layer < h; layer++) {
			boolean bottom = layer == 0;
			boolean top = layer == h - 1;
			boolean cap = hollow && ((bottom && (caps == Caps.BOTH || caps == Caps.BOTTOM)) || (top && (caps == Caps.BOTH || caps == Caps.TOP)));
			Mask2D mask = cap ? section : wall;

			for (int v = 0; v < l; v++) {
				for (int u = 0; u < w; u++) {
					if (!mask.contains(u, v)) continue;

					MaterialRole role = MaterialRole.PRIMARY;
					boolean onWall = wall.contains(u, v);

					if (cap && !onWall) role = MaterialRole.CAP;
					else if (innerSurface != null && innerSurface.contains(u, v)) role = MaterialRole.INNER;

					long dx = Solids.doubled(u, w);
					long dz = Solids.doubled(v, l);
					boolean outer = outline.contains(u, v);

					if (onWall && outer) {
						if (d.has(DetailFeature.EDGE_TRIM) && (bottom || top)) role = MaterialRole.TRIM;
						if (d.has(DetailFeature.HORIZONTAL_BANDS) && layer % d.interval() == 0 && !bottom) role = MaterialRole.TRIM;
						if (d.has(DetailFeature.VERTICAL_BANDS) && Shapes3D.onMeridian(dx, dz, meridians)) {
							role = role == MaterialRole.TRIM && d.has(DetailFeature.ACCENTS) ? MaterialRole.ACCENT : MaterialRole.TRIM;
						}
					}

					map.set(out, u, v, layer, role);
				}
			}

			if (d.has(DetailFeature.TOP_RIM) && top) ring(out, map, w, l, layer, MaterialRole.TRIM, true);
			if (d.has(DetailFeature.BOTTOM_RIM) && bottom) ring(out, map, w, l, layer, MaterialRole.TRIM, true);
			if (d.has(DetailFeature.SUPPORT_BANDS) && layer > 0 && layer < h - 1 && layer % (2 * d.interval()) == 0) {
				ring(out, map, w, l, layer, MaterialRole.SUPPORT, true);
			}

			if (d.has(DetailFeature.INNER_RING) && hollow && top && hollowPart.count() > 0) {
				Mask2D inner = hollowPart.outline();

				for (int v = 0; v < inner.height(); v++) {
					for (int u = 0; u < inner.width(); u++) {
						if (inner.contains(u, v)) map.set(out, u, v, layer, MaterialRole.TRIM);
					}
				}
			}
		}

		if (d.has(DetailFeature.ACCENTS) && !d.has(DetailFeature.VERTICAL_BANDS)) {
			for (int layer = d.interval() / 2; layer < h; layer += d.interval()) {
				for (int v = 0; v < l; v++) {
					for (int u = 0; u < w; u++) {
						if (outline.contains(u, v) && wall.contains(u, v) && Shapes3D.onMeridian(Solids.doubled(u, w), Solids.doubled(v, l), meridians)) {
							map.set(out, u, v, layer, MaterialRole.ACCENT);
						}
					}
				}
			}
		}

		map.centre(out);
		out.value("width", w);
		out.value("length", l);
		out.value("height", h);
		out.value("thickness", hollow ? t : Math.min(w, l) / 2.0);

		if (w == l) out.value("diameter", w);
	}

	/** A one-block ring just outside the cross-section at the given layer. */
	private static void ring(GeometryBuilder out, Mapper map, int w, int l, int layer, MaterialRole role, boolean outside) {
		Mask2D rim = Mask2D.ellipse(w + 2, l + 2).outline();

		for (int v = 0; v < rim.height(); v++) {
			for (int u = 0; u < rim.width(); u++) {
				if (rim.contains(u, v)) map.set(out, u - 1, v - 1, layer, role);
			}
		}
	}

	/** Maps (cross-section u, v, layer) to world offsets for the chosen orientation. */
	private static final class Mapper {
		final Orientation orientation;
		final int w;
		final int l;
		final int h;
		final int minU;
		final int minV;
		final int minLayer;
		final GenerationContext ctx;

		Mapper(Orientation orientation, int w, int l, int h, GenerationContext ctx) {
			this.orientation = orientation;
			this.w = w;
			this.l = l;
			this.h = h;
			this.ctx = ctx;

			switch (orientation) {
				case VERTICAL -> {
					minU = Centring.minOffset(w, ctx.alignX());
					minV = Centring.minOffset(l, ctx.alignZ());
					minLayer = 0;
				}
				case ALONG_X -> {
					// Cross-section: u → Z, v → Y (resting on the anchor level); layers run along X.
					minU = Centring.minOffset(w, ctx.alignZ());
					minV = rimLift(ctx);
					minLayer = Centring.minOffset(h, ctx.alignX());
				}
				default -> {
					// Cross-section: u → X, v → Y; layers run along Z.
					minU = Centring.minOffset(w, ctx.alignX());
					minV = rimLift(ctx);
					minLayer = Centring.minOffset(h, ctx.alignZ());
				}
			}
		}

		/**
		 * A lying cylinder rests on the anchor layer. Its rims project one block beyond the round surface, including
		 * downwards, so with rims the body sits one block up and the rim's lowest blocks are the base layer.
		 */
		private static int rimLift(GenerationContext ctx) {
			return ctx.details().has(DetailFeature.TOP_RIM) || ctx.details().has(DetailFeature.BOTTOM_RIM) ? 1 : 0;
		}

		void set(GeometryBuilder out, int u, int v, int layer, MaterialRole role) {
			switch (orientation) {
				case VERTICAL -> out.set(minU + u, layer, minV + v, role);
				case ALONG_X -> out.set(minLayer + layer, minV + v, minU + u, role);
				case ALONG_Z -> out.set(minU + u, minV + v, minLayer + layer, role);
			}
		}

		void centre(GeometryBuilder out) {
			switch (orientation) {
				case VERTICAL -> out.centreCells(w, ctx.alignX(), 0, true, l, ctx.alignZ());
				case ALONG_X -> out.centreCells(h, ctx.alignX(), 0, true, w, ctx.alignZ());
				case ALONG_Z -> out.centreCells(w, ctx.alignX(), 0, true, h, ctx.alignZ());
			}
		}
	}

	@Override
	public int smoothingOpenFaces(ToolSettings settings) {
		return switch (settings.getEnum("orientation", Orientation.class)) {
			case VERTICAL -> SmoothingPass.OPEN_ALL & ~SmoothingPass.OPEN_NEG_Y;
			case ALONG_X -> SmoothingPass.OPEN_POS_Y | SmoothingPass.OPEN_NEG_Z | SmoothingPass.OPEN_POS_Z;
			case ALONG_Z -> SmoothingPass.OPEN_POS_Y | SmoothingPass.OPEN_NEG_X | SmoothingPass.OPEN_POS_X;
		};
	}

	@Override
	public Set<DetailFeature> supportedDetails() {
		return EnumSet.of(DetailFeature.EDGE_TRIM, DetailFeature.HORIZONTAL_BANDS, DetailFeature.VERTICAL_BANDS,
				DetailFeature.TOP_RIM, DetailFeature.BOTTOM_RIM, DetailFeature.INNER_RING, DetailFeature.ACCENTS,
				DetailFeature.SUPPORT_BANDS, DetailFeature.SMOOTH_CURVES);
	}

	@Override
	public Set<DetailFeature> presetDetails(DetailPreset preset) {
		return switch (preset) {
			case NONE, CUSTOM -> EnumSet.noneOf(DetailFeature.class);
			case SIMPLE -> EnumSet.of(DetailFeature.EDGE_TRIM);
			case DETAILED -> EnumSet.of(DetailFeature.EDGE_TRIM, DetailFeature.TOP_RIM, DetailFeature.HORIZONTAL_BANDS);
			case ARCHITECTURAL -> EnumSet.of(DetailFeature.TOP_RIM, DetailFeature.BOTTOM_RIM, DetailFeature.VERTICAL_BANDS, DetailFeature.SUPPORT_BANDS);
			case DECORATIVE -> EnumSet.of(DetailFeature.EDGE_TRIM, DetailFeature.HORIZONTAL_BANDS, DetailFeature.VERTICAL_BANDS, DetailFeature.ACCENTS, DetailFeature.TOP_RIM);
		};
	}

	@Override
	public Set<MaterialRole> roles() {
		return EnumSet.of(MaterialRole.PRIMARY, MaterialRole.TRIM, MaterialRole.ACCENT, MaterialRole.CAP, MaterialRole.INNER, MaterialRole.SUPPORT);
	}

	@Override
	public List<String> dimensionKeys() {
		return new ArrayList<>(List.of("diameter", "width", "length", "height", "thickness"));
	}
}
