package com.howtobuild.palette;

/** Whether randomised materials repeat across groups (e.g. the steps of a spiral). */
public enum RandomSymmetry {
	/** Every block is chosen on its own. */
	INDEPENDENT,
	/** All blocks of one group (a spiral step) get the same material. */
	PER_STEP,
	/** The same arrangement repeats every revolution (step k and step k + steps per turn match). */
	PER_REVOLUTION
}
