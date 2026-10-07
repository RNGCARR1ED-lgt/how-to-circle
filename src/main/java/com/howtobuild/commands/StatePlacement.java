package com.howtobuild.commands;

/**
 * A block to build at an absolute world position, with its full block state in command syntax
 * (e.g. {@code minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]}).
 */
public record StatePlacement(int x, int y, int z, String state) {
}
