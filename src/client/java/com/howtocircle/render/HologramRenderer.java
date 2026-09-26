package com.howtocircle.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import com.howtocircle.client.HologramManager;
import com.howtocircle.config.HowToCircleConfig;
import com.howtocircle.input.CentreSelectionHandler;

/**
 * Draws the hologram as translucent geometry through Fabric's {@code LevelRenderEvents.COLLECT_SUBMITS}.
 *
 * <p>Each connected section is drawn as <em>one</em> box (not one box per block), outlined with thin edge ribbons.
 * Faint grid lines on the face towards the camera keep individual blocks countable. Edge thickness grows with distance
 * so outlines stay visible far away, and grid lines are only emitted near the camera, so even very large shapes produce
 * a bounded amount of geometry. No entities are spawned and no blocks are placed: this is purely client-side rendering.
 *
 * <p>All per-frame work reads the cached {@link HologramGeometry}; nothing is regenerated and the only per-frame
 * allocation is the geometry callback itself.
 */
public final class HologramRenderer {
	private static final float BOX_INSET = 0.004F;
	private static final float BASE_EDGE = 0.03F;
	private static final float GRID_WIDTH = 0.02F;
	private static final float FACE_LIFT = 0.012F;
	private static final float GRID_RANGE = 40F;
	private static final float EDGE_RANGE = 160F;
	private static final int ACCENT = 0xFFD24D;

	private HologramRenderer() {
	}

	public static void render(LevelRenderContext context) {
		HowToCircleConfig config = HowToCircleConfig.get();
		HologramGeometry geometry = HologramManager.get().geometry();
		BlockPos marker = CentreSelectionHandler.get().markerPos();

		if (geometry == null && marker == null) return;

		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack poseStack = context.poseStack();
		long millis = System.currentTimeMillis();

		if (geometry != null) {
			BlockPos anchor = geometry.anchor;
			float camX = (float) (camera.x - anchor.getX());
			float camY = (float) (camera.y - anchor.getY());
			float camZ = (float) (camera.z - anchor.getZ());
			Colors colors = Colors.of(config);

			poseStack.pushPose();
			poseStack.translate(anchor.getX() - camera.x, anchor.getY() - camera.y, anchor.getZ() - camera.z);
			boolean grid = config.showBlockGrid;
			context.submitNodeCollector().submitCustomGeometry(poseStack, HologramRenderTypes.hologram(config.seeThroughBlocks), (pose, buffer) -> {
				long start = System.nanoTime();
				new Emitter(pose, buffer).hologram(geometry, camX, camY, camZ, colors, grid);
				RenderStats.add(System.nanoTime() - start);
			});
			poseStack.popPose();
		}

		if (marker != null) {
			renderSelectionMarker(context, camera, marker, CentreSelectionHandler.get().previewBounds(), millis);
		}
	}

	private static void renderSelectionMarker(LevelRenderContext context, Vec3 camera, BlockPos marker, float @Nullable [] preview, long millis) {
		PoseStack poseStack = context.poseStack();
		float pulse = 0.5F + 0.5F * (float) Math.sin(millis / 180.0);
		int fill = ARGB.color(Math.round(70 + 60 * pulse), 0xFF, 0xD2, 0x4D);
		int edge = ARGB.color(230, 0xFF, 0xE6, 0x80);
		float camX = (float) (camera.x - marker.getX());
		float camY = (float) (camera.y - marker.getY());
		float camZ = (float) (camera.z - marker.getZ());

		poseStack.pushPose();
		poseStack.translate(marker.getX() - camera.x, marker.getY() - camera.y, marker.getZ() - camera.z);
		context.submitNodeCollector().submitCustomGeometry(poseStack, HologramRenderTypes.hologram(true), (pose, buffer) -> {
			Emitter e = new Emitter(pose, buffer);
			float grow = 0.04F * pulse;
			e.box(0.2F - grow, 0.2F - grow, 0.2F - grow, 0.8F + grow, 0.8F + grow, 0.8F + grow, fill);
			e.boxEdges(-0.01F, -0.01F, -0.01F, 1.01F, 1.01F, 1.01F, 0.035F, edge);

			if (preview != null) {
				// Footprint of the shape that would be generated here.
				float[] b = preview;
				float thickness = Math.max(0.04F, distance(b, 0, camX, camY, camZ) / 300F);
				e.boxEdges(b[0], b[1], b[2], b[3], b[4], b[5], thickness, ARGB.color(170, 0xFF, 0xD2, 0x4D));
			}
		});
		poseStack.popPose();
	}

