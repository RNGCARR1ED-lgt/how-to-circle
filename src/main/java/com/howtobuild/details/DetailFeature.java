package com.howtobuild.details;

import java.util.Locale;

/**
 * Every optional detail a tool can generate. Details are real blocks (with roles), so they are rendered, labelled,
 * mirrored and command-built exactly like the main structure. Each tool declares which features it supports.
 */
public enum DetailFeature {
	// Shared
	EDGE_TRIM,
	INNER_RING,
	OUTER_RING,
	ALTERNATING,
	ACCENTS,
	CORNER_ACCENTS,
	VERTICAL_BANDS,
	HORIZONTAL_BANDS,
	SMOOTH_CURVES,
	// Cylinder
	TOP_RIM,
	BOTTOM_RIM,
	SUPPORT_BANDS,
	// Sphere
	LATITUDE_RINGS,
	LONGITUDE_RINGS,
	EQUATOR_BAND,
	POLE_CAPS,
	CROSS_BANDS,
	SEGMENTATION,
	// Dome
	RADIAL_RIBS,
	CROWN,
	BASE_RING,
	// Spiral staircase
	OUTER_RAIL,
	INNER_RAIL,
	SUPPORT_PILLARS,
	CENTRAL_COLUMN,
	STEP_TRIM,
	ALTERNATE_STEPS,
	LANDING,
	RING_SUPPORT,
	WALL_ATTACHMENT,
	// Corridor
	RIBBING,
	ARCH_FRAMES,
	KEYSTONE,
	CEILING_RIBS,
	SIDE_COLUMNS,
	FLOOR_BORDER,
	WALL_PANELS,
	LIGHT_RECESSES,
	ALTERNATING_ARCHES,
	ENTRY_FRAME;

	public String translationKey() {
		return "detail.howtobuild." + name().toLowerCase(Locale.ROOT);
	}
}
