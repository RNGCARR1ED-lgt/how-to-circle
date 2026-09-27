package com.howtocircle.input;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import com.howtocircle.client.HologramManager;
import com.howtocircle.config.HowToCircleConfig;
import com.howtocircle.geometry.ResolvedDimensions;
import com.howtocircle.geometry.ShapePlacement;
import com.howtocircle.gui.CircleSettingsScreen;

/**
 * Centre selection mode.
 *
 * <ol>
 *     <li>Press the <i>Select centre</i> key (default J) or the GUI button.</li>
 *     <li>A pulsing holographic marker shows the targeted centre block, with the footprint of the shape around it. As
 *     with placing a block, the centre is the block in front of the face you look at; sneak to target the block
 *     itself.</li>
 *     <li>Left-click confirms: the hologram is generated there and the settings screen (if it was open) reopens with
 *     the new coordinates. Right-click or pressing the key again cancels.</li>
 * </ol>
 * While selecting, clicks never break, place or use anything.
 */
public final class CentreSelectionHandler {
	public static final double RANGE = 128;
	private static final CentreSelectionHandler INSTANCE = new CentreSelectionHandler();

	private boolean active;
	private boolean reopenScreen;
	private boolean suppressUntilReleased;
	private @Nullable BlockPos target;
	private @Nullable BlockPos previewFor;
	private @Nullable ShapePlacement previewPlacement;
	private @Nullable ResolvedDimensions previewDimensions;
	private float @Nullable [] previewBounds;

	private CentreSelectionHandler() {
	}

	public static CentreSelectionHandler get() {
		return INSTANCE;
	}

	public void register() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
				level.isClientSide() && blocksInteraction() ? InteractionResult.FAIL : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
				level.isClientSide() && blocksInteraction() ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) ->
				level.isClientSide() && blocksInteraction() ? InteractionResult.FAIL : InteractionResult.PASS);
	}

	public boolean isActive() {
		return active;
	}

	private boolean blocksInteraction() {
		return active || suppressUntilReleased;
	}

	/** Enters selection mode. {@code fromScreen} reopens the settings screen after confirming. */
	public void start(boolean fromScreen) {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null) return;

		active = true;
		reopenScreen = fromScreen;
		// Drop clicks that were queued before selection started.
		while (client.options.keyAttack.consumeClick()) {
			// discard
		}

		while (client.options.keyUse.consumeClick()) {
			// discard
		}

		updateTarget(client);
		client.player.sendSystemMessage(Component.translatable("message.how-to-circle.selecting"));
	}

	public void cancel() {
		if (!active) return;

		active = false;
		target = null;
		suppressUntilReleased = true;
		Minecraft client = Minecraft.getInstance();

		if (client.player != null) {
			client.player.sendSystemMessage(Component.translatable("message.how-to-circle.selection_cancelled"));
		}

		reopenIfNeeded(client);
	}

	/** Leaves selection mode without messages (e.g. when disconnecting). */
	public void cancelSilently() {
		active = false;
		reopenScreen = false;
		target = null;
	}

	public void toggle(boolean fromScreen) {
		if (active) {
			cancel();
		} else {
			start(fromScreen);
		}
	}

	/** Confirms the current target, or reports that no block is targeted. Returns whether a centre was set. */
	public boolean confirm() {
		Minecraft client = Minecraft.getInstance();

		if (!active || client.player == null) return false;

		if (target == null) {
			client.player.sendSystemMessage(Component.translatable("message.how-to-circle.no_target"));
			return false;
		}

		BlockPos centre = target;
		active = false;
		target = null;
		suppressUntilReleased = true;
		HologramManager.get().setAnchor(centre);
		HowToCircleConfig.get().showHologram = true;
		ResolvedDimensions dims = HowToCircleConfig.get().resolveDimensions();
		client.player.sendSystemMessage(Component.translatable("message.how-to-circle.centre_set",
				centre.getX(), centre.getY(), centre.getZ(), dims.width(), dims.height()));
		reopenIfNeeded(client);
		return true;
	}

	private void reopenIfNeeded(Minecraft client) {
		if (reopenScreen) {
			reopenScreen = false;
			client.gui.setScreen(new CircleSettingsScreen());
		}
	}

	/**
	 * Called at the start of every client tick, before vanilla processes key presses, so the clicks used for
	 * selection are consumed and never reach the game.
	 */
	public void onStartTick(Minecraft client) {
		if (suppressUntilReleased && !client.options.keyAttack.isDown() && !client.options.keyUse.isDown()) {
			suppressUntilReleased = false;
		}

		if (!active) return;

		if (client.player == null || client.level == null) {
			active = false;
			target = null;
			return;
		}

		updateTarget(client);

		if (client.gui.screen() != null) return;

		boolean confirmed = false;

		while (client.options.keyAttack.consumeClick()) {
			confirmed = true;
		}

		boolean cancelled = false;

		while (client.options.keyUse.consumeClick()) {
			cancelled = true;
		}

		if (cancelled) {
			cancel();
		} else if (confirmed) {
			confirm();
		}
	}

	private void updateTarget(Minecraft client) {
		if (client.player == null) {
			target = null;
			return;
		}

		HitResult hit = client.player.pick(RANGE, 0F, false);

		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = blockHit.getBlockPos();
			target = client.player.isShiftKeyDown() ? pos.immutable() : pos.relative(blockHit.getDirection()).immutable();
		} else {
			target = null;
		}
	}

	/** The block the marker is drawn at while selecting, or {@code null}. */
	public @Nullable BlockPos markerPos() {
		return active ? target : null;
	}

	/**
	 * Bounds of the shape that would be generated at the marker, relative to the marker block (6 floats), cached until
	 * the target or settings change.
	 */
	public float @Nullable [] previewBounds() {
		if (!active || target == null) return null;

		HowToCircleConfig config = HowToCircleConfig.get();
		ShapePlacement placement = config.placement();
		ResolvedDimensions dims = config.resolveDimensions();

		if (previewBounds == null || !target.equals(previewFor) || !placement.equals(previewPlacement) || !dims.equals(previewDimensions)) {
			float[] bounds = new float[6];
			int[] min = placement.offset(0, 0, dims.width(), dims.height());
			int[] max = placement.offset(dims.width() - 1, dims.height() - 1, dims.width(), dims.height());

			for (int axis = 0; axis < 3; axis++) {
				bounds[axis] = Math.min(min[axis], max[axis]);
				bounds[3 + axis] = Math.max(min[axis], max[axis]) + 1;
			}

			previewBounds = bounds;
			previewFor = target;
			previewPlacement = placement;
			previewDimensions = dims;
		}

		return previewBounds;
	}

	/** A short status line for the HUD while selecting. */
	public Component statusLine() {
		if (target == null) {
			return Component.translatable("hud.how-to-circle.no_target");
		}

		return Component.translatable("hud.how-to-circle.target", target.getX(), target.getY(), target.getZ());
	}
}
