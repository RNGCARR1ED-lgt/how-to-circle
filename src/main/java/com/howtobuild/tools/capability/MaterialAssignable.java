package com.howtobuild.tools.capability;

import java.util.Set;

import com.howtobuild.geometry.MaterialRole;

/**
 * The tool emits several material roles, so the player can assign different blocks to its parts.
 */
public interface MaterialAssignable {
	Set<MaterialRole> roles();
}
