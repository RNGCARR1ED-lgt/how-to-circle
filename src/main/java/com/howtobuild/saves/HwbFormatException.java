package com.howtobuild.saves;

import java.io.IOException;

/** A saved build file that is not a valid How to Build file; the message explains why. */
public final class HwbFormatException extends IOException {
	public HwbFormatException(String message) {
		super(message);
	}
}
