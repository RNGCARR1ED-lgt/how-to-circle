package com.howtobuild.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class StateTransformTest {
	private static final List<String> STATES = List.of(
			"minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]",
			"minecraft:oak_log[axis=x]",
			"minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]",
			"minecraft:oak_sign[rotation=3,waterlogged=false]",
			"minecraft:oak_fence[east=true,north=false,south=true,waterlogged=false,west=false]",
			"minecraft:chest[facing=south,type=left,waterlogged=false]",
			"minecraft:rail[shape=north_east]",
			"minecraft:stone_slab[type=top,waterlogged=true]",
			"minecraft:lever[face=floor,facing=west,powered=false]",
			"minecraft:stone");

	@Test
	void fourQuarterTurnsAreTheIdentity() {
		for (String state : STATES) {
			String s = state;

			for (int i = 0; i < 4; i++) {
				s = StateTransform.rotateY(s, 1);
			}

			assertEquals(state, s);
			assertEquals(StateTransform.rotateY(state, 2), StateTransform.rotateY(StateTransform.rotateY(state, 1), 1));
		}
	}

	@Test
	void mirrorsAndFlipsAreInvolutions() {
		for (String state : STATES) {
			assertEquals(state, StateTransform.mirror(StateTransform.mirror(state, true), true));
			assertEquals(state, StateTransform.mirror(StateTransform.mirror(state, false), false));
			assertEquals(state, StateTransform.flipVertical(StateTransform.flipVertical(state)));
		}
	}

	@Test
	void rotationTurnsDirectionsClockwise() {
		assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=inner_left,waterlogged=false]", StateTransform.rotateY(STATES.get(0), 1));
		assertEquals("minecraft:oak_log[axis=z]", StateTransform.rotateY(STATES.get(1), 1));
		assertEquals("minecraft:oak_sign[rotation=7,waterlogged=false]", StateTransform.rotateY(STATES.get(3), 1));
		assertEquals("minecraft:oak_fence[east=false,north=false,south=true,waterlogged=false,west=true]", StateTransform.rotateY(STATES.get(4), 1));
		assertEquals("minecraft:rail[shape=south_east]", StateTransform.rotateY(STATES.get(6), 1));
		assertEquals("minecraft:stone", StateTransform.rotateY("minecraft:stone", 3));
	}

	@Test
	void mirrorSwapsHandedness() {
		assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=inner_right,waterlogged=false]", StateTransform.mirror(STATES.get(0), true));
		assertEquals("minecraft:oak_stairs[facing=south,half=bottom,shape=inner_right,waterlogged=false]", StateTransform.mirror(STATES.get(0), false));
		assertEquals("minecraft:oak_door[facing=west,half=lower,hinge=right,open=false,powered=false]", StateTransform.mirror(STATES.get(2), true));
		assertEquals("minecraft:chest[facing=north,type=right,waterlogged=false]", StateTransform.mirror(STATES.get(5), false));
		assertEquals("minecraft:oak_log[axis=x]", StateTransform.mirror(STATES.get(1), true), "axes do not change in a mirror");
	}

	@Test
	void upsideDownSwapsHalvesAndAttachments() {
		assertEquals("minecraft:oak_stairs[facing=north,half=top,shape=inner_left,waterlogged=false]", StateTransform.flipVertical(STATES.get(0)));
		assertEquals("minecraft:stone_slab[type=bottom,waterlogged=true]", StateTransform.flipVertical(STATES.get(7)));
		assertEquals("minecraft:lever[face=ceiling,facing=west,powered=false]", StateTransform.flipVertical(STATES.get(8)));
	}

	@Test
	void propertiesParseAndCompose() {
		var props = StateTransform.properties(STATES.get(0));
		assertEquals("north", props.get("facing"));
		assertEquals("minecraft:oak_stairs", StateTransform.blockId(STATES.get(0)));
		assertEquals(STATES.get(0), StateTransform.compose("minecraft:oak_stairs", props));
	}
}
