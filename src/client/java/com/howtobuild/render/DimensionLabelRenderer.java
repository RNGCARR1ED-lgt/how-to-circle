package com.howtobuild.render;

import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Quaternionf;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import com.howtobuild.client.BuildSession;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.config.LabelSettings;
import com.howtobuild.dimensions.LabelLayout;
import com.howtobuild.dimensions.LabelPlacement;

/**
 * Renders the text of every label in the cached {@link LabelSet}.
 *
 * <ul>
 *     <li><b>Section pop-ups</b> are placed by the chosen {@link LabelPlacement} (above, below, inside, outside, left or
 *     right of their section, or automatic) and joined to the section by an optional leader line.</li>
 *     <li><b>Dimension line labels</b> sit beside their CAD line, on the face of a flat shape towards the camera.</li>
 *     <li><b>Overall</b> width / length / height labels and the <b>summary</b> label above the shape.</li>
 * </ul>
 * Labels face the camera (or, with billboarding off, run along their section), optionally scale with distance, fade
 * in and out, and are laid out every frame with {@link LabelLayout} so overlapping labels are nudged apart or hidden.
 * Style options: text size, colour, opacity, background, border, padding and drawing through walls.
 */
public final class DimensionLabelRenderer {
	private static final float DEG = (float) (Math.PI / 180.0);
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final float BASE_SCALE = 0.025F;
	private static final float FADE_SPEED = 7F;

	private static final DimensionLabelRenderer INSTANCE = new DimensionLabelRenderer();

	private final LabelLayout layout = new LabelLayout();
	private final Quaternionf billboard = new Quaternionf();
	private final Quaternionf fixed = new Quaternionf();
	private final float[] p = new float[3];

	private LabelSet last;
	private float[] alpha = new float[0];
	private double[] rx = new double[0];
	private double[] ry = new double[0];
	private double[] rz = new double[0];
	private double[] anchorX = new double[0];
	private double[] anchorY = new double[0];
	private double[] anchorZ = new double[0];
	private float[] depth = new float[0];
	private float[] scale = new float[0];
	private int[] layoutSlot = new int[0];
	private long lastFrameNanos;

	private DimensionLabelRenderer() {
	}

	public static void render(LevelRenderContext context) {
		INSTANCE.renderLabels(context);
	}

