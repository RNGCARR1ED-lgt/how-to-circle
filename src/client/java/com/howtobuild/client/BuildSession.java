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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import com.howtobuild.HowToBuild;
import com.howtobuild.commands.CommandPlan;
import com.howtobuild.commands.CommandPlanner;
import com.howtobuild.commands.MaterialCounter;
import com.howtobuild.commands.StatePlacement;
import com.howtobuild.config.HowToBuildConfig;
import com.howtobuild.config.MaterialSlot;
import com.howtobuild.geometry.GeometryResult;
import com.howtobuild.geometry.MaterialRole;
import com.howtobuild.geometry.Placement;
import com.howtobuild.geometry.WorldTransform;
import com.howtobuild.materials.MaterialResolver;
import com.howtobuild.palette.Randomiser;
import com.howtobuild.render.LabelSet;
import com.howtobuild.render.RenderMesh;
import com.howtobuild.tools.BuildTool;
import com.howtobuild.tools.GenerationContext;
import com.howtobuild.tools.GeometryPipeline;
import com.howtobuild.tools.HeightMap;
import com.howtobuild.tools.ToolRegistry;
import com.howtobuild.tools.ToolSettings;
import com.howtobuild.tools.impl.TerrainTool;
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
	private @Nullable Object countKey;
	private List<MaterialCounter.Count> counts = List.of();
	private @Nullable Object heightKey;
	private HeightMap heights = HeightMap.NONE;
	private int heightRevision;
	private final ProgressTracker progress = new ProgressTracker();
	private int revision;
	private long generatedAt;

	/**
	 * Geometry with resolved block states. Placements are relative to {@code origin} (= the transform's origin); {@code mirrorPlaneX/Z} are the
	 * doubled mirror plane coordinates relative to the anchor (see {@link MirrorTransform#planeDoubled}).
	 */
	public record Resolved(GeometryResult result, BlockPos origin, WorldTransform transform, BlockState[] states, boolean[] buildable,
			MirrorSettings mirror, int mirrorPlaneX, int mirrorPlaneZ, java.util.Set<String> missingBlocks) {
		/** World position of placement {@code i}. */
		public BlockPos worldPos(int i) {
			Placement p = result.placements().get(i);
			return new BlockPos(transform.x(p.x()), transform.y(p.y()), transform.z(p.z()));
		}
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

	/**
	 * The single anchor → world transform: the selected centre plus the configured X / Y / Z offset. Every consumer
	 * (hologram, labels, progress, command building) places blocks through this, so they cannot disagree.
	 */
	public @Nullable WorldTransform transform() {
		if (!hasAnchor()) return null;

		HowToBuildConfig c = HowToBuildConfig.get();
		return new WorldTransform(anchor.getX(), anchor.getY(), anchor.getZ(), c.offsetX, c.offsetY, c.offsetZ);
	}

	/** The block the preview is centred on, including the configured offset. */
	public @Nullable BlockPos origin() {
		WorldTransform t = transform();
		return t == null ? null : new BlockPos(t.originX(), t.originY(), t.originZ());
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
		GenerationContext context = config.generationContext(tool);

		if (tool instanceof TerrainTool) context = context.withPalettes(context.palettes().withHeights(heightSnapshot(config.settings(tool), context)));

		GenerationKey key = new GenerationKey(tool.id(), config.settings(tool), context);

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

	/**
	 * The existing ground around the centre, for terrain blending: the top solid block of every column (relative to the
	 * centre's Y, in the tool's unrotated coordinates). Taken once per centre / size / rotation, not every tick, so a
	 * terrain that was just built does not feed back into its own preview; {@link #resampleGround()} takes it again.
	 */
	private HeightMap heightSnapshot(ToolSettings s, GenerationContext ctx) {
		WorldTransform t = transform();
		ClientLevel world = Minecraft.getInstance().level;

		if (t == null || world == null) return HeightMap.NONE;

		int half = Math.max(s.getInt("width"), s.has("length") ? s.getInt("length") : 0) / 2 + 4;
		Object key = List.of(t.originX(), t.originY(), t.originZ(), half, ctx.rotation(), System.identityHashCode(world), heightRevision);

		if (key.equals(heightKey)) return heights;

		int size = 2 * half + 1;
		int[] h = new int[size * size];

		for (int v = 0; v < size; v++) {
			for (int u = 0; u < size; u++) {
				int[] r = GeometryPipeline.rotateXZ(u - half, v - half, ctx.rotation());
				int wx = t.originX() + r[0];
				int wz = t.originZ() + r[1];
				h[v * size + u] = world.getChunkSource().hasChunk(wx >> 4, wz >> 4)
						? world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1 - t.originY()
						: HeightMap.UNKNOWN;
			}
		}

		heights = new HeightMap(-half, -half, size, size, h);
		heightKey = key;
		return heights;
	}

	/** Samples the ground under a terrain preview again (e.g. after the surroundings changed). */
	public void resampleGround() {
		heightRevision++;
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
		WorldTransform transform = transform();

		if (geometry == null || transform == null) return null;

		BlockPos origin = new BlockPos(transform.originX(), transform.originY(), transform.originZ());

		HowToBuildConfig config = HowToBuildConfig.get();
		MirrorSettings mirror = config.mirror.toSettings();
		BlockPos mirrorAt = mirrorCentre != null && !config.mirror.useShapeCentre ? mirrorCentre : origin;
		List<MaterialSlot> slots = new ArrayList<>();

		for (MaterialRole role : MaterialRole.values()) {
			slots.add(config.materials.get(role).copy());
		}

		Object key = List.of(geometry, origin, mirror, mirrorAt, slots, new java.util.LinkedHashMap<>(config.materialOverrides));

		if (!key.equals(resolvedKey) || resolved == null) {
			int centreX = mirrorAt.getX() - origin.getX();
			int centreZ = mirrorAt.getZ() - origin.getZ();
			GeometryResult result = MirrorTransform.apply(geometry, mirror, centreX, centreZ);

			if (geometryKey != null && mirror.enabled()) {
				BuildTool tool = ToolRegistry.get(geometryKey.tool());
				result = Randomiser.randomiseMirrored(result, tool.randomisation(geometryKey.settings(), geometryKey.context()));
			}

			MaterialResolver resolver = new MaterialResolver(config.materials, config.materialOverrides);
			List<Placement> placements = result.placements();
			BlockState[] states = new BlockState[placements.size()];
			boolean[] buildable = new boolean[placements.size()];

			for (int i = 0; i < states.length; i++) {
				Placement p = placements.get(i);
				states[i] = resolver.resolve(p, result.materials());
				// Placeholders for blocks missing from this game are shown but never built.
				buildable[i] = (!p.mirrored() || mirror.mode().buildsMirror()) && !resolver.isMissing(p, result.materials());
			}

			resolved = new Resolved(result, origin, transform, states, buildable, mirror,
					MirrorTransform.planeDoubled(centreX, mirror.twoWideX(), mirror.alignX(), mirror.offsetX()),
					MirrorTransform.planeDoubled(centreZ, mirror.twoWideZ(), mirror.alignZ(), mirror.offsetZ()), java.util.Set.copyOf(resolver.missing()));
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
			WorldTransform t = r.transform();

			for (int i = 0; i < placements.size(); i++) {
				if (!r.buildable()[i]) continue;

				Placement p = placements.get(i);
				blocks.add(new StatePlacement(t.x(p.x()), t.y(p.y()), t.z(p.z()), resolver.commandString(r.states()[i])));
			}

			plan = CommandPlanner.plan(blocks, new CommandPlanner.Options(config.build.maxFillVolume, config.build.keepExisting));
			planKey = key;
		}

		return plan;
	}

	/**
	 * Exact material requirements of what would be built (mirror included, missing blocks excluded): one entry per block
	 * with the count of every exact state, sorted by count. Taken from the resolved states, so it always matches the
	 * hologram and the commands.
	 */
	public List<MaterialCounter.Count> materialCounts() {
		Resolved r = resolved();

		if (r == null) return List.of();

		if (r != countKey) {
			MaterialResolver resolver = new MaterialResolver(HowToBuildConfig.get().materials);
			List<String> states = new ArrayList<>();

			for (int i = 0; i < r.states().length; i++) {
				if (r.buildable()[i]) states.add(resolver.commandString(r.states()[i]));
			}

			counts = MaterialCounter.count(states);
			countKey = r;
		}

		return counts;
	}

	public ProgressTracker progress() {
		return progress;
	}
}
