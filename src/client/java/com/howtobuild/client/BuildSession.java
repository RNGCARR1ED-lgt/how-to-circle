package com.howtobuild.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.howtobuild.HowToBuild;
import com.howtobuild.commands.CommandPlan;
import com.howtobuild.commands.CommandPlanner;
import com.howtobuild.commands.StatePlacement;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.config.MaterialSlot;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.materials.MaterialResolver;
import com.howtobuild.render.LabelSet;
import com.howtobuild.render.RenderMesh;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.GeometryPipeline;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.transform.MirrorSettings;
import com.howtobuild.transform.MirrorTransform;

/**
 * The active preview: where it is, what it shows, and every cached stage derived from it.
 *
 * <pre>
 * settings ─(background thread)─► GeometryResult ─► mirror ─► resolved block states ─┬─► RenderMesh  (hologram)
 *                                                                                     ├─► LabelSet    (labels)
 *                                                                                     ├─► progress    (world comparison)
 *                                                                                     └─► CommandPlan (on demand)
 * </pre>
 *
 * Each stage is keyed by its inputs and rebuilt only when they change, so per-frame rendering never regenerates
 * geometry. Generation is pure and runs on a background thread; everything that touches Minecraft runs on the client
 * thread. The centre is tied to the current level, so a preview never appears in another world.
 */
