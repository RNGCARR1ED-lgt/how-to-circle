package com.howtobuild.input;

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

import com.howtobuild.client.BuildSession;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.geometry.Box;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.gui.HowToBuildScreen;

/**
 * Centre selection mode, for the shape centre or the mirror centre.
 *
 * <ol>
 *     <li>Press the <i>Select centre</i> key (default J), the <i>Select mirror centre</i> key or a GUI button.</li>
 *     <li>A pulsing holographic marker shows the targeted block; for the shape centre the footprint of the current
 *     shape is outlined around it. As with placing a block, the target is the block in front of the face you look at;
 *     sneak to target the block itself.</li>
 *     <li>Left-click confirms. Right-click or pressing the key again cancels. If the settings screen was open it
 *     reopens afterwards.</li>
 * </ol>
 * While selecting, clicks never break, place or use anything.
 */
public final class CentreSelectionHandler {
	public static final double RANGE = 128;
	private static final CentreSelectionHandler INSTANCE = new CentreSelectionHandler();

	public enum Target {
		SHAPE,
		MIRROR
	}

	private boolean active;
	private Target mode = Target.SHAPE;
	private boolean reopenScreen;
	private boolean suppressUntilReleased;
	private @Nullable BlockPos target;

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

	public Target mode() {
		return mode;
	}

	private boolean blocksInteraction() {
		return active || suppressUntilReleased;
	}

	/** Enters selection mode. {@code fromScreen} reopens the settings screen afterwards. */
	public void start(Target what, boolean fromScreen) {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null) return;

		active = true;
		mode = what;
		reopenScreen = fromScreen;
		// Drop clicks that were queued before selection started.
		while (client.options.keyAttack.consumeClick()) {
			// discard
		}

		while (client.options.keyUse.consumeClick()) {
			// discard
		}

		updateTarget(client);
		client.player.sendSystemMessage(Component.translatable(what == Target.SHAPE ? "message.howtobuild.selecting" : "message.howtobuild.selecting_mirror"));
	}

	public void cancel() {
		if (!active) return;

		active = false;
		target = null;
		suppressUntilReleased = true;
		Minecraft client = Minecraft.getInstance();

		if (client.player != null) {
			client.player.sendSystemMessage(Component.translatable("message.howtobuild.selection_cancelled"));
		}

		reopenIfNeeded(client);
	}

	/** Leaves selection mode without messages (e.g. when disconnecting). */
	public void cancelSilently() {
		active = false;
		reopenScreen = false;
		target = null;
	}

	public void toggle(Target what, boolean fromScreen) {
		if (active) {
			cancel();
		} else {
			start(what, fromScreen);
		}
	}

	/** Confirms the current target, or reports that no block is targeted. Returns whether a centre was set. */
	public boolean confirm() {
		Minecraft client = Minecraft.getInstance();

		if (!active || client.player == null) return false;

		if (target == null) {
			client.player.sendSystemMessage(Component.translatable("message.howtobuild.no_target"));
			return false;
		}

		BlockPos centre = target;
		active = false;
		target = null;
		suppressUntilReleased = true;
		HowToBuildConfig config = HowToBuildConfig.get();

		if (mode == Target.SHAPE) {
			BuildSession.get().setAnchor(centre);
			config.hologram.visible = true;
			client.player.sendSystemMessage(Component.translatable("message.howtobuild.centre_set", centre.getX(), centre.getY(), centre.getZ()));
		} else {
			BuildSession.get().setMirrorCentre(centre);
			config.mirror.useShapeCentre = false;
			config.mirror.enabled = true;
			client.player.sendSystemMessage(Component.translatable("message.howtobuild.mirror_centre_set", centre.getX(), centre.getY(), centre.getZ()));
		}

		HowToBuildConfig.save();
		reopenIfNeeded(client);
		return true;
	}

	private void reopenIfNeeded(Minecraft client) {
		if (reopenScreen) {
			reopenScreen = false;
			client.gui.setScreen(new HowToBuildScreen());
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

	/** Marker colour: amber for the shape centre, violet for the mirror centre. */
	public int markerColor() {
		return mode == Target.SHAPE ? 0xFFD24D : 0xB070FF;
	}

	/** Bounds of the current shape if it were centred on the marker, relative to the marker block. */
	public @Nullable Box previewBounds() {
		if (!active || target == null || mode != Target.SHAPE) return null;

		GeometryResult geometry = BuildSession.get().geometry();
		Box bounds = geometry == null ? null : geometry.bounds();

		if (bounds == null) return null;

		HowToBuildConfig c = HowToBuildConfig.get();
		return bounds.offset(c.offsetX, c.offsetY, c.offsetZ);
	}

	/** A short status line for the HUD while selecting. */
	public Component statusLine() {
		if (target == null) {
			return Component.translatable("hud.howtobuild.no_target");
		}

		return Component.translatable("hud.howtobuild.target", target.getX(), target.getY(), target.getZ());
	}
}