	private void renderLabels(LevelRenderContext context) {
		HowToBuildConfig config = HowToBuildConfig.get();
		LabelSettings settings = config.labels;
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0 : Math.min(0.25F, (now - lastFrameNanos) / 1.0e9F);
		lastFrameNanos = now;

		BuildSession session = BuildSession.get();
		BuildSession.Resolved resolved = config.hologram.visible ? session.resolved() : null;
		LabelSet labels = resolved == null ? null : session.labels();

		if (labels == null || labels.count == 0) {
			last = null;
			return;
		}

		int total = labels.count;

		if (labels != last) {
			last = labels;
			alpha = new float[total];
			rx = new double[total];
			ry = new double[total];
			rz = new double[total];
			anchorX = new double[total];
			anchorY = new double[total];
			anchorZ = new double[total];
			depth = new float[total];
			scale = new float[total];
			layoutSlot = new int[total];
		}

		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Vec3 cam = context.levelState().cameraRenderState.pos;
		float yaw = camera.yRot() * DEG;
		float pitch = camera.xRot() * DEG;
		// Camera basis (Minecraft: yaw 0 looks towards +Z).
		double fx = -Math.sin(yaw) * Math.cos(pitch);
		double fy = -Math.sin(pitch);
		double fz = Math.cos(yaw) * Math.cos(pitch);
		double rgx = -Math.cos(yaw);
		double rgz = -Math.sin(yaw);
		// up = right × forward
		double ux = -rgz * fy;
		double uy = rgz * fx - rgx * fz;
		double uz = rgx * fy;
		billboard.rotationYXZ((float) Math.PI - yaw, -pitch, 0);

		BlockPos o = resolved.origin();
		double ax = o.getX() - cam.x;
		double ay = o.getY() - cam.y;
		double az = o.getZ() - cam.z;
		float[] camLocal = {(float) -ax, (float) -ay, (float) -az};
		int n = labels.planeNormalAxis;
		float lineLift = 0;

		if (n >= 0) {
			boolean positive = camLocal[n] >= (labels.planeLow + labels.planeHigh) * 0.5F;
			lineLift = positive ? labels.planeHigh + 0.12F : labels.planeLow - 0.12F;
		}

		layout.clear();

		for (int i = 0; i < total; i++) {
			int kind = labels.kinds[i];
			boolean enabled = switch (kind) {
				case LabelSet.SECTION -> settings.showPopups;
				case LabelSet.LINE -> settings.showDimensions;
				case LabelSet.OVERALL -> settings.showOverall;
				default -> settings.showSummary;
			};
			p[0] = labels.positions[i * 3];
			p[1] = labels.positions[i * 3 + 1];
			p[2] = labels.positions[i * 3 + 2];
			anchorX[i] = ax + p[0];
			anchorY[i] = ay + p[1];
			anchorZ[i] = az + p[2];

			if (kind == LabelSet.LINE && n >= 0) {
				p[n] = lineLift;
			} else if (kind == LabelSet.SECTION) {
				place(labels, i, settings, rgx, rgz);
			}

			double dx = ax + p[0];
			double dy = ay + p[1];
			double dz = az + p[2];
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
			double zf = dx * fx + dy * fy + dz * fz;
			rx[i] = dx;
			ry[i] = dy;
			rz[i] = dz;
			depth[i] = (float) zf;
			float size = kind == LabelSet.LINE ? 0.8F : kind == LabelSet.SUMMARY ? 1.15F : 1F;
			scale[i] = BASE_SCALE * settings.textSize * size * (settings.distanceScaling ? (float) Math.max(1.0, dist / 8.0) : 1F);
			layoutSlot[i] = -1;

			float range = switch (kind) {
				case LabelSet.SECTION -> settings.popupDistance;
				case LabelSet.SUMMARY -> settings.labelDistance * 2;
				default -> settings.labelDistance;
			};

			if (!enabled || dist > range || zf < 0.2) continue;

			double halfW = (labels.widths[i] * 0.5 + 2 + settings.padding) * scale[i] / zf;
			double halfH = (5.5 + settings.padding) * scale[i] / zf;
			double sx = (dx * rgx + dz * rgz) / zf;
			double sy = (dx * ux + dy * uy + dz * uz) / zf;
			layoutSlot[i] = layout.add(sx, sy, halfW, halfH, labels.priorities[i] - dist);
		}

		layout.solve();

		SubmitNodeCollector collector = context.submitNodeCollector();
		PoseStack poseStack = context.poseStack();
		float fade = Math.min(1F, dt * FADE_SPEED);
		boolean through = settings.throughWalls || config.hologram.seeThroughBlocks;
		// Depth-tested text stays crisp in front of the translucent hologram; "through walls" shows it through terrain.
		Font.DisplayMode displayMode = through ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
		boolean customPanel = settings.background && (settings.border || settings.padding > 1);

		for (int i = 0; i < total; i++) {
			int slot = layoutSlot[i];
			float target = slot >= 0 && layout.visible(slot) ? 1F : 0F;
			alpha[i] += (target - alpha[i]) * fade;

			float a = alpha[i] * settings.opacity;
			int textAlpha = Math.round(255 * a);

			if (textAlpha < 8 || depth[i] < 0.2F) continue;

			int kind = labels.kinds[i];
			double shift = slot >= 0 ? layout.shiftY(slot) * depth[i] : 0;
			double x = rx[i] + ux * shift;
			double y = ry[i] + uy * shift;
			double z = rz[i] + uz * shift;
			int width = labels.widths[i];
			int bgAlpha = settings.background ? Math.round(255 * a * settings.backgroundOpacity * (kind == LabelSet.LINE ? 0.75F : 1F)) : 0;
			int background = ARGB.color(bgAlpha, ARGB.red(settings.backgroundColor), ARGB.green(settings.backgroundColor), ARGB.blue(settings.backgroundColor));
			int color = ARGB.color(textAlpha, ARGB.red(settings.textColor), ARGB.green(settings.textColor), ARGB.blue(settings.textColor));

			if (kind == LabelSet.SECTION && settings.leaderLines && settings.placement != LabelPlacement.INSIDE) {
				leader(context, x, y, z, anchorX[i], anchorY[i], anchorZ[i], ARGB.color(Math.round(170 * a), ARGB.red(settings.borderColor),
						ARGB.green(settings.borderColor), ARGB.blue(settings.borderColor)), through);
			}

			poseStack.pushPose();
			poseStack.translate(x, y, z);
			poseStack.mulPose(settings.billboard || kind == LabelSet.LINE || kind == LabelSet.SUMMARY ? billboard : fixedOrientation(labels, i, camLocal));
			poseStack.scale(scale[i], -scale[i], scale[i]);

			if (customPanel && bgAlpha > 0) {
				panel(collector, poseStack, width, settings, background, ARGB.color(Math.round(255 * a), ARGB.red(settings.borderColor),
						ARGB.green(settings.borderColor), ARGB.blue(settings.borderColor)), through);
				background = 0;
			}

			collector.submitText(poseStack, -width / 2F, -4.5F, labels.texts[i], false, displayMode, FULL_BRIGHT, color, background, 0);
			poseStack.popPose();
		}
	}

