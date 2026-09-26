# How to Circle

A client-side Fabric mod for **Minecraft 26.2** that shows block-perfect circles and ovals as holograms, so you can plan
a build before placing anything.

Enter a width and height, pick a centre, and the mod shows every block of the shape as a translucent blueprint. Blocks
that line up in a straight line are merged into one **connected section** and labelled like a CAD drawing: `7 by 1`,
`4 by 3`, and so on. It never places, breaks or changes real blocks, and it doesn't spawn entities.

## Features

- **Circles and ovals** of any size from 1×1 up to 1024×1024, filled or outline only. The shapes are symmetric,
  deterministic and have no gaps (see [Geometry](#geometry)).
- **Centre handling for odd, even and mixed sizes.** Odd sizes have a 1×1 centre, even sizes a 2×2 centre, and mixed
  sizes (for example 21×12) a 1×2 centre. You choose which side of the selected block the 2-wide centre extends to.
  Forcing a 1×1 or 2×2 centre on the wrong parity adjusts the size by one block and shows the change in the GUI. The
  mod never draws an off-centre shape without telling you.
- **Holographic rendering.** Each connected section is drawn as one translucent box with bright edges, plus faint grid
  lines so single blocks can still be counted. The centre block(s) are marked in a contrasting colour. Colour and
  opacity are configurable, and an optional *see through blocks* mode helps when planning underground.
- **Connected sections.** Straight runs of blocks are grouped horizontally or vertically, and identical stacked runs are
  merged into rectangles. For example, a filled 7×7 circle becomes `3 by 1`, `5 by 1`, `7 by 3`, `5 by 1`, `3 by 1`.
  Blocks that only touch diagonally are never grouped.
- **Dimension labels.** Each section gets a blueprint-style dimension line with end ticks on its outer side:
  - lines show their full size (`7 by 1`);
  - rectangles show the width along one side and the height along the other.
- **Pop-up holograms.** Floating labels above each section that:
  - fade in and out;
  - face the player, or optionally stay upright along the section;
  - scale with distance;
  - move aside or hide automatically when they would overlap.
- **Centre selection mode** with a pulsing marker and a preview of the shape's footprint.
- **A single settings screen** with three panels (Circle, Dimensions, Position). It doesn't pause the game, so you see
  each change on the hologram straight away.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer for Minecraft 26.2.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) (0.161.0+26.2 or newer) and `how-to-circle-<version>.jar` in
   your `mods` folder.
3. Java 25 is required, which matches Minecraft 26.2.

The mod is client-only, so you can use it on any server.

## Using it

| Key (default) | Action |
|---|---|
| `H` | Open the How to Circle screen |
| `J` | Start or cancel centre selection |
| *(unbound)* | Show or hide the hologram |

You can change these under *Options → Controls → Key Binds → How to Circle*.

### Quick start

1. Press **H**, type a width (for example `15`) and press **Generate**. With no centre selected yet, the circle is
   centred on you.
2. Press **J**, look at the ground and **left-click**. The circle moves there, one block above the face you clicked, the
   same way block placement works. **Sneak** to use the targeted block itself. **Right-click** or **J** cancels.

### The screen

**Circle Settings**
- **W / H**: width and height in blocks. In *Circle* mode the height follows the width.
- **Shape**: Circle or Oval.
- **Fill**: Outline or Filled.
- **Centre**: Auto, 1×1 or 2×2. The button shows the resulting centre, and any size adjustment appears in yellow.
- **Centre extends**: for even or mixed sizes, which neighbour of the selected block makes up the rest of the centre
  (for example *East South*).
- **Generate**, **Clear**, plus a live summary of the size, block count and section count.

**Dimension Settings**
- Toggles for dimension labels, pop-up holograms and *pop-ups face player*.
- **Label format**: *long by short* (`5 by 1`) or *width by height* (`1 by 5`).
- Sliders for text size, label offset (pop-up height), label opacity and hologram opacity.
- **Hologram colour**: preset swatches or any hex colour.

**Position Settings**
- The current centre coordinates, **Select centre** and **Use my position**.
- **Lock to block centre**:
  - ON: the centre is the block you stand in.
  - OFF: even-sized centres snap to the block corner nearest your exact position.
- **▲ Up / ▼ Down** moves the hologram vertically. Click the offset value to reset it.
- **Rotate** turns the hologram 90°. **Plane** switches between floor (horizontal) and wall (vertical) orientation.
- **Reset position**, **Block grid** and **See through blocks**.

Settings are saved to `config/how-to-circle.json`. The hologram itself only lasts for the current world session.

## Geometry

A block is part of the filled shape when **its centre lies inside the ellipse** whose extents are exactly
`width × height` blocks. All coordinates are doubled so the test runs in exact integer arithmetic:

```
X = 2u + 1 − width,  Z = 2v + 1 − height
inside ⇔ X²·height² + Z²·width² ≤ width²·height²
```

Because there is no floating point, the result is deterministic, perfectly symmetric and free of rounding gaps. The
central row(s) and column(s) always span the full requested size, so a 21×13 oval really is 21 by 13. This only makes a
difference for very thin even-sized ovals such as 20×2, which would otherwise lose their tips. The **outline** is every
filled block with at least one empty edge-neighbour: the classic one-block-thick Minecraft circle, whose blocks touch
along an edge or at a diagonal step.

Examples (rows of the filled shape):

| Size | Rows |
|---|---|
| 5×5 | 3, 5, 5, 5, 3 |
| 7×7 | 3, 5, 7, 7, 7, 5, 3 |
| 10×10 | 4, 8, 8, 10, 10, 10, 10, 8, 8, 4 |
| 21×13 | 9, 13, 17, 19, 19, 21, 21, 21, 19, 19, 17, 13, 9 |

**Connected sections** are found by splitting rows into straight runs and merging runs in consecutive rows that have
exactly the same extent. The same is done column-wise, and the result with fewer sections wins; on a tie, the lines
follow the shape's longer axis. Each section is a solid rectangle of real shape blocks, and every block belongs to
exactly one section. A 100×100 circle (7,860 blocks) becomes 59 sections when filled and 116 as an outline.

## Performance

- Nothing is recomputed per frame:
  - the shape and its sections are cached until the size or fill changes;
  - world-space boxes and label text are cached until the centre or placement changes.
- Each section is one box, not one box per block. The grid lines are only generated within 40 blocks of the camera,
  so the geometry stays bounded even for huge shapes.
- Labels are culled by distance and laid out with an allocation-free spatial hash.
- There are no entities and no block changes.

The client game test measures the render callback's CPU time for 100×100 circles and fails if it goes over 25 ms per
frame. Measured numbers are listed below.

## Building

Requires JDK 25. The Gradle wrapper downloads Gradle 9.7.1.

```sh
./gradlew build              # compiles the mod and runs the unit tests; the jar is in build/libs/
./gradlew runClient          # starts a development client with the mod
./gradlew runClientGameTest  # starts a real client and runs the end-to-end test (src/gametest)
```

The project was generated from the official Fabric template for 26.2, the `26.2` branch of
[FabricMC/fabric-example-mod](https://github.com/FabricMC/fabric-example-mod), which is what
https://fabricmc.net/develop/template/ produces. It uses Loom 1.18, Fabric Loader 0.19.5 and Fabric API 0.161.0+26.2.

### Project layout

```
src/main/java/com/howtocircle/
  geometry/     CircleGenerator, CircleShape, OvalShape, ResolvedDimensions, ShapePlacement, …  (no Minecraft code)
  dimensions/   SectionDetector, ConnectedSection, DimensionLabel, LabelLayout                  (no Minecraft code)
src/client/java/com/howtocircle/
  client/       HowToCircleClient (entrypoint), HologramManager (state + caches)
  config/       HowToCircleConfig (JSON)
  input/        KeyBindings, CentreSelectionHandler
  render/       HologramRenderer, DimensionLabelRenderer, HologramGeometry, HologramRenderTypes, SelectionHud
  gui/          CircleSettingsScreen
src/test/java/        JUnit tests for geometry, sections and label layout
src/gametest/java/    Fabric client game test (real client, screenshots)
```

### Tests

- **Unit tests** (`./gradlew test`):
  - exact row profiles for 1×1, 2×2, 3×3, 5×5, 7×7, 10×10, 15×15, 20×12 and 21×13;
  - symmetry, exact bounding box, convexity, outline correctness and gap-free (8-connected) outlines for 21 sizes
    up to 128×17;
  - that sections exactly partition the shape, never group diagonal blocks, and stay readable for 100×100;
  - label layout and centre and placement maths.
- **Client game test** (`./gradlew runClientGameTest`, also run in CI under Xvfb):
  - opens the GUI with the key binding and clicks *Use my position* and *Generate*;
  - renders 15×15 (outline and filled), 16×16 (2×2 centre), 21×13, 20×12 and 21×12 (mixed parity);
  - checks that a forced centre adjusts the size visibly;
  - toggles labels and pop-ups, and tests the wall plane and rotation;
  - renders 100×100 filled and outline circles, checking caching and render cost;
  - clears and regenerates the hologram;
  - uses the centre selection key with a real left-click and right-click;
  - opens and closes the GUI repeatedly;
  - finally checks that no real block in the area changed.

  Screenshots are saved to `build/run/clientGameTest/screenshots` and uploaded as a CI artifact.

## License

CC0-1.0, the same as the Fabric template.
