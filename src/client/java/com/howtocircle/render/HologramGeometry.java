package com.howtocircle.render;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import com.howtocircle.dimensions.ConnectedSection;
import com.howtocircle.dimensions.DimensionLabel;
import com.howtocircle.dimensions.LabelFormat;
import com.howtocircle.geometry.CircleShape;
import com.howtocircle.geometry.ShapePlacement;

/**
 * Immutable, render-ready data for one hologram, precomputed once whenever the shape or its placement changes.
 *
 * <p>All coordinates are {@code float}s relative to the anchor block's minimum corner, so the renderer only needs one
 * {@code double} translation per frame and never loses precision far from the world origin. Axes are indices into
 * {@code (x, y, z)}.
 */
public final class HologramGeometry {
	/** Gap between a section and its dimension line, in blocks. */
	static final float DIMENSION_GAP = 0.3F;

	public final BlockPos anchor;
	public final ShapePlacement placement;
	/** Axis indices: width axis, height axis and normal (thickness) axis. */
	public final int axisU;
	public final int axisV;
	public final int axisN;
	/** The one-block slab the shape occupies along the normal axis. */
	public final float normalMin;
	public final float normalMax;

	public final int sectionCount;
	/** Section boxes, 6 floats each: min x, y, z, max x, y, z. */
	public final float[] boxes;
	public final ConnectedSection[] sections;
	/** Label text per section ("7 by 1"), shown by pop-ups and single-measurement dimension lines. */
	public final FormattedCharSequence[] sectionText;
	public final int[] sectionTextWidth;

	/** Centre marker boxes, 6 floats each. */
	public final float[] centreBoxes;
	/** Bounds of the whole shape: 6 floats. */
	public final float[] bounds;
	public final int blockCount;

	public final int dimensionLineCount;
	/** Per dimension line: axis the line runs along, axis it is offset across. */
	public final int[] lineAlong;
	public final int[] lineAcross;
	/** Per dimension line: start and end along {@link #lineAlong}, position across, and outward sign (+1 / -1). */
	public final float[] lineFrom;
	public final float[] lineTo;
	public final float[] linePos;
	public final float[] lineOutward;
	public final int[] lineSection;
	public final FormattedCharSequence[] lineText;
	public final int[] lineTextWidth;
	/**
	 * Per dimension line: the measurement alone ("7"), used when pop-ups already show the full "7 by 1". {@code null}
	 * for single blocks, whose pop-up says everything.
	 */
	public final FormattedCharSequence[] lineShortText;
	public final int[] lineShortTextWidth;

	private HologramGeometry(Builder b) {
		anchor = b.anchor;
		placement = b.placement;
		axisU = b.axisU;
		axisV = b.axisV;
		axisN = b.axisN;
		normalMin = b.normalMin;
		normalMax = b.normalMin + 1;
		sectionCount = b.sections.length;
		boxes = b.boxes;
		sections = b.sections;
		sectionText = b.sectionText;
		sectionTextWidth = b.sectionTextWidth;
		centreBoxes = b.centreBoxes;
		bounds = b.bounds;
		blockCount = b.blockCount;
		dimensionLineCount = b.lineCount;
		lineAlong = b.lineAlong;
		lineAcross = b.lineAcross;
		lineFrom = b.lineFrom;
		lineTo = b.lineTo;
		linePos = b.linePos;
		lineOutward = b.lineOutward;
		lineSection = b.lineSection;
		lineText = b.lineText;
		lineTextWidth = b.lineTextWidth;
		lineShortText = b.lineShortText;
		lineShortTextWidth = b.lineShortTextWidth;
	}

	public static int axisIndex(ShapePlacement.Axis axis) {
		return axis.ordinal();
	}

