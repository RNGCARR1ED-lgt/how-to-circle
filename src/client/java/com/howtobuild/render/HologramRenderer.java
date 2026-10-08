package com.howtobuild.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import com.howtobuild.client.BuildSession;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.config.LabelSettings;
import com.howtobuild.geometry.Box;
import com.howtobuild.geometry.Guide;
import com.howtobuild.input.CentreSelectionHandler;

/**
 * Draws the hologram through Fabric's {@code LevelRenderEvents.COLLECT_SUBMITS} as translucent client-side geometry.
 * No entities are spawned and no blocks are touched.
 *
 * <ul>
 *     <li><b>Blocks</b>: the cached {@link RenderMesh} (culled, merged faces; real slab and stair shapes).</li>
 *     <li><b>Edges</b>: thin crossed ribbons, thicker with distance so they stay visible; only edges within
 *     {@link #EDGE_RANGE} are drawn, which bounds the per-frame cost of very large shapes. Freshly regenerated geometry
 *     briefly glows (the "current edit" state).</li>
 *     <li><b>Dimension lines</b> from the cached {@link LabelSet}: flat CAD lines with end ticks on the face of a flat
 *     shape that points towards the camera, and 3D lines for the overall width, length and height.</li>
 *     <li><b>Mirror planes</b> and the <b>centre selection marker</b>.</li>
 * </ul>
 */
public final class HologramRenderer {
	private static final float BASE_EDGE = 0.025F;
	private static final float EDGE_RANGE = 96F;
	private static final float FACE_LIFT = 0.015F;
	private static final float TICK = 0.15F;
	private static final int MIRROR_COLOR = 0xB070FF;
	private static final int CENTRE_COLOR = 0xFFB020;
	private static final int GUIDE_COLOR = 0xE8F8FF;

	private HologramRenderer() {
	}

	public static void render(LevelRenderContext context) {
		HowToBuildConfig config = HowToBuildConfig.get();
		BuildSession session = BuildSession.get();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack poseStack = context.poseStack();

		if (config.hologram.visible) {
			BuildSession.Resolved resolved = session.resolved();
			RenderMesh mesh = session.mesh();

			if (resolved != null && mesh != null) {
				BlockPos o = resolved.origin();
				float camX = (float) (camera.x - o.getX());
				float camY = (float) (camera.y - o.getY());
				float camZ = (float) (camera.z - o.getZ());
				boolean edges = config.hologram.showEdges;
				float glow = Math.max(0F, 1F - session.sinceGenerated() / 700F);
				LabelSet labels = session.labels();
				LabelSettings labelSettings = config.labels;

				poseStack.pushPose();
				poseStack.translate(o.getX() - camera.x, o.getY() - camera.y, o.getZ() - camera.z);
				context.submitNodeCollector().submitCustomGeometry(poseStack, HologramRenderTypes.hologram(config.hologram.seeThroughBlocks), (pose, buffer) -> {
					long start = System.nanoTime();
					Emitter e = new Emitter(pose, buffer);
					e.mesh(mesh, camX, camY, camZ, edges, glow);

					if (resolved.mirror().enabled()) e.mirrorPlanes(resolved);

					RenderStats.add(System.nanoTime() - start);
				});

				if (labels != null && labels.lineCount > 0 && (labelSettings.showDimensions || labelSettings.showOverall)) {
					boolean through = labelSettings.throughWalls || config.hologram.seeThroughBlocks;
					context.submitNodeCollector().submitCustomGeometry(poseStack, HologramRenderTypes.hologram(through), (pose, buffer) ->
							new Emitter(pose, buffer).dimensionLines(labels, labelSettings, camX, camY, camZ));
				}

				boolean showCentre = config.hologram.showCentre || CentreSelectionHandler.get().isActive();
				List<int[]> centres = showCentre ? resolved.result().centreCells() : List.of();
				List<int[]> guides = config.hologram.showGuides ? resolved.result().guides() : List.of();

				if (!centres.isEmpty() || !guides.isEmpty()) {
					float pulse = 0.5F + 0.5F * (float) Math.sin(System.currentTimeMillis() / 220.0);
					// Drawn through blocks so the centre and the master outline are never hidden inside the structure.
					context.submitNodeCollector().submitCustomGeometry(poseStack, HologramRenderTypes.hologram(true), (pose, buffer) -> {
						Emitter e = new Emitter(pose, buffer);
						e.guides(guides);
						e.centres(centres, pulse, CENTRE_COLOR);
					});
				}

				poseStack.popPose();
			}
		}

		CentreSelectionHandler selection = CentreSelectionHandler.get();
		BlockPos marker = selection.markerPos();

		if (marker != null) {
			renderSelectionMarker(context, camera, marker, selection.previewBounds(), selection.previewCentres(), selection.markerColor());
		}
	}