	/** Moves a section pop-up from its default spot (top centre of the section) according to the placement setting. */
	private void place(LabelSet labels, int i, LabelSettings settings, double rightX, double rightZ) {
		int b = i * 6;
		int[] box = labels.boxes;
		float minX = box[b];
		float minY = box[b + 1];
		float minZ = box[b + 2];
		float maxX = box[b + 3] + 1;
		float maxY = box[b + 4] + 1;
		float maxZ = box[b + 5] + 1;
		float cx = (minX + maxX) / 2;
		float cy = (minY + maxY) / 2;
		float cz = (minZ + maxZ) / 2;
		float offset = settings.offset;

		switch (settings.placement) {
			case AUTOMATIC, ABOVE -> {
				p[0] = cx;
				p[1] = maxY + offset;
				p[2] = cz;
			}
			case BELOW -> {
				p[0] = cx;
				p[1] = minY - offset;
				p[2] = cz;
			}
			case INSIDE -> {
				p[0] = cx;
				p[1] = cy;
				p[2] = cz;
			}
			case OUTSIDE -> {
				// Away from the centre of the whole shape, along the dominant horizontal direction.
				float dx = cx - labels.centreX;
				float dz = cz - labels.centreZ;
				p[1] = cy;

				if (Math.abs(dx) >= Math.abs(dz)) {
					p[0] = dx >= 0 ? maxX + offset : minX - offset;
					p[2] = cz;
				} else {
					p[0] = cx;
					p[2] = dz >= 0 ? maxZ + offset : minZ - offset;
				}
			}
			case LEFT, RIGHT -> {
				float sign = settings.placement == LabelPlacement.RIGHT ? 1 : -1;
				float half = (float) (Math.abs(rightX) * (maxX - minX) + Math.abs(rightZ) * (maxZ - minZ)) / 2;
				p[0] = cx + (float) rightX * sign * (half + offset);
				p[1] = cy;
				p[2] = cz + (float) rightZ * sign * (half + offset);
			}
		}

		// Leader lines start at the nearest point of the section.
		anchorX[i] += Math.max(minX, Math.min(maxX, p[0])) - labels.positions[i * 3];
		anchorY[i] += Math.max(minY, Math.min(maxY, p[1])) - labels.positions[i * 3 + 1];
		anchorZ[i] += Math.max(minZ, Math.min(maxZ, p[2])) - labels.positions[i * 3 + 2];
	}