	static float distance(float[] boxes, int index, float x, float y, float z) {
		int o = index * 6;
		float dx = Math.max(Math.max(boxes[o] - x, 0), x - boxes[o + 3]);
		float dy = Math.max(Math.max(boxes[o + 1] - y, 0), y - boxes[o + 4]);
		float dz = Math.max(Math.max(boxes[o + 2] - z, 0), z - boxes[o + 5]);
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Colours for one frame, derived from the config. */
	record Colors(int fill, int edge, int grid, int centreFill, int centreEdge, int dimension) {
		static Colors of(HowToCircleConfig config) {
			int rgb = config.hologramColor;
			float opacity = config.hologramOpacity;
			int r = ARGB.red(rgb);
			int g = ARGB.green(rgb);
			int b = ARGB.blue(rgb);
			// Edges are a lighter, more opaque version of the fill so section boundaries read clearly.
			int er = r + (255 - r) / 3;
			int eg = g + (255 - g) / 3;
			int eb = b + (255 - b) / 3;
			int accent = isYellowish(rgb) ? 0xFF5CC8 : ACCENT;
			int labelAlpha = Math.round(255 * config.labelOpacity);
			return new Colors(
					ARGB.color(Math.round(255 * opacity), r, g, b),
					ARGB.color(Math.round(255 * Math.min(1F, 0.35F + opacity * 1.6F)), er, eg, eb),
					ARGB.color(Math.round(255 * Math.min(1F, 0.15F + opacity * 0.9F)), er, eg, eb),
					ARGB.color(Math.round(255 * Math.min(0.85F, opacity + 0.3F)), ARGB.red(accent), ARGB.green(accent), ARGB.blue(accent)),
					ARGB.color(235, ARGB.red(accent), ARGB.green(accent), ARGB.blue(accent)),
					ARGB.color(labelAlpha, ARGB.red(config.labelColor), ARGB.green(config.labelColor), ARGB.blue(config.labelColor)));
		}

		private static boolean isYellowish(int rgb) {
			return ARGB.red(rgb) > 200 && ARGB.green(rgb) > 170 && ARGB.blue(rgb) < 120;
		}
	}

	/**
	 * Writes quads (4 vertices each) in the {@code POSITION_COLOR} format used by the debug filled box pipeline.
	 */
	static final class Emitter {
		private final PoseStack.Pose pose;
		private final VertexConsumer buffer;
		private final float[] p = new float[3];
		private final float[] cam = new float[3];
		private final float[] min = new float[3];
		private final float[] max = new float[3];

		Emitter(PoseStack.Pose pose, VertexConsumer buffer) {
			this.pose = pose;
			this.buffer = buffer;
		}

		void hologram(HologramGeometry g, float camX, float camY, float camZ, Colors colors, boolean grid) {
			float[] boxes = g.boxes;
			int n = g.axisN;
			cam[0] = camX;
			cam[1] = camY;
			cam[2] = camZ;
			// Draw grid and dimension lines on the face of the slab that points towards the camera.
			boolean positiveFace = cam[n] >= (g.normalMin + g.normalMax) * 0.5F;
			float face = positiveFace ? g.normalMax + FACE_LIFT : g.normalMin - FACE_LIFT;

			for (int i = 0; i < g.sectionCount; i++) {
				int o = i * 6;
				float dist = distance(boxes, i, camX, camY, camZ);
				box(boxes[o] + BOX_INSET, boxes[o + 1] + BOX_INSET, boxes[o + 2] + BOX_INSET,
						boxes[o + 3] - BOX_INSET, boxes[o + 4] - BOX_INSET, boxes[o + 5] - BOX_INSET, colors.fill());

				if (dist < EDGE_RANGE) {
					float thickness = BASE_EDGE * Math.max(1F, dist / 12F);
					boxEdges(boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], boxes[o + 4], boxes[o + 5], Math.min(thickness, 0.3F), colors.edge());
				}

				if (grid && dist < GRID_RANGE) {
					gridLines(g, i, cam, face, colors.grid());
				}
			}

			float[] centre = g.centreBoxes;

			for (int i = 0; i < centre.length / 6; i++) {
				int o = i * 6;
				box(centre[o] + 0.3F, centre[o + 1] + 0.3F, centre[o + 2] + 0.3F, centre[o + 3] - 0.3F, centre[o + 4] - 0.3F, centre[o + 5] - 0.3F, colors.centreFill());
				boxEdges(centre[o] + 0.3F, centre[o + 1] + 0.3F, centre[o + 2] + 0.3F, centre[o + 3] - 0.3F, centre[o + 4] - 0.3F, centre[o + 5] - 0.3F, 0.03F, colors.centreEdge());
			}

			if (DimensionLabelRenderer.dimensionLinesEnabled()) {
				dimensionLines(g, cam, face, colors.dimension());
			}
		}

		/** Block boundary lines inside a section, limited to the part near the camera. */
		private void gridLines(HologramGeometry g, int index, float[] cam, float face, int color) {
			int o = index * 6;
			int n = g.axisN;

			for (int pass = 0; pass < 2; pass++) {
				int along = pass == 0 ? g.axisU : g.axisV; // lines are perpendicular to this axis
				int across = pass == 0 ? g.axisV : g.axisU;
				float start = g.boxes[o + along];
				float end = g.boxes[o + 3 + along];
				int first = (int) Math.max(start + 1, Math.ceil(cam[along] - GRID_RANGE));
				int last = (int) Math.min(end - 1, Math.floor(cam[along] + GRID_RANGE));
				float lineStart = g.boxes[o + across];
				float lineEnd = g.boxes[o + 3 + across];

				for (int k = first; k <= last; k++) {
					flat(across, lineStart, lineEnd, along, k - GRID_WIDTH * 0.5F, k + GRID_WIDTH * 0.5F, n, face, color);
				}
			}
		}

		/** CAD-style dimension lines: a line parallel to the measured side with ticks at both ends. */
		private void dimensionLines(HologramGeometry g, float[] cam, float face, int color) {
			int n = g.axisN;

			for (int i = 0; i < g.dimensionLineCount; i++) {
				float midAlong = (g.lineFrom[i] + g.lineTo[i]) * 0.5F;
				float dx = cam[g.lineAlong[i]] - midAlong;
				float dy = cam[g.lineAcross[i]] - g.linePos[i];
				float dz = cam[n] - face;
				float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

				if (dist > DimensionLabelRenderer.labelRange()) continue;

				float w = Math.min(0.02F * Math.max(1F, dist / 10F), 0.2F);
				int along = g.lineAlong[i];
				int across = g.lineAcross[i];
				float pos = g.linePos[i];
				float from = g.lineFrom[i];
				float to = g.lineTo[i];
				// Main line, slightly shorter than the side so the ticks stand out.
				flat(along, from, to, across, pos - w * 0.5F, pos + w * 0.5F, n, face, color);
				// Extension ticks at both ends, reaching back to the section edge.
				float tickIn = pos - g.lineOutward[i] * HologramGeometry.DIMENSION_GAP;
				float tickOut = pos + g.lineOutward[i] * 0.15F;
				float lo = Math.min(tickIn, tickOut);
				float hi = Math.max(tickIn, tickOut);
				flat(across, lo, hi, along, from, from + w, n, face, color);
				flat(across, lo, hi, along, to - w, to, n, face, color);
			}
		}

		/** A flat rectangle spanning [a0, a1] × [b0, b1] at coordinate {@code c} of axis {@code cAxis}. */
		void flat(int aAxis, float a0, float a1, int bAxis, float b0, float b1, int cAxis, float c, int color) {
			vertex(aAxis, a0, bAxis, b0, cAxis, c, color);
			vertex(aAxis, a1, bAxis, b0, cAxis, c, color);
			vertex(aAxis, a1, bAxis, b1, cAxis, c, color);
			vertex(aAxis, a0, bAxis, b1, cAxis, c, color);
		}

		void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
			// Bottom, top
			flat(0, x0, x1, 2, z0, z1, 1, y0, color);
			flat(0, x0, x1, 2, z0, z1, 1, y1, color);
			// North, south
			flat(0, x0, x1, 1, y0, y1, 2, z0, color);
			flat(0, x0, x1, 1, y0, y1, 2, z1, color);
			// West, east
			flat(2, z0, z1, 1, y0, y1, 0, x0, color);
			flat(2, z0, z1, 1, y0, y1, 0, x1, color);
		}

		/** The 12 edges of a box, each drawn as two crossed ribbons so it is visible from every angle. */
		void boxEdges(float x0, float y0, float z0, float x1, float y1, float z1, float thickness, int color) {
			float h = thickness * 0.5F;
			min[0] = x0;
			min[1] = y0;
			min[2] = z0;
			max[0] = x1;
			max[1] = y1;
			max[2] = z1;

			for (int along = 0; along < 3; along++) {
				int a1 = (along + 1) % 3;
				int a2 = (along + 2) % 3;

				for (int corner = 0; corner < 4; corner++) {
					float c1 = (corner & 1) == 0 ? min[a1] : max[a1];
					float c2 = (corner & 2) == 0 ? min[a2] : max[a2];
					flat(along, min[along] - h, max[along] + h, a1, c1 - h, c1 + h, a2, c2, color);
					flat(along, min[along] - h, max[along] + h, a2, c2 - h, c2 + h, a1, c1, color);
				}
			}
		}

		private void vertex(int aAxis, float a, int bAxis, float b, int cAxis, float c, int color) {
			p[aAxis] = a;
			p[bAxis] = b;
			p[cAxis] = c;
			buffer.addVertex(pose, p[0], p[1], p[2]).setColor(color);
		}
	}
}