	private static void renderSelectionMarker(LevelRenderContext context, Vec3 camera, BlockPos marker, @Nullable Box preview, List<int[]> centres, int rgb) {
		PoseStack poseStack = context.poseStack();
		float pulse = 0.5F + 0.5F * (float) Math.sin(System.currentTimeMillis() / 180.0);
		int fill = ARGB.color(Math.round(70 + 60 * pulse), ARGB.red(rgb), ARGB.green(rgb), ARGB.blue(rgb));
		int edge = ARGB.color(230, Math.min(255, ARGB.red(rgb) + 30), Math.min(255, ARGB.green(rgb) + 30), Math.min(255, ARGB.blue(rgb) + 30));

		poseStack.pushPose();
		poseStack.translate(marker.getX() - camera.x, marker.getY() - camera.y, marker.getZ() - camera.z);
		context.submitNodeCollector().submitCustomGeometry(poseStack, HologramRenderTypes.hologram(true), (pose, buffer) -> {
			Emitter e = new Emitter(pose, buffer);
			float grow = 0.04F * pulse;
			e.box(0.2F - grow, 0.2F - grow, 0.2F - grow, 0.8F + grow, 0.8F + grow, 0.8F + grow, fill);
			e.boxEdges(-0.01F, -0.01F, -0.01F, 1.01F, 1.01F, 1.01F, 0.035F, edge);

			// The full 1×1 / 2×2 centre the shape would get here (including the configured offset).
			e.centres(centres, pulse, rgb);

			if (preview != null) {
				// Footprint of the shape that would be generated here.
				float size = Math.max(preview.sizeX(), preview.sizeZ());
				e.boxEdges(preview.minX(), preview.minY(), preview.minZ(), preview.maxX() + 1, preview.maxY() + 1, preview.maxZ() + 1,
						Math.max(0.04F, size / 300F), ARGB.color(170, ARGB.red(rgb), ARGB.green(rgb), ARGB.blue(rgb)));
			}
		});
		poseStack.popPose();
	}