public final class BuildSession {
	private static final BuildSession INSTANCE = new BuildSession();
	private static final ExecutorService GENERATOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "How to Build geometry");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	private record GenerationKey(String tool, ToolSettings settings, GenerationContext context) {
	}

	private @Nullable BlockPos anchor;
	private @Nullable BlockPos mirrorCentre;
	private @Nullable Object level;

	private @Nullable GenerationKey requested;
	private @Nullable CompletableFuture<GeometryResult> pending;
	private @Nullable GenerationKey pendingKey;
	private @Nullable GeometryResult geometry;
	private @Nullable GenerationKey geometryKey;

	private @Nullable Object resolvedKey;
	private @Nullable Resolved resolved;
	private @Nullable Object meshKey;
	private @Nullable RenderMesh mesh;
	private @Nullable Object labelKey;
	private @Nullable LabelSet labels;
	private @Nullable Object planKey;
	private @Nullable CommandPlan plan;
	private final ProgressTracker progress = new ProgressTracker();
	private int revision;
	private long generatedAt;

	/**
	 * Geometry with resolved block states. Placements are relative to {@code anchor}; {@code mirrorPlaneX/Z} are the
	 * doubled mirror plane coordinates relative to the anchor (see {@link MirrorTransform#planeDoubled}).
	 */
	public record Resolved(GeometryResult result, BlockPos anchor, BlockState[] states, boolean[] buildable, MirrorSettings mirror,
			int mirrorPlaneX, int mirrorPlaneZ) {
	}

	private BuildSession() {
	}

	public static BuildSession get() {
		return INSTANCE;
	}

	// ----------------------------------------------------------------- centre & world context

	public @Nullable BlockPos anchor() {
		return anchor;
	}

	public boolean hasAnchor() {
		return anchor != null && level == Minecraft.getInstance().level;
	}

	/** The block the preview is centred on, including the configured offset. */
	public @Nullable BlockPos origin() {
		if (!hasAnchor()) return null;

		HowToBuildConfig c = HowToBuildConfig.get();
		return anchor.offset(c.offsetX, c.offsetY, c.offsetZ);
	}

	public void setAnchor(BlockPos pos) {
		anchor = pos.immutable();
		level = Minecraft.getInstance().level;
	}

	/**
	 * Centres the preview on the player. With "lock to block centre" off, even-sized centres snap to the block corner
	 * nearest the player's exact position.
	 */
	public boolean setAnchorToPlayer() {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null) return false;

		Vec3 pos = client.player.position();
		HowToBuildConfig config = HowToBuildConfig.get();

		if (!config.lockToBlockCentre) {
			config.alignX = pos.x - Math.floor(pos.x) >= 0.5;
			config.alignZ = pos.z - Math.floor(pos.z) >= 0.5;
		}

		setAnchor(BlockPos.containing(pos));
		return true;
	}

	public @Nullable BlockPos mirrorCentre() {
		return mirrorCentre;
	}

	public void setMirrorCentre(@Nullable BlockPos pos) {
		mirrorCentre = pos == null ? null : pos.immutable();
	}

	public void clear() {
		anchor = null;
		level = null;
		mirrorCentre = null;
		resolved = null;
		resolvedKey = null;
		mesh = null;
		meshKey = null;
		labels = null;
		labelKey = null;
		plan = null;
		planKey = null;
		progress.reset();
	}

	public void onDisconnect() {
		clear();
	}

	// ----------------------------------------------------------------- generation

	/** Called every client tick: starts or adopts background generation and refreshes derived caches. */
	public void tick() {
		HowToBuildConfig config = HowToBuildConfig.get();
		BuildTool tool = config.activeTool();
		GenerationKey key = new GenerationKey(tool.id(), config.settings(tool), config.generationContext(tool));

		if (!key.equals(requested)) {
			requested = key;

			if (!key.equals(geometryKey) && !key.equals(pendingKey)) {
				pendingKey = key;
				pending = CompletableFuture.supplyAsync(() -> GeometryPipeline.generate(tool, key.settings(), key.context()), GENERATOR);
			}
		}

		if (pending != null && pending.isDone()) {
			try {
				GeometryResult result = pending.join();

				if (Objects.equals(pendingKey, requested)) {
					geometry = result;
					geometryKey = pendingKey;
					revision++;
					generatedAt = System.currentTimeMillis();
				}
			} catch (RuntimeException e) {
				HowToBuild.LOGGER.warn("Geometry generation failed", e);
			}

			pending = null;

			if (!Objects.equals(pendingKey, requested) && requested != null) {
				GenerationKey next = requested;
				pendingKey = next;
				BuildTool nextTool = config.activeTool();
				pending = CompletableFuture.supplyAsync(() -> GeometryPipeline.generate(nextTool, next.settings(), next.context()), GENERATOR);
			}
		}

		if (hasAnchor() && config.build.trackProgress) {
			Resolved r = resolved();

			if (r != null) progress.tick(r);
		}
	}

	public boolean isGenerating() {
		return pending != null;
	}

	/** The latest generated geometry (relative to the centre), or null before the first result. */
	public @Nullable GeometryResult geometry() {
		return geometry;
	}

	public int revision() {
		return revision;
	}

	/** Milliseconds since the last regeneration (used to flash freshly edited geometry). */
	public long sinceGenerated() {
		return System.currentTimeMillis() - generatedAt;
	}

	// ----------------------------------------------------------------- derived stages

	/** Mirrored geometry with resolved block states, or null if nothing is placed in this world. */
	public @Nullable Resolved resolved() {
		BlockPos origin = origin();

		if (geometry == null || origin == null) return null;

		HowToBuildConfig config = HowToBuildConfig.get();
		MirrorSettings mirror = config.mirror.toSettings();
		BlockPos mirrorAt = mirrorCentre != null && !config.mirror.useShapeCentre ? mirrorCentre : origin;
		List<MaterialSlot> slots = new ArrayList<>();

		for (MaterialRole role : MaterialRole.values()) {
			slots.add(config.materials.get(role).copy());
		}

		Object key = List.of(geometry, origin, mirror, mirrorAt, slots);

		if (!key.equals(resolvedKey) || resolved == null) {
			int centreX = mirrorAt.getX() - origin.getX();
			int centreZ = mirrorAt.getZ() - origin.getZ();
			GeometryResult result = MirrorTransform.apply(geometry, mirror, centreX, centreZ);
			MaterialResolver resolver = new MaterialResolver(config.materials);
			List<Placement> placements = result.placements();
			BlockState[] states = new BlockState[placements.size()];
			boolean[] buildable = new boolean[placements.size()];

			for (int i = 0; i < states.length; i++) {
				Placement p = placements.get(i);
				states[i] = resolver.resolve(p);
				buildable[i] = !p.mirrored() || mirror.mode().buildsMirror();
			}

			resolved = new Resolved(result, origin, states, buildable, mirror,
					MirrorTransform.planeDoubled(centreX, mirror.twoWideX(), mirror.alignX(), mirror.offsetX()),
					MirrorTransform.planeDoubled(centreZ, mirror.twoWideZ(), mirror.alignZ(), mirror.offsetZ()));
			resolvedKey = key;
			progress.reset();
		}

		return resolved;
	}

	public @Nullable RenderMesh mesh() {
		Resolved r = resolved();

		if (r == null) return null;

		HowToBuildConfig config = HowToBuildConfig.get();
		Object key = List.of(r, config.hologram.colorByMaterial, config.hologram.color, config.hologram.opacity, config.hologram.showCentre,
				progress.version());

		if (!key.equals(meshKey) || mesh == null) {
			mesh = RenderMesh.build(r, config.hologram, progress);
			meshKey = key;
		}

		return mesh;
	}

	public @Nullable LabelSet labels() {
		Resolved r = resolved();

		if (r == null) return null;

		HowToBuildConfig config = HowToBuildConfig.get();
		Object key = List.of(r, HowToBuildConfig.gson().toJson(config.labels), config.tool);

		if (!key.equals(labelKey) || labels == null) {
			labels = LabelSet.build(r, config.activeTool(), config.labels, config.materials);
			labelKey = key;
		}

		return labels;
	}

	/** The command plan for the current preview (built on demand and cached). */
	public CommandPlan plan() {
		Resolved r = resolved();

		if (r == null) return CommandPlan.EMPTY;

		HowToBuildConfig config = HowToBuildConfig.get();
		Object key = List.of(r, config.build.maxFillVolume, config.build.keepExisting);

		if (!key.equals(planKey) || plan == null) {
			MaterialResolver resolver = new MaterialResolver(config.materials);
			List<StatePlacement> blocks = new ArrayList<>();
			List<Placement> placements = r.result().placements();
			BlockPos o = r.anchor();

			for (int i = 0; i < placements.size(); i++) {
				if (!r.buildable()[i]) continue;

				Placement p = placements.get(i);
				blocks.add(new StatePlacement(o.getX() + p.x(), o.getY() + p.y(), o.getZ() + p.z(), resolver.commandString(r.states()[i])));
			}

			plan = CommandPlanner.plan(blocks, new CommandPlanner.Options(config.build.maxFillVolume, config.build.keepExisting));
			planKey = key;
		}

		return plan;
	}

	public ProgressTracker progress() {
		return progress;
	}
}