	public static HologramGeometry build(CircleShape shape, List<ConnectedSection> sectionList, BlockPos anchor, ShapePlacement placement, LabelFormat format) {
		Builder b = new Builder();
		Font font = Minecraft.getInstance().font;
		b.anchor = anchor.immutable();
		b.placement = placement;
		b.axisU = axisIndex(placement.widthAxis());
		b.axisV = axisIndex(placement.heightAxis());
		b.axisN = axisIndex(placement.normalAxis());
		b.blockCount = shape.blockCount();

		int width = shape.width();
		int height = shape.height();
		int minU = placement.minOffsetU(width);
		int minV = placement.minOffsetV(height);
		// Along the normal axis only the vertical offset (Y) can move the slab.
		b.normalMin = b.axisN == 1 ? placement.verticalOffset() : 0;
		float yShiftU = b.axisU == 1 ? placement.verticalOffset() : 0;
		float yShiftV = b.axisV == 1 ? placement.verticalOffset() : 0;

		int count = sectionList.size();
		b.sections = sectionList.toArray(new ConnectedSection[0]);
		b.boxes = new float[count * 6];
		b.sectionText = new FormattedCharSequence[count];
		b.sectionTextWidth = new int[count];

		// Up to two dimension lines per section.
		b.lineAlong = new int[count * 2];
		b.lineAcross = new int[count * 2];
		b.lineFrom = new float[count * 2];
		b.lineTo = new float[count * 2];
		b.linePos = new float[count * 2];
		b.lineOutward = new float[count * 2];
		b.lineSection = new int[count * 2];
		b.lineText = new FormattedCharSequence[count * 2];
		b.lineTextWidth = new int[count * 2];
		b.lineShortText = new FormattedCharSequence[count * 2];
		b.lineShortTextWidth = new int[count * 2];

		for (int i = 0; i < count; i++) {
			ConnectedSection s = b.sections[i];
			float u0 = minU + s.u() + yShiftU;
			float u1 = u0 + s.width();
			float v0 = minV + s.v() + yShiftV;
			float v1 = v0 + s.height();
			setBox(b.boxes, i, b.axisU, u0, u1, b.axisV, v0, v1, b.axisN, b.normalMin, b.normalMin + 1);

			String full = DimensionLabel.text(s, format);
			b.sectionText[i] = Component.literal(full).getVisualOrderText();
			b.sectionTextWidth[i] = font.width(full);

			// Put dimension lines on the side of the section facing away from the shape centre.
			float outwardV = 2 * s.v() + s.height() < height ? -1 : 1;
			float outwardU = 2 * s.u() + s.width() < width ? -1 : 1;
			float lineV = outwardV < 0 ? v0 - DIMENSION_GAP : v1 + DIMENSION_GAP;
			float lineU = outwardU < 0 ? u0 - DIMENSION_GAP : u1 + DIMENSION_GAP;

			switch (s.kind()) {
				case SINGLE -> b.addLine(i, b.axisU, u0, u1, b.axisV, lineV, outwardV, full, null, font);
				case LINE_ALONG_WIDTH -> b.addLine(i, b.axisU, u0, u1, b.axisV, lineV, outwardV, full, DimensionLabel.side(s.width()), font);
				case LINE_ALONG_HEIGHT -> b.addLine(i, b.axisV, v0, v1, b.axisU, lineU, outwardU, full, DimensionLabel.side(s.height()), font);
				case RECTANGLE -> {
					String w = DimensionLabel.side(s.width());
					String h = DimensionLabel.side(s.height());
					b.addLine(i, b.axisU, u0, u1, b.axisV, lineV, outwardV, w, w, font);
					b.addLine(i, b.axisV, v0, v1, b.axisU, lineU, outwardU, h, h, font);
				}
			}
		}

		// Centre marker: the 1 or 2 central blocks along each axis (drawn even when the outline leaves them empty).
		int centreW = width % 2 == 1 ? 1 : 2;
		int centreH = height % 2 == 1 ? 1 : 2;
		b.centreBoxes = new float[centreW * centreH * 6];
		int c = 0;

		for (int dv = 0; dv < centreH; dv++) {
			for (int du = 0; du < centreW; du++) {
				float cu = minU + (width - centreW) / 2 + du + yShiftU;
				float cv = minV + (height - centreH) / 2 + dv + yShiftV;
				setBox(b.centreBoxes, c++, b.axisU, cu, cu + 1, b.axisV, cv, cv + 1, b.axisN, b.normalMin, b.normalMin + 1);
			}
		}

		b.bounds = new float[6];
		setBox(b.bounds, 0, b.axisU, minU + yShiftU, minU + width + yShiftU, b.axisV, minV + yShiftV, minV + height + yShiftV, b.axisN, b.normalMin, b.normalMin + 1);
		return new HologramGeometry(b);
	}

	private static void setBox(float[] out, int index, int a, float a0, float a1, int bAxis, float b0, float b1, int n, float n0, float n1) {
		int o = index * 6;
		out[o + a] = a0;
		out[o + 3 + a] = a1;
		out[o + bAxis] = b0;
		out[o + 3 + bAxis] = b1;
		out[o + n] = n0;
		out[o + 3 + n] = n1;
	}

	private static final class Builder {
		BlockPos anchor;
		ShapePlacement placement;
		int axisU;
		int axisV;
		int axisN;
		float normalMin;
		int blockCount;
		ConnectedSection[] sections;
		float[] boxes;
		FormattedCharSequence[] sectionText;
		int[] sectionTextWidth;
		float[] centreBoxes;
		float[] bounds;
		int lineCount;
		int[] lineAlong;
		int[] lineAcross;
		float[] lineFrom;
		float[] lineTo;
		float[] linePos;
		float[] lineOutward;
		int[] lineSection;
		FormattedCharSequence[] lineText;
		int[] lineTextWidth;
		FormattedCharSequence[] lineShortText;
		int[] lineShortTextWidth;

		void addLine(int section, int along, float from, float to, int across, float pos, float outward, String text, String shortText, Font font) {
			int i = lineCount++;
			lineSection[i] = section;
			lineAlong[i] = along;
			lineAcross[i] = across;
			lineFrom[i] = from;
			lineTo[i] = to;
			linePos[i] = pos;
			lineOutward[i] = outward;
			lineText[i] = Component.literal(text).getVisualOrderText();
			lineTextWidth[i] = font.width(text);

			if (shortText != null) {
				lineShortText[i] = Component.literal(shortText).getVisualOrderText();
				lineShortTextWidth[i] = font.width(shortText);
			}
		}
	}
}