	/** Distance from a point to an axis-aligned box. */
	static float distance(float x0, float y0, float z0, float x1, float y1, float z1, float x, float y, float z) {
		float dx = Math.max(Math.max(Math.min(x0, x1) - x, 0), x - Math.max(x0, x1));
		float dy = Math.max(Math.max(Math.min(y0, y1) - y, 0), y - Math.max(y0, y1));
		float dz = Math.max(Math.max(Math.min(z0, z1) - z, 0), z - Math.max(z0, z1));
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Writes quads (4 vertices each) in the {@code POSITION_COLOR} format used by the debug filled box pipeline.
	 */
	static final class Emitter {
		private final PoseStack.Pose pose;
		private final VertexConsumer buffer;
		private final float[] p = new float[3];
		private final float[] a = new float[3];
		private final float[] c = new float[3];
		private final float[] min = new float[3];
		private final float[] max = new float[3];

		Emitter(PoseStack.Pose pose, VertexConsumer buffer) {
			this.pose = pose;
			this.buffer = buffer;
		}

		void mesh(RenderMesh mesh, float camX, float camY, float camZ, boolean edges, float glow) {
			float[] q = mesh.quads;

			for (int i = 0; i < mesh.quadCount; i++) {
				int o = i * 12;
				int color = mesh.quadColors[i];
				buffer.addVertex(pose, q[o], q[o + 1], q[o + 2]).setColor(color);
				buffer.addVertex(pose, q[o + 3], q[o + 4], q[o + 5]).setColor(color);
				buffer.addVertex(pose, q[o + 6], q[o + 7], q[o + 8]).setColor(color);
				buffer.addVertex(pose, q[o + 9], q[o + 10], q[o + 11]).setColor(color);
			}

			if (!edges) return;

			float[] s = mesh.edges;

			for (int i = 0; i < mesh.edgeCount; i++) {
				int o = i * 6;
				float dist = distance(s[o], s[o + 1], s[o + 2], s[o + 3], s[o + 4], s[o + 5], camX, camY, camZ);

				if (dist > EDGE_RANGE) continue;

				float thickness = Math.min(0.25F, BASE_EDGE * Math.max(1F, dist / 12F));
				int color = mesh.edgeColors[i];

				if (glow > 0) {
					color = ARGB.color(Math.min(255, ARGB.alpha(color) + Math.round(25 * glow)), lerp(ARGB.red(color), 255, glow * 0.6F),
							lerp(ARGB.green(color), 255, glow * 0.6F), lerp(ARGB.blue(color), 255, glow * 0.6F));
					thickness *= 1F + glow;
				}

				segment(s[o], s[o + 1], s[o + 2], s[o + 3], s[o + 4], s[o + 5], thickness, color);
			}
		}

		private static int lerp(int from, int to, float t) {
			return Math.round(from + (to - from) * t);
		}

		/**
		 * The centre cells as glowing outlined cubes, slightly larger than a block, plus a vertical beam through the true
		 * centre (a block's middle for 1×1, the shared corner for 2×2) so it is obvious where the centre is.
		 */
		void centres(List<int[]> cells, float pulse, int rgb) {
			if (cells.isEmpty()) return;

			int fill = ARGB.color(Math.round(55 + 45 * pulse), ARGB.red(rgb), ARGB.green(rgb), ARGB.blue(rgb));
			int edge = ARGB.color(255, Math.min(255, ARGB.red(rgb) + 40), Math.min(255, ARGB.green(rgb) + 40), Math.min(255, ARGB.blue(rgb) + 40));
			float minX = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE;
			float minZ = Float.MAX_VALUE;
			float maxZ = -Float.MAX_VALUE;
			float y = Float.MAX_VALUE;

			for (int[] c : cells) {
				float g = 0.03F + 0.02F * pulse;
				box(c[0] - g, c[1] - g, c[2] - g, c[0] + 1 + g, c[1] + 1 + g, c[2] + 1 + g, fill);
				boxEdges(c[0] - g, c[1] - g, c[2] - g, c[0] + 1 + g, c[1] + 1 + g, c[2] + 1 + g, 0.05F, edge);
				minX = Math.min(minX, c[0]);
				maxX = Math.max(maxX, c[0] + 1);
				minZ = Math.min(minZ, c[2]);
				maxZ = Math.max(maxZ, c[2] + 1);
				y = Math.min(y, c[1]);
			}

			float cx = (minX + maxX) / 2;
			float cz = (minZ + maxZ) / 2;
			segment(cx, y, cz, cx, y + 4, cz, 0.06F, edge);
		}

		/** Reference outline cells (e.g. the master circle a spiral follows): thin squares on the floor of each cell. */
		void guides(List<int[]> cells) {
			for (int[] c : cells) {
				int rgb = guideColor(c.length > 3 ? c[3] : Guide.OUTLINE);
				int fill = ARGB.color(40, ARGB.red(rgb), ARGB.green(rgb), ARGB.blue(rgb));
				int edge = ARGB.color(220, ARGB.red(rgb), ARGB.green(rgb), ARGB.blue(rgb));
				float y = c[1] + 0.02F;
				flat(0, c[0], c[0] + 1, 2, c[2], c[2] + 1, 1, y, fill);
				flat(0, c[0], c[0] + 1, 2, c[2], c[2] + 0.06F, 1, y, edge);
				flat(0, c[0], c[0] + 1, 2, c[2] + 0.94F, c[2] + 1, 1, y, edge);
				flat(0, c[0], c[0] + 0.06F, 2, c[2], c[2] + 1, 1, y, edge);
				flat(0, c[0] + 0.94F, c[0] + 1, 2, c[2], c[2] + 1, 1, y, edge);
			}
		}

		/** Guide colours: master outline, protected area (red), region boundary (yellow), contours (pale blue), inset (green). */
		static int guideColor(int kind) {
			return switch (kind) {
				case Guide.PROTECTED -> 0xFF4D4D;
				case Guide.BOUNDARY -> 0xFFD24D;
				case Guide.CONTOUR -> 0x9BE7FF;
				case Guide.INSET -> 0x6BE08A;
				default -> GUIDE_COLOR;
			};
		}

		/** Translucent sheets showing where each mirror plane lies, spanning the preview's height and extent. */
		void mirrorPlanes(BuildSession.Resolved r) {
			Box bounds = r.result().bounds();

			if (bounds == null) return;

			int fill = ARGB.color(46, ARGB.red(MIRROR_COLOR), ARGB.green(MIRROR_COLOR), ARGB.blue(MIRROR_COLOR));
			int edge = ARGB.color(200, ARGB.red(MIRROR_COLOR), ARGB.green(MIRROR_COLOR), ARGB.blue(MIRROR_COLOR));
			float y0 = bounds.minY() - 0.5F;
			float y1 = bounds.maxY() + 1.5F;

			if (r.mirror().axis().mirrorsX()) {
				float x = r.mirrorPlaneX() / 2F;
				float z0 = bounds.minZ() - 1;
				float z1 = bounds.maxZ() + 2;
				flat(2, z0, z1, 1, y0, y1, 0, x, fill);
				boxEdges(x, y0, z0, x, y1, z1, 0.05F, edge);
			}

			if (r.mirror().axis().mirrorsZ()) {
				float z = r.mirrorPlaneZ() / 2F;
				float x0 = bounds.minX() - 1;
				float x1 = bounds.maxX() + 2;
				flat(0, x0, x1, 1, y0, y1, 2, z, fill);
				boxEdges(x0, y0, z, x1, y1, z, 0.05F, edge);
			}
		}

		/**
		 * CAD-style dimension lines: a line parallel to the measured side with ticks at both ends. Lines of flat shapes
		 * lie on the face towards the camera; overall lines are drawn as 3D beams with ticks.
		 */
		void dimensionLines(LabelSet labels, LabelSettings settings, float camX, float camY, float camZ) {
			int rgb = settings.textColor;
			int color = ARGB.color(Math.round(255 * settings.opacity), ARGB.red(rgb), ARGB.green(rgb), ARGB.blue(rgb));
			int overallColor = ARGB.color(Math.round(230 * settings.opacity), 0xFF, 0xD2, 0x4D);
			int n = labels.planeNormalAxis;
			float[] cam = {camX, camY, camZ};
			float face = 0;

			if (n >= 0) {
				boolean positive = cam[n] >= (labels.planeLow + labels.planeHigh) * 0.5F;
				face = positive ? labels.planeHigh + FACE_LIFT : labels.planeLow - FACE_LIFT;
			}

			float[] l = labels.lines;

			for (int i = 0; i < labels.lineCount; i++) {
				int o = i * LabelSet.LINE_STRIDE;
				int along = (int) l[o + 6];
				int across = (int) l[o + 7];
				boolean flatLine = across >= 0;

				if (flatLine ? !settings.showDimensions : !settings.showOverall) continue;

				for (int k = 0; k < 3; k++) {
					a[k] = Float.isNaN(l[o + k]) ? face : l[o + k];
					c[k] = Float.isNaN(l[o + 3 + k]) ? face : l[o + 3 + k];
				}

				float dist = distance(a[0], a[1], a[2], c[0], c[1], c[2], camX, camY, camZ);

				if (dist > settings.labelDistance) continue;

				float w = Math.min(0.02F * Math.max(1F, dist / 10F), 0.2F);
				float from = a[along];
				float to = c[along];

				if (flatLine) {
					float pos = a[across];
					float outward = l[o + 8];
					flat(along, from, to, across, pos - w * 0.5F, pos + w * 0.5F, n, face, color);
					// Extension ticks at both ends, reaching back to the section edge.
					float tickIn = pos - outward * 0.3F;
					float tickOut = pos + outward * TICK;
					float lo = Math.min(tickIn, tickOut);
					float hi = Math.max(tickIn, tickOut);
					flat(across, lo, hi, along, from, from + w, n, face, color);
					flat(across, lo, hi, along, to - w, to, n, face, color);
				} else {
					segment(a[0], a[1], a[2], c[0], c[1], c[2], w * 1.5F, overallColor);
					// Ticks perpendicular to the line: vertical for horizontal lines, along X for the height line.
					int tickAxis = along == 1 ? 0 : 1;

					for (int end = 0; end < 2; end++) {
						p[0] = a[0];
						p[1] = a[1];
						p[2] = a[2];
						p[along] = end == 0 ? from : to;
						float t0 = p[tickAxis] - 0.25F;
						float t1 = p[tickAxis] + 0.25F;
						float x0 = tickAxis == 0 ? t0 : p[0];
						float x1 = tickAxis == 0 ? t1 : p[0];
						float y0 = tickAxis == 1 ? t0 : p[1];
						float y1 = tickAxis == 1 ? t1 : p[1];
						segment(x0, y0, p[2], x1, y1, p[2], w * 1.5F, overallColor);
					}
				}
			}
		}

		/** An axis-aligned segment drawn as two crossed ribbons so it is visible from every angle. */
		void segment(float x0, float y0, float z0, float x1, float y1, float z1, float thickness, int color) {
			min[0] = Math.min(x0, x1);
			min[1] = Math.min(y0, y1);
			min[2] = Math.min(z0, z1);
			max[0] = Math.max(x0, x1);
			max[1] = Math.max(y0, y1);
			max[2] = Math.max(z0, z1);
			int along = max[0] - min[0] >= max[1] - min[1] && max[0] - min[0] >= max[2] - min[2] ? 0 : max[1] - min[1] >= max[2] - min[2] ? 1 : 2;
			int a1 = (along + 1) % 3;
			int a2 = (along + 2) % 3;
			float h = thickness * 0.5F;
			flat(along, min[along] - h, max[along] + h, a1, min[a1] - h, min[a1] + h, a2, min[a2], color);
			flat(along, min[along] - h, max[along] + h, a2, min[a2] - h, min[a2] + h, a1, min[a1], color);
		}

		/** A flat rectangle spanning [a0, a1] × [b0, b1] at coordinate {@code c} of axis {@code cAxis}. */
		void flat(int aAxis, float a0, float a1, int bAxis, float b0, float b1, int cAxis, float cc, int color) {
			vertex(aAxis, a0, bAxis, b0, cAxis, cc, color);
			vertex(aAxis, a1, bAxis, b0, cAxis, cc, color);
			vertex(aAxis, a1, bAxis, b1, cAxis, cc, color);
			vertex(aAxis, a0, bAxis, b1, cAxis, cc, color);
		}

		void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
			flat(0, x0, x1, 2, z0, z1, 1, y0, color);
			flat(0, x0, x1, 2, z0, z1, 1, y1, color);
			flat(0, x0, x1, 1, y0, y1, 2, z0, color);
			flat(0, x0, x1, 1, y0, y1, 2, z1, color);
			flat(2, z0, z1, 1, y0, y1, 0, x0, color);
			flat(2, z0, z1, 1, y0, y1, 0, x1, color);
		}

		/** The 12 edges of a box. */
		void boxEdges(float x0, float y0, float z0, float x1, float y1, float z1, float thickness, int color) {
			float[] lo = {x0, y0, z0};
			float[] hi = {x1, y1, z1};

			for (int along = 0; along < 3; along++) {
				if (hi[along] - lo[along] <= 0) continue;

				int a1 = (along + 1) % 3;
				int a2 = (along + 2) % 3;

				for (int corner = 0; corner < 4; corner++) {
					float c1 = (corner & 1) == 0 ? lo[a1] : hi[a1];
					float c2 = (corner & 2) == 0 ? lo[a2] : hi[a2];
					float[] s = new float[3];
					float[] e = new float[3];
					s[along] = lo[along];
					e[along] = hi[along];
					s[a1] = c1;
					e[a1] = c1;
					s[a2] = c2;
					e[a2] = c2;
					segment(s[0], s[1], s[2], e[0], e[1], e[2], thickness, color);
				}
			}
		}

		private void vertex(int aAxis, float av, int bAxis, float bv, int cAxis, float cv, int color) {
			p[aAxis] = av;
			p[bAxis] = bv;
			p[cAxis] = cv;
			buffer.addVertex(pose, p[0], p[1], p[2]).setColor(color);
		}
	}
}
