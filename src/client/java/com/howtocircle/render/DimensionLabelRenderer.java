package com.howtocircle.render;

import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Quaternionf;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import com.howtocircle.client.HologramManager;
import com.howtocircle.config.HowToCircleConfig;
import com.howtocircle.dimensions.LabelLayout;

/**
 * Renders the text of every dimension label.
 *
 * <p>There are two kinds of label:
 * <ul>
 *     <li><b>Dimension labels</b> sit on the CAD-style dimension line beside each section (e.g. "7 by 1" beside a
 *     line, "4" and "3" along the sides of a 4 by 3 rectangle).</li>
 *     <li><b>Pop-up holograms</b> float {@code labelOffset} blocks above each section and read e.g. "7 by 1". They fade
 *     in when the player comes within the pop-up distance and fade out when they leave or when a label would be
 *     covered.</li>
 * </ul>
 * Labels always face the camera (pop-ups optionally face along their section), scale with distance so they stay
 * readable, and are laid out every frame with {@link LabelLayout} so overlapping labels are nudged apart or hidden.
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
	private final float[] camLocal = new float[3];
	private final float[] p = new float[3];

	private HologramGeometry lastGeometry;
	private float[] alpha = new float[0];
	private double[] rx = new double[0];
	private double[] ry = new double[0];
	private double[] rz = new double[0];
	private float[] depth = new float[0];
	private float[] scale = new float[0];
	private int[] layoutSlot = new int[0];
	private long lastFrameNanos;

	private DimensionLabelRenderer() {
	}

	public static boolean dimensionLinesEnabled() {
		return HowToCircleConfig.get().showDimensions;
	}

	public static float labelRange() {
		return HowToCircleConfig.get().labelDistance;
	}

	public static void render(LevelRenderContext context) {
		INSTANCE.renderLabels(context);
	}

	private void renderLabels(LevelRenderContext context) {
		HologramGeometry g = HologramManager.get().geometry();
		HowToCircleConfig config = HowToCircleConfig.get();
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0 : Math.min(0.25F, (now - lastFrameNanos) / 1.0e9F);
		lastFrameNanos = now;

		if (g == null) {
			lastGeometry = null;
			return;
		}

		int popupCount = g.sectionCount;
		int total = popupCount + g.dimensionLineCount;

		if (g != lastGeometry) {
			lastGeometry = g;
			alpha = new float[total];
			rx = new double[total];
			ry = new double[total];
			rz = new double[total];
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

		double ax = g.anchor.getX() - cam.x;
		double ay = g.anchor.getY() - cam.y;
		double az = g.anchor.getZ() - cam.z;
		int n = g.axisN;
		camLocal[0] = (float) -ax;
		camLocal[1] = (float) -ay;
		camLocal[2] = (float) -az;
		boolean positiveFace = camLocal[n] >= (g.normalMin + g.normalMax) * 0.5F;
		float labelLift = positiveFace ? g.normalMax + 0.12F : g.normalMin - 0.12F;
		layout.clear();

		for (int i = 0; i < total; i++) {
			boolean popup = i < popupCount;
			boolean enabled;

			if (popup) {
				int o = i * 6;
				p[0] = (g.boxes[o] + g.boxes[o + 3]) * 0.5F;
				p[1] = g.boxes[o + 4] + config.labelOffset;
				p[2] = (g.boxes[o + 2] + g.boxes[o + 5]) * 0.5F;
				enabled = config.showPopups;
			} else {
				int line = i - popupCount;
				p[g.lineAlong[line]] = (g.lineFrom[line] + g.lineTo[line]) * 0.5F;
				p[g.lineAcross[line]] = g.linePos[line] + g.lineOutward[line] * 0.35F;
				p[n] = labelLift;
				enabled = config.showDimensions;
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
			scale[i] = BASE_SCALE * config.textSize * (float) Math.max(1.0, dist / 8.0) * (popup ? 1F : 0.8F);
			layoutSlot[i] = -1;

			float range = popup ? config.popupDistance : config.labelDistance;

			if (!enabled || dist > range || zf < 0.2) continue;

			int textWidth = popup ? g.sectionTextWidth[i] : g.lineTextWidth[i - popupCount];
			double halfW = (textWidth * 0.5 + 2) * scale[i] / zf;
			double halfH = 5.5 * scale[i] / zf;
			double sx = (dx * rgx + dz * rgz) / zf;
			double sy = (dx * ux + dy * uy + dz * uz) / zf;
			int section = popup ? i : g.lineSection[i - popupCount];
			double priority = (popup ? 1.0e6 : 0) + g.sections[section].blockCount() - dist;
			layoutSlot[i] = layout.add(sx, sy, halfW, halfH, priority);
		}

		layout.solve();

		SubmitNodeCollector collector = context.submitNodeCollector();
		PoseStack poseStack = context.poseStack();
		float fade = Math.min(1F, dt * FADE_SPEED);
		int bgRgb = config.labelBackgroundColor;
		int textRgb = config.labelColor;

		for (int i = 0; i < total; i++) {
			int slot = layoutSlot[i];
			float target = slot >= 0 && layout.visible(slot) ? 1F : 0F;
			alpha[i] += (target - alpha[i]) * fade;

			float a = alpha[i] * config.labelOpacity;
			int textAlpha = Math.round(255 * a);

			if (textAlpha < 8 || depth[i] < 0.2F) continue;

			boolean popup = i < popupCount;
			double shift = slot >= 0 ? layout.shiftY(slot) * depth[i] : 0;
			FormattedCharSequence text = popup ? g.sectionText[i] : g.lineText[i - popupCount];
			int width = popup ? g.sectionTextWidth[i] : g.lineTextWidth[i - popupCount];
			int background = ARGB.color(Math.round(255 * a * (popup ? 0.62F : 0.45F)), ARGB.red(bgRgb), ARGB.green(bgRgb), ARGB.blue(bgRgb));
			int color = ARGB.color(textAlpha, ARGB.red(textRgb), ARGB.green(textRgb), ARGB.blue(textRgb));

			poseStack.pushPose();
			poseStack.translate(rx[i] + ux * shift, ry[i] + uy * shift, rz[i] + uz * shift);
			poseStack.mulPose(popup && !config.popupsFacePlayer ? fixedOrientation(g, i, camLocal) : billboard);
			poseStack.scale(scale[i], -scale[i], scale[i]);
			collector.submitText(poseStack, -width / 2F, -4.5F, text, false, Font.DisplayMode.SEE_THROUGH, FULL_BRIGHT, color, background, 0);
			poseStack.popPose();
		}
	}

	/**
	 * Upright orientation with the text running along the section (for pop-ups that do not face the player), turned
	 * towards whichever side the camera is on so it is never mirrored.
	 */
	private Quaternionf fixedOrientation(HologramGeometry g, int section, float[] camLocal) {
		int o = section * 6;
		float sizeX = g.boxes[o + 3] - g.boxes[o];
		float sizeZ = g.boxes[o + 5] - g.boxes[o + 2];
		boolean runsAlongX = sizeX >= sizeZ;

		if (runsAlongX) {
			float centreZ = (g.boxes[o + 2] + g.boxes[o + 5]) * 0.5F;
			return fixed.rotationY(camLocal[2] >= centreZ ? 0F : (float) Math.PI);
		}

		float centreX = (g.boxes[o] + g.boxes[o + 3]) * 0.5F;
		return fixed.rotationY(camLocal[0] >= centreX ? (float) (Math.PI / 2) : (float) (-Math.PI / 2));
	}
}
