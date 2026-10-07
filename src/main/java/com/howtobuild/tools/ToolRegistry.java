package com.howtobuild.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.howtobuild.tools.impl.CircleTool;
import com.howtobuild.tools.impl.CorridorTool;
import com.howtobuild.tools.impl.CylinderTool;
import com.howtobuild.tools.impl.DomeTool;
import com.howtobuild.tools.impl.OvalTool;
import com.howtobuild.tools.impl.RectangleTool;
import com.howtobuild.tools.impl.SphereTool;
import com.howtobuild.tools.impl.SpiralStaircaseTool;
import com.howtobuild.tools.impl.SquareTool;

/**
 * All geometry tools, in GUI order. Adding a tool means writing one {@link BuildTool} class and adding one line here;
 * the GUI, config, presets, labels, mirror and command building pick it up automatically.
 */
public final class ToolRegistry {
	private static final Map<String, BuildTool> TOOLS = new LinkedHashMap<>();

	static {
		register(new CircleTool());
		register(new OvalTool());
		register(new CylinderTool());
		register(new SpiralStaircaseTool());
		register(new SphereTool());
		register(new DomeTool());
		register(new CorridorTool());
		register(new SquareTool());
		register(new RectangleTool());
	}

	private ToolRegistry() {
	}

	public static void register(BuildTool tool) {
		if (TOOLS.putIfAbsent(tool.id(), tool) != null) {
			throw new IllegalStateException("Duplicate tool id " + tool.id());
		}
	}

	public static List<BuildTool> all() {
		return List.copyOf(TOOLS.values());
	}

	public static Optional<BuildTool> byId(String id) {
		return Optional.ofNullable(TOOLS.get(id));
	}

	public static BuildTool get(String id) {
		return byId(id).orElseGet(() -> TOOLS.values().iterator().next());
	}
}
