package com.howtocircle.client;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import com.howtocircle.HowToCircle;
import com.howtocircle.config.HowToCircleConfig;
import com.howtocircle.dimensions.ConnectedSection;
import com.howtocircle.dimensions.LabelFormat;
import com.howtocircle.dimensions.SectionDetector;
import com.howtocircle.geometry.CentreSize;
import com.howtocircle.geometry.CircleGenerator;
import com.howtocircle.geometry.CircleShape;
import com.howtocircle.geometry.FillMode;
import com.howtocircle.geometry.ResolvedDimensions;
import com.howtocircle.geometry.ShapePlacement;
import com.howtocircle.geometry.ShapeType;
import com.howtocircle.render.HologramGeometry;

/**
 * Owns the current hologram: where it is, which shape it shows, and the cached geometry derived from both.
 *
 * <p>Everything expensive (shape generation, connected-section detection, world-space boxes and label text) is cached
 * and only recomputed when the relevant settings actually change. Rendering reads {@link #geometry()} every frame
 * without rebuilding anything.
 */
public final class HologramManager {
	private static final HologramManager INSTANCE = new HologramManager();

	private @Nullable BlockPos anchor;
	private @Nullable Object anchorLevel;

	private @Nullable ShapeKey shapeKey;
	private @Nullable CircleShape shape;
	private List<ConnectedSection> sections = List.of();
	private ResolvedDimensions dimensions = ResolvedDimensions.resolve(ShapeType.CIRCLE, 1, 1, CentreSize.AUTO);

	private @Nullable GeometryKey geometryKey;
	private @Nullable HologramGeometry geometry;
	private int rebuildCount;

	private HologramManager() {
	}

	public static HologramManager get() {
		return INSTANCE;
	}

	public @Nullable BlockPos anchor() {
		return anchor;
	}

	public boolean hasHologram() {
		return anchor != null;
	}

	/** Places (generates) the hologram with its centre at {@code pos} in the player's current level. */
	public void setAnchor(BlockPos pos) {
		anchor = pos.immutable();
		anchorLevel = Minecraft.getInstance().level;
		HowToCircle.LOGGER.debug("Hologram centre set to {}", anchor);
	}

	/**
	 * Centres the hologram on the player. With "lock to block centre" off, even-sized axes snap their centre to the
	 * block corner closest to the player's exact position instead of using the configured alignment.
	 */
	public boolean setAnchorToPlayer() {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null) return false;

		Vec3 pos = client.player.position();
		BlockPos block = BlockPos.containing(pos);
		HowToCircleConfig config = HowToCircleConfig.get();

		if (!config.lockToBlockCentre) {
			ShapePlacement placement = config.placement();
			config.alignPositiveU = fraction(pos, placement.widthAxis()) >= 0.5;
			config.alignPositiveV = fraction(pos, placement.heightAxis()) >= 0.5;
		}

		setAnchor(block);
		return true;
	}

	private static double fraction(Vec3 pos, ShapePlacement.Axis axis) {
		double value = switch (axis) {
			case X -> pos.x;
			case Y -> pos.y;
			case Z -> pos.z;
		};
		return value - Math.floor(value);
	}

	public void clear() {
		anchor = null;
		anchorLevel = null;
		geometry = null;
		geometryKey = null;
	}

	/** Called when the player leaves a world; holograms are per-session planning aids. */
	public void onDisconnect() {
		clear();
	}

	public ResolvedDimensions dimensions() {
		refreshShape();
		return dimensions;
	}

	public @Nullable CircleShape shape() {
		refreshShape();
		return shape;
	}

	public List<ConnectedSection> sections() {
		refreshShape();
		return sections;
	}

	/** Number of times the world geometry was rebuilt (used by tests to verify caching). */
	public int rebuildCount() {
		return rebuildCount;
	}

	/**
	 * The world-space geometry of the current hologram, or {@code null} if there is nothing to show in the current
	 * level. Rebuilt only when the shape, anchor or placement changed.
	 */
	public @Nullable HologramGeometry geometry() {
		if (anchor == null || !HowToCircleConfig.get().showHologram) return null;

		if (anchorLevel != Minecraft.getInstance().level) return null;

		refreshShape();
		HowToCircleConfig config = HowToCircleConfig.get();
		GeometryKey key = new GeometryKey(Objects.requireNonNull(shapeKey), anchor, config.placement(), config.labelFormat);

		if (!key.equals(geometryKey) || geometry == null) {
			geometry = HologramGeometry.build(Objects.requireNonNull(shape), sections, anchor, key.placement(), config.labelFormat);
			geometryKey = key;
			rebuildCount++;
		}

		return geometry;
	}

	private void refreshShape() {
		HowToCircleConfig config = HowToCircleConfig.get();
		ResolvedDimensions resolved = config.resolveDimensions();
		ShapeKey key = new ShapeKey(resolved.width(), resolved.height(), config.fillMode);

		if (key.equals(shapeKey) && shape != null) {
			dimensions = resolved;
			return;
		}

		long start = System.nanoTime();
		shape = CircleGenerator.generate(key.width(), key.height(), key.fillMode());
		sections = SectionDetector.detect(shape);
		dimensions = resolved;
		shapeKey = key;
		HowToCircle.LOGGER.debug("Generated {} with {} sections in {} µs", shape, sections.size(), (System.nanoTime() - start) / 1000);
	}

	private record ShapeKey(int width, int height, FillMode fillMode) {
	}

	private record GeometryKey(ShapeKey shape, BlockPos anchor, ShapePlacement placement, LabelFormat format) {
	}
}
