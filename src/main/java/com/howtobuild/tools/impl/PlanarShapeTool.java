package com.howtobuild.tools.impl;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.howtobuild.details.DetailFeature;
import com.howtobuild.details.DetailPreset;
import com.howtobuild.geometry.CentreSize;
import com.howtobuild.geometry.GeometryBuilder;
import com.howtobuild.geometry.GeometryCenter;
import com.howtobuild.geometry.Mask2D;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.ResolvedDimensions;
import com.howtobuild.geometry.ShapePlacement;
import com.howtobuild.geometry.ShapeType;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.Plane;
import com.howtobuild.tools.ToolParameter;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.ValidationResult;
import com.howtobuild.tools.VerticalAnchor;
import com.howtobuild.tools.capability.CommandBuildable;
import com.howtobuild.tools.capability.Detailable;
import com.howtobuild.tools.capability.Dimensionable;
import com.howtobuild.tools.capability.MaterialAssignable;
import com.howtobuild.tools.capability.Mirrorable;
import com.howtobuild.tools.capability.Rotatable;

/**
 * Shared implementation of the one-block-thick shapes (circle, oval, square, rectangle).
 *
 * <p>Each subclass provides the filled cross-section for given extents; this class handles the fill mode (filled,
 * outline, ring of a given thickness), the centre rule (1 block for odd sizes, 2 for even, forced 1×1 or 2×2 by growing
 * the size), floor or wall orientation, and details. The original How to Circle behaviour is exactly the circle and
 * oval tools with {@code fill = FILLED / OUTLINE}.
 */
public abstract class PlanarShapeTool implements BuildTool, Mirrorable, Rotatable, Detailable, MaterialAssignable, Dimensionable, CommandBuildable {
	public enum Fill {
		FILLED,
		OUTLINE,
		RING
	}

	/** Filled cross-section with exactly these extents. */
	protected abstract Mask2D mask(int width, int length);

	/** Whether width and length are one value (circle, square). */
	protected abstract boolean uniform();

	/** Shape type used for the centre rule (uniform shapes keep both sides equal when adjusting parity). */
	protected ShapeType shapeType() {
		return uniform() ? ShapeType.CIRCLE : ShapeType.OVAL;
	}

	protected List<ToolParameter> sizeParameters() {
		if (uniform()) {
			return List.of(ToolParameter.integer("size", 1, ResolvedDimensions.MAX_SIZE, 15));
		}

		return List.of(ToolParameter.integer("width", 1, ResolvedDimensions.MAX_SIZE, 21),
				ToolParameter.integer("length", 1, ResolvedDimensions.MAX_SIZE, 13));
	}

	@Override
	public List<ToolParameter> parameters() {
		List<ToolParameter> list = new ArrayList<>(sizeParameters());
		list.add(ToolParameter.choice("fill", Fill.OUTLINE));
		list.add(ToolParameter.integer("thickness", 1, 256, 2).visibleWhen(s -> s.getEnum("fill", Fill.class) == Fill.RING));
		list.add(ToolParameter.choice("centre_size", CentreSize.AUTO).section("centre"));
		list.add(ToolParameter.choice("plane", Plane.FLOOR).section("centre"));
		return list;
	}

	protected int width(ToolSettings s) {
		return uniform() ? s.getInt("size") : s.getInt("width");
	}

	protected int length(ToolSettings s) {
		return uniform() ? s.getInt("size") : s.getInt("length");
	}

	public ResolvedDimensions dimensions(ToolSettings s) {
		return ResolvedDimensions.resolve(shapeType(), width(s), length(s), s.getEnum("centre_size", CentreSize.class));
	}

	@Override
	public ValidationResult validate(ToolSettings settings, GenerationContext context) {
		ValidationResult result = new ValidationResult();
		String incompatible = GeometryCenter.incompatibility(settings.getEnum("centre_size", CentreSize.class), width(settings), length(settings));

		if (incompatible != null) return result.error(incompatible);

		ResolvedDimensions dims = dimensions(settings);
		dims.notes().forEach(result::warn);

		if (settings.getEnum("fill", Fill.class) == Fill.RING) {
			int t = settings.getInt("thickness");
			result.warnIf(2 * t >= Math.min(dims.width(), dims.height()), "Ring thickness " + t + " fills the whole shape (it is wider than half the size).");
		}

		return result;
	}

	@Override
	public VerticalAnchor verticalAnchor(ToolSettings settings) {
		return settings.getEnum("plane", Plane.class) == Plane.FLOOR ? VerticalAnchor.BASE : VerticalAnchor.CENTRE;
	}

	@Override
	public int planeNormalAxis(ToolSettings settings, GenerationContext context) {
		if (settings.getEnum("plane", Plane.class) == Plane.FLOOR) return 1;
		return (context.rotation() & 1) == 1 ? 0 : 2;
	}

	protected ShapePlacement placement(ToolSettings s, GenerationContext ctx) {
		boolean floor = s.getEnum("plane", Plane.class) == Plane.FLOOR;
		return new ShapePlacement(floor ? ShapePlacement.Plane.HORIZONTAL : ShapePlacement.Plane.VERTICAL, false,
				ctx.alignX(), floor ? ctx.alignZ() : ctx.alignY(), 0);
	}

	/** The cells to build, before details. */
	public Mask2D shapeMask(ToolSettings s) {
		ResolvedDimensions dims = dimensions(s);
		Mask2D filled = mask(dims.width(), dims.height());

		return switch (s.getEnum("fill", Fill.class)) {
			case FILLED -> filled;
			case OUTLINE -> filled.outline();
			case RING -> filled.ring(s.getInt("thickness"));
		};
	}