	/** A thin camera-facing ribbon from the label to its section (camera-relative coordinates). */
	private static void leader(LevelRenderContext context, double x0, double y0, double z0, double x1, double y1, double z1, int color, boolean through) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double dz = z1 - z0;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (length < 0.3) return;

		// side = direction × view, normalised to the ribbon half-width
		double mx = (x0 + x1) / 2;
		double my = (y0 + y1) / 2;
		double mz = (z0 + z1) / 2;
		double sx = dy * mz - dz * my;
		double sy = dz * mx - dx * mz;
		double sz = dx * my - dy * mx;
		double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);

		if (sl < 1e-6) return;

		double distance = Math.sqrt(mx * mx + my * my + mz * mz);
		double half = Math.min(0.08, 0.012 * Math.max(1, distance / 10)) / sl;
		float ox = (float) (sx * half);
		float oy = (float) (sy * half);
		float oz = (float) (sz * half);
		float ax = (float) x0;
		float ay = (float) y0;
		float az = (float) z0;
		float bx = (float) x1;
		float by = (float) y1;
		float bz = (float) z1;
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), HologramRenderTypes.hologram(through), (pose, buffer) -> {
			buffer.addVertex(pose, ax - ox, ay - oy, az - oz).setColor(color);
			buffer.addVertex(pose, bx - ox, by - oy, bz - oz).setColor(color);
			buffer.addVertex(pose, bx + ox, by + oy, bz + oz).setColor(color);
			buffer.addVertex(pose, ax + ox, ay + oy, az + oz).setColor(color);
		});
	}

	/** Background panel with padding and an optional border, slightly behind the text (text-space coordinates). */
	private static void panel(SubmitNodeCollector collector, PoseStack poseStack, int width, LabelSettings settings, int background, int border,
			boolean through) {
		float pad = settings.padding;
		float x0 = -width / 2F - 1 - pad;
		float x1 = width / 2F + pad;
		float y0 = -5.5F - pad;
		float y1 = 4.5F + pad;
		float z = 0.03F;
		boolean drawBorder = settings.border;
		collector.submitCustomGeometry(poseStack, HologramRenderTypes.hologram(through), (pose, buffer) -> {
			quad(pose, buffer, x0, y0, x1, y1, z, background);

			if (drawBorder) {
				float t = 0.75F;
				float zb = z - 0.005F;
				quad(pose, buffer, x0 - t, y0 - t, x1 + t, y0, zb, border);
				quad(pose, buffer, x0 - t, y1, x1 + t, y1 + t, zb, border);
				quad(pose, buffer, x0 - t, y0, x0, y1, zb, border);
				quad(pose, buffer, x1, y0, x1 + t, y1, zb, border);
			}
		});
	}

	private static void quad(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buffer, float x0, float y0, float x1, float y1, float z, int color) {
		buffer.addVertex(pose, x0, y0, z).setColor(color);
		buffer.addVertex(pose, x0, y1, z).setColor(color);
		buffer.addVertex(pose, x1, y1, z).setColor(color);
		buffer.addVertex(pose, x1, y0, z).setColor(color);
	}

	/**
	 * Upright orientation with the text running along the label's axis (used when billboarding is off), turned towards
	 * whichever side the camera is on so it is never mirrored.
	 */
	private Quaternionf fixedOrientation(LabelSet labels, int i, float[] camLocal) {
		int axis = labels.runAxis[i];
		float px = labels.positions[i * 3];
		float pz = labels.positions[i * 3 + 2];

		if (axis == 1) {
			// Vertical line: face the camera horizontally.
			float dx = camLocal[0] - px;
			float dz = camLocal[2] - pz;
			return fixed.rotationY((float) Math.atan2(dx, dz));
		}

		if (axis == 0) {
			return fixed.rotationY(camLocal[2] >= pz ? 0F : (float) Math.PI);
		}

		return fixed.rotationY(camLocal[0] >= px ? (float) (Math.PI / 2) : (float) (-Math.PI / 2));
	}
}
