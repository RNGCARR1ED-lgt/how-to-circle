package com.howtobuild.transform;

/**
 * Mirror planes. X mirrors across a plane perpendicular to the X axis (east ↔ west); Z across one perpendicular to Z
 * (north ↔ south); X + Z applies both, giving four-way symmetry. The transform is written so a Y plane can be added
 * later without changing callers.
 */
public enum MirrorAxis {
	X,
	Z,
	X_AND_Z;

	public boolean mirrorsX() {
		return this == X || this == X_AND_Z;
	}

	public boolean mirrorsZ() {
		return this == Z || this == X_AND_Z;
	}
}