	@Override
	public void generate(ToolSettings s, GenerationContext ctx, GeometryBuilder out) {
		ResolvedDimensions dims = dimensions(s);
		int w = dims.width();
		int h = dims.height();
		ShapePlacement placement = placement(s, ctx);
		Mask2D shape = shapeMask(s);

		for (int v = 0; v < h; v++) {
			for (int u = 0; u < w; u++) {
				if (shape.contains(u, v)) put(out, placement, u, v, w, h, MaterialRole.PRIMARY);
			}
		}

		applyDetails(s, ctx, out, placement, shape, w, h);

		for (int v = 0; v < h; v++) {
			for (int u = 0; u < w; u++) {
				if (Math.abs(2 * u + 1 - w) <= 1 && Math.abs(2 * v + 1 - h) <= 1) {
					int[] o = placement.offset(u, v, w, h);
					out.centreCell(o[0], o[1], o[2]);
				}
			}
		}

		Fill fill = s.getEnum("fill", Fill.class);
		out.value("width", w);
		out.value("length", h);

		if (uniform()) out.value("diameter", w);

		out.value("thickness", fill == Fill.RING ? s.getInt("thickness") : fill == Fill.OUTLINE ? 1 : Math.min(w, h) / 2.0);
	}

	protected void applyDetails(ToolSettings s, GenerationContext ctx, GeometryBuilder out, ShapePlacement placement, Mask2D shape, int w, int h) {
		var details = ctx.details();
		Mask2D boundary = shape.outline();

		if (details.has(DetailFeature.EDGE_TRIM)) {
			forEach(boundary, (u, v) -> put(out, placement, u, v, w, h, MaterialRole.TRIM));
		}

		if (details.has(DetailFeature.INNER_RING)) {
			Mask2D inner = shape.minus(boundary, 0, 0).outline();
			forEach(inner, (u, v) -> put(out, placement, u, v, w, h, MaterialRole.TRIM));
		}

		if (details.has(DetailFeature.OUTER_RING)) {
			Mask2D outer = mask(w + 2, h + 2).outline();
			forEach(outer, (u, v) -> put(out, placement, u - 1, v - 1, w, h, MaterialRole.TRIM));
		}

		if (details.has(DetailFeature.ALTERNATING)) {
			int sectors = Math.max(4, 2 * Math.round((w + h) * 1.57f / Math.max(2, details.interval()) / 2f));
			forEach(shape, (u, v) -> {
				double angle = Math.atan2(2 * v + 1 - h, 2 * u + 1 - w) + Math.PI;

				if (((int) (angle / (2 * Math.PI) * sectors)) % 2 == 1) put(out, placement, u, v, w, h, MaterialRole.ACCENT);
			});
		}

		if (details.has(DetailFeature.ACCENTS)) {
			List<int[]> perimeter = new ArrayList<>();
			forEach(boundary, (u, v) -> perimeter.add(new int[] {u, v}));
			perimeter.sort((a, b) -> Double.compare(angle(a, w, h), angle(b, w, h)));

			for (int i = 0; i < perimeter.size(); i += details.interval()) {
				put(out, placement, perimeter.get(i)[0], perimeter.get(i)[1], w, h, MaterialRole.ACCENT);
			}
		}

		if (details.has(DetailFeature.CORNER_ACCENTS)) {
			int[][] corners = {{0, 0}, {w - 1, 0}, {0, h - 1}, {w - 1, h - 1}};

			for (int[] c : corners) {
				if (shape.contains(c[0], c[1])) put(out, placement, c[0], c[1], w, h, MaterialRole.ACCENT);
			}
		}
	}

	private static double angle(int[] cell, int w, int h) {
		return Math.atan2(2 * cell[1] + 1 - h, 2 * cell[0] + 1 - w);
	}

	protected static void put(GeometryBuilder out, ShapePlacement placement, int u, int v, int w, int h, MaterialRole role) {
		int[] o = placement.offset(u, v, w, h);
		out.set(o[0], o[1], o[2], role);
	}

	protected interface CellAction {
		void accept(int u, int v);
	}

	protected static void forEach(Mask2D mask, CellAction action) {
		for (int v = 0; v < mask.height(); v++) {
			for (int u = 0; u < mask.width(); u++) {
				if (mask.contains(u, v)) action.accept(u, v);
			}
		}
	}

	@Override
	public Set<DetailFeature> supportedDetails() {
		return EnumSet.of(DetailFeature.EDGE_TRIM, DetailFeature.INNER_RING, DetailFeature.OUTER_RING,
				DetailFeature.ALTERNATING, DetailFeature.ACCENTS, DetailFeature.CORNER_ACCENTS);
	}

	@Override
	public Set<DetailFeature> presetDetails(DetailPreset preset) {
		return switch (preset) {
			case NONE, CUSTOM -> EnumSet.noneOf(DetailFeature.class);
			case SIMPLE -> EnumSet.of(DetailFeature.EDGE_TRIM);
			case DETAILED -> EnumSet.of(DetailFeature.EDGE_TRIM, DetailFeature.ACCENTS);
			case ARCHITECTURAL -> EnumSet.of(DetailFeature.OUTER_RING, DetailFeature.INNER_RING, DetailFeature.CORNER_ACCENTS);
			case DECORATIVE -> EnumSet.of(DetailFeature.EDGE_TRIM, DetailFeature.ALTERNATING, DetailFeature.ACCENTS);
		};
	}

	@Override
	public Set<MaterialRole> roles() {
		return EnumSet.of(MaterialRole.PRIMARY, MaterialRole.TRIM, MaterialRole.ACCENT);
	}

	@Override
	public List<String> dimensionKeys() {
		return uniform() ? List.of("diameter", "thickness") : List.of("width", "length", "thickness");
	}
}
