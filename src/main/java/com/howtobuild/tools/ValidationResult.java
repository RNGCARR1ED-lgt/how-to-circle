package com.howtobuild.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of validating tool settings before generation. Warnings are shown inline in the GUI and generation still
 * happens; errors stop generation (the preview is cleared and the reason shown) so invalid input never reaches the
 * renderer.
 */
public final class ValidationResult {
	private final List<String> warnings = new ArrayList<>();
	private final List<String> errors = new ArrayList<>();

	public ValidationResult warn(String message) {
		warnings.add(message);
		return this;
	}

	public ValidationResult error(String message) {
		errors.add(message);
		return this;
	}

	public ValidationResult warnIf(boolean condition, String message) {
		if (condition) warn(message);
		return this;
	}

	public ValidationResult errorIf(boolean condition, String message) {
		if (condition) error(message);
		return this;
	}

	public List<String> warnings() {
		return warnings;
	}

	public List<String> errors() {
		return errors;
	}

	public boolean ok() {
		return errors.isEmpty();
	}
}
