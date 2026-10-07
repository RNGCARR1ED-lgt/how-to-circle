package com.howtobuild.transform;

/**
 * What happens with the mirrored copy.
 */
public enum MirrorMode {
	/** Show the mirror as a preview only; it is not built. */
	PREVIEW,
	/** Keep the original and add the mirrored copy (both are built). */
	DUPLICATE,
	/** Show and build only the mirrored result. */
	REPLACE;

	public boolean buildsMirror() {
		return this != PREVIEW;
	}

	public boolean keepsOriginal() {
		return this != REPLACE;
	}
}
