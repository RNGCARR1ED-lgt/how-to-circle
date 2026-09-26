package com.howtocircle.render;

import java.util.Optional;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import com.howtocircle.HowToCircle;

/**
 * Render types for the translucent hologram.
 *
 * <p>The normal mode uses vanilla's translucent, depth-tested debug filled box type, so terrain correctly hides the
 * hologram. The optional "see through blocks" mode uses a copy of the same pipeline without a depth test, which is
 * useful for planning underground or inside existing builds.
 */
public final class HologramRenderTypes {
	private static final RenderPipeline SEE_THROUGH_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(HowToCircle.id("pipeline/hologram_see_through"))
					.withDepthStencilState(Optional.empty())
					.withCull(false)
					.build());

	private static final RenderType SEE_THROUGH = RenderType.create(
			HowToCircle.MOD_ID + ":hologram_see_through",
			RenderSetup.builder(SEE_THROUGH_PIPELINE).sortOnUpload().createRenderSetup());

	private HologramRenderTypes() {
	}

	/** Forces class initialisation so the pipeline is registered during client start-up. */
	public static void init() {
	}

	public static RenderType hologram(boolean seeThrough) {
		return seeThrough ? SEE_THROUGH : RenderTypes.debugFilledBox();
	}
}
