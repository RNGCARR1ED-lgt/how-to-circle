# How to Build — implementation plan

This plan turns the **How to Circle** mod into **How to Build**, a geometry and construction toolkit for Minecraft
Java 26.2 (Fabric, client-side). It was written after inspecting the existing project and before changing any code.

---

## 1. What exists today (How to Circle 1.0)

| Area | Classes | Notes |
|---|---|---|
| Build | `build.gradle`, `gradle.properties` | Official template, `26.2` branch of `FabricMC/fabric-example-mod`; MC 26.2, Loader 0.19.5, Loom 1.18, Fabric API 0.161.0+26.2, Java 25. No mappings line: 26.x is unobfuscated, so the Mojang names are the only names. |
| Pure geometry (`src/main`) | `CircleGenerator`, `CircleShape`, `OvalShape`, `ResolvedDimensions`, `CentreSize`, `ShapePlacement`, `FillMode`, `ShapeType` | Exact integer ellipse test in doubled coordinates. Centre rule: 1 block for odd extents, 2 for even. `ShapePlacement` maps the 2D cells `(u, v)` to world offsets (floor or wall plane, 90° rotation, which side an even centre extends to). |
| Dimensions (`src/main`) | `SectionDetector`, `ConnectedSection`, `DimensionLabel`, `LabelFormat`, `LabelLayout` | 2D run-merge grouping into rectangles, plus a greedy screen-space layout that stops labels overlapping. |
| Client state | `HologramManager` | Holds one anchor and caches the shape, sections and world geometry, keyed on settings records. |
| Rendering | `HologramRenderer`, `HologramGeometry`, `DimensionLabelRenderer`, `HologramRenderTypes`, `SelectionHud` | Fabric `LevelRenderEvents.COLLECT_SUBMITS`, `SubmitNodeCollector.submitCustomGeometry` with `RenderTypes.debugFilledBox()` or a no-depth `RenderPipeline` copy, and `submitText` for labels. No OpenGL calls. |
| GUI | `CircleSettingsScreen` | Three panels: buttons, `EditBox`es, custom sliders and swatches. |
| Input | `KeyBindings`, `CentreSelectionHandler` | Pick-centre mode. Clicks are consumed in `START_CLIENT_TICK`; interaction callbacks are cancelled. |
| Config | `HowToCircleConfig` | Gson, `config/how-to-circle.json`. |
| Tests | 77 JUnit tests, plus a client game test (real 26.2 client under Xvfb in CI) | |

All 26.2 APIs in use were confirmed against code that compiles on 26.2: the Fabric API 26.2 branch, the Fabric docs
reference mod, Wurst 26.2 and Litematica/MaLiLib 26.2.

## 2. What is reused

* **Integer ellipse maths** (`CircleGenerator`): becomes the cross-section primitive for circles, ovals and rings,
  the cross-section of cylinders, and the 3D generalisation used by spheres and domes (same doubled-coordinate test,
  three axes).
* **Centre rule** (`ShapePlacement.minOffset`): becomes the shared `Centring` utility for every tool and for the
  mirror centre.
* **Connected sections** (`SectionDetector`): still used for planar tools. A new 3D `BoxDecomposer` generalises it
  for volumes and also drives `/fill` optimisation.
* **Label layout** (`LabelLayout`): reused unchanged for all labels.
* **Rendering path** (Fabric level events, submit nodes, the see-through pipeline): reused. The per-frame emitter
  becomes a renderer for a cached mesh.
* **Selection handler, HUD and key-binding pattern**: reused; the handler gains a second target (the mirror centre).
* **Client game-test harness and CI**: reused and extended.

## 3. What is refactored

* **Rename.** Mod id `how-to-circle` becomes `howtobuild`, display name *How to Build*, and the Java packages move
  from `com.howtocircle` to `com.howtobuild`. This is a mechanical rename verified by the compiler in CI, which is
  safer than leaving a mixed identity. Assets, language keys, the key-binding category and config paths follow.
* **`HologramManager` becomes `BuildSession`**: the active tool, its anchor, mirror centre, asynchronous generation,
  and caches for each stage.
* **`HologramGeometry` (circle-specific) becomes `RenderMesh` + `LabelSet`**, built from the generic
  `GeometryResult`.
* **`CircleSettingsScreen` becomes `HowToBuildScreen`**: a tool list plus tabs, with controls generated from
  parameter definitions.
* **`HowToCircleConfig` becomes `HowToBuildConfig`**, with per-tool parameter maps and migration from the old file.

## 4. Geometry abstraction

```text
                         ToolRegistry
                              │
   ┌──────┬──────┬────────┬───┴────┬────────┬──────┬──────────┬────────┐
 Circle  Oval  Square  Rectangle  Cylinder  Sphere  Dome  SpiralStair  Corridor
   └──────┴──────┴────────┴───┬────┴────────┴──────┴──────────┴────────┘
                              │  BuildTool.generate(ToolSettings, GenerationContext)
                              ▼
                       GeometryBuilder  (mutable voxel map: packed long → Placement)
                              │  shared passes: details → patterns → variation → stair shapes
                              ▼
                        GeometryResult  (immutable, sorted, exact)
```

* **`Placement`**: `(x, y, z, MaterialRole role, BlockShape shape, int variant)`, in block coordinates relative to
  the anchor block. Every downstream system (labels, mirror, commands, rendering, progress tracking) reads only
  placements, so nothing works from "a radius 10 circle".
* **`BlockShape`**: `FULL`; `SLAB(BOTTOM|TOP|DOUBLE)`; or `STAIR(facing N/E/S/W, half, shape)`. It is pure Java,
  translated to a real `BlockState` by the client resolver.
* **`MaterialRole`**: `PRIMARY, TRIM, ACCENT, STEP, SUPPORT, CAP, RAIL, FLOOR`. Tools emit roles, never blocks.
* **`GeometryResult`**: holds
  * the placements, sorted by y, z, x so the output is deterministic;
  * the bounds;
  * the exact centre cells (1 or 2 per axis);
  * label groups;
  * dimension values (width = maxX − minX + 1 and so on, plus tool values such as radius, revolutions and steps);
  * metadata and validation warnings.
* **`BuildTool`**: an id, a list of `ToolParameter`s (int, enum or bool, each with a range, default, translation key,
  tooltip and simple/advanced flag), `validate(...)`, `generate(...)`, and the detail features it supports.
  Capabilities are interfaces: `Mirrorable`, `Rotatable`, `Detailable`, `MaterialAssignable`, `Dimensionable` and
  `CommandBuildable`. Shared systems check capabilities instead of tool names.
* **Adding a tool** means one class (parameters + generator) and one line in `ToolRegistry`. The GUI, labels, mirror,
  commands, presets and config all work generically from the parameter list and the `GeometryResult`.
* **Validation runs before generation** and produces warnings, for example "⚠ Stair width is larger than the radius".
  Fatal problems produce an empty result with the reason. Nothing throws into the renderer.

## 5. Rendering abstraction

```text
GeometryResult ─► MaterialResolver ─► RenderMesh.build()  (on settings change only)
                                          │  exposed faces of full blocks, greedy-merged per plane and colour;
                                          │  slab and stair shapes as their real boxes; panel edges
                                          ▼
                        cached float[] quads + edge ribbons
                                          │
                  LevelRenderEvents.COLLECT_SUBMITS (every frame) ─► submitCustomGeometry
                  (translate once to the anchor, then copy cached vertices; no allocation or generation)
```

* **Colours** come from the resolved block's `MapColor`, mixed with a hologram-state tint:
  * primary: tinted lightly;
  * detail roles: brighter edges;
  * mirror copy: violet;
  * completed (the world already holds the intended state): green;
  * conflict (a different block occupies the cell): red.
* **Slab and stair shapes** render as their real geometry: a half box, or a lower box plus an upper quarter (or two)
  set by the stair's facing and shape. The preview therefore shows the blocks that will actually be built.
* **Pipelines**: the vanilla `RenderTypes.debugFilledBox()` (depth-tested), or the mod's registered no-depth copy
  ("X-ray"). Rendering goes through Blaze3D / `RenderPipeline` and submit nodes only, never `GL11`, so it works on
  both the OpenGL and Vulkan backends.
* **Large shapes**: only exposed faces are kept, and coplanar faces merge, so a 64-block hollow sphere becomes a few
  thousand quads. Edge ribbons are drawn only within a distance limit, and labels are distance-culled.

## 6. Material and block abstraction

```text
MaterialProfile (per role):  block id │ slab id │ stair id │ variant ids[]
      ▲ chosen in BlockPickerScreen ◄── BlockCatalog (registry scan; categories; full/slab/stair flags from block classes)
      │
MaterialResolver: Placement(role, shape, variant) ─► BlockState  (StairBlock.FACING/HALF/SHAPE, SlabBlock.TYPE)
                                                 └─► command string via BlockStateParser.serialize(state)
```

* **`BlockCatalog`** scans `BuiltInRegistries.BLOCK`, which includes modded blocks. It classifies each block by its
  type, not its name:
  * stairs: `instanceof StairBlock`;
  * slabs: `instanceof SlabBlock`;
  * walls: `instanceof WallBlock`;
  * pillars: `instanceof RotatedPillarBlock`;
  * functional: `instanceof EntityBlock`;
  * full blocks: `isCollisionShapeFullBlock` and none of the partial types above.

  Search matches name, namespace, category and `#tag`.
* **"Material Types ▼ ☑ Blocks ☐ Slabs ☑ Stairs"** is a real multi-select (`EnumSet<ShapeKind>`). It filters the picker
  to the union of the selected kinds and tells tools which shapes they may emit; the spiral and smoothing passes use
  it.
* **Matching slabs and stairs.** Picking a base block offers its matching slab and stair (for example
  `stone_bricks` → `stone_brick_slab` / `stone_brick_stairs`). A candidate is accepted only if the registry block
  really is a `SlabBlock` or `StairBlock`. Every variant can be overridden in the picker.
* **Material variation** (Subtle, Medium, Heavy) and **patterns** (stripes, checker, rings, bands, sections) are
  shared post-passes driven by a seeded position hash, so they are deterministic.

## 7. Dimension and label abstraction

* **Groups**:
  * planar tools use the existing 2D `SectionDetector` in the tool's plane;
  * volumes use `BoxDecomposer` boxes for each role, labelling those whose longest side is at least *min label
    size*.
* **Values always come from coordinates.** For example a box from x = 3 to x = 8 is width 6.
* **`DimensionFormatter`** formats:
  * `FULL` → `6 × 1`;
  * `SIMPLIFIED` → `6`;
  * `WIDTH_ONLY` → `6 W`;
  * `HEIGHT_ONLY` → `10 H`;
  * `NAMED` → `WIDTH: 6`;
  * `CUSTOM` → a template using `{width} {height} {length} {long} {short} {blocks} {radius} {diameter} {thickness}
    {steps} {revolutions} {material} {tool} {section}`.
* **Components** (width, height, length, thickness, radius, diameter, steps, revolutions, material, tool name, section
  name) are individual toggles used by the summary pop-up.
* **Placement** options: Above, Below, Inside, Outside, Left, Right, Automatic.
* **Style** options: text scale, background on/off and opacity, outline (border), padding, label opacity, leader
  line, distance scaling, billboarding, decimals, units suffix, and visibility through walls.
* `LabelSet` (texts, anchor points, priorities) is rebuilt only when the geometry or label settings change. The
  renderer runs layout plus fading each frame on cached data.

## 8. Command generation

```text
GeometryResult ─► MaterialResolver (state strings) ─► CommandPlanner (pure)
   group placements by exact state string ─► BoxDecomposer: maximal exact cuboids
   box volume > 1  → /fill x1 y1 z1 x2 y2 z2 <state> [replace|keep]   (split to ≤ the fill block limit)
   single cells    → /setblock x y z <state> [replace|keep]
   order bottom-up (gravity blocks)
        ▼
CommandPlan (commands, block count, fill/setblock counts, compression %, undo plan)
        ▼
CommandQueue (client): N commands per tick through ClientPacketListener.sendCommand
                       pause / resume / stop / progress; failure keys from game messages pause the queue;
                       "too big" splits the box and retries
```

* A `/fill` box is only emitted when **every** cell in it is in the geometry with exactly that state. Boxes come from
  the exact voxel set, so empty space is never filled. Unit tests check that the commands cover exactly the input
  cells.
* Block-state syntax comes from Minecraft itself (`BlockStateParser.serialize`), so property names and values match
  26.2 exactly (for example `facing=east,half=bottom,shape=straight,waterlogged=false`).
* **Permission check**: `player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`, the level `/fill` and
  `/setblock` need. Without it the GUI shows *Command Build Unavailable* with the reason. Nothing bypasses it: commands
  are typed into the normal command channel (`sendCommand`), exactly as if the player entered them.
* **Output modes**: Execute (with a confirmation dialog showing blocks, commands, compression and materials), Copy to
  the clipboard, Save as an `.mcfunction` file, and Preview (a scrollable list).
* **Undo plan**: the same boxes filled with air. It is shown as destructive, so the user is warned and must confirm.

## 9. Mirror and transform

* **`MirrorTransform`** is pure and exact. It works in block coordinates for axes `X`, `Z` and `X+Z`; the design
  leaves room for `Y`.
* **Mirror plane.** The plane runs through the centre of the chosen mirror centre (1×1 block or 2×2 corner, same
  `Centring` rule), plus an integer offset. For axis X the reflection is `x' = S − x`, where `S = 2·c + (centre2 ? 1
  : 0) + 2·offset`, all integers. For a 1×1 centre `c` is the centre block; for a 2×2 centre `c` is its first block
  on that axis (the 2-block centre is `c, c+1`).
* **Block states mirror too**: stair facing flips across the axis, the stair shape swaps left↔right, and slab halves
  are unchanged.
* **Modes**:
  * *Preview* renders the mirror in violet but doesn't build it;
  * *Duplicate* builds the original plus the mirror;
  * *Replace* builds only the mirror.
* **Mirror in the tool list** opens the Mirror tab of the current shape tool. Mirroring applies to any tool that
  implements `Mirrorable`, which is every grid tool.
* **`Orientation`** (90° turns about Y) is shared by 3D tools and rotates stair facings with the geometry. Arbitrary
  angles are not offered because the block grid cannot represent them exactly.

## 10. GUI

```text
┌ HOW TO BUILD ─────────────── [Simple|Advanced] [Presets] [?] [Done] ┐
│ TOOLS ★     │ [Geometry][Materials][Details][Labels][Mirror][Build] │
│ ★ Circle    │  controls generated from ToolParameter definitions     │
│   Oval      │  (int fields with −/+, enum cycles, toggles, sliders,  │
│   Cylinder  │   multi-select checkboxes, material slots with icons)  │
│   …         │                                                        │
│   Mirror    │  ⚠ inline validation warnings                          │
│             │  status: 21×13 · 217 blocks · 9 sections   PREVIEW     │
└─────────────┴────────────────────────────────────────────────────────┘
```

* Parameters marked *advanced* are hidden in Simple mode. Every control has a tooltip, and each tool has a help popup
  (`?`).
* **Separate screens**: `BlockPickerScreen` (search, category filters, real item models), `CommandBuildScreen`
  (summary, command list, copy/save/execute, confirmation, progress, pause/resume/stop/undo) and
  `PresetScreen` (save/load/delete).
* **Live preview**: changing any value regenerates in the background and the hologram updates while the screen is
  open. The game is not paused.
* **Preview vs build**: the *PREVIEW* badge and the build buttons are visually separate.

## 11. Configuration

* `config/howtobuild.json` holds:
  * the last tool, per-tool parameter maps, materials, material types, details, labels, mirror and build settings;
  * GUI state (tab, simple/advanced, scroll) and favourites.
* Presets are separate files in `config/howtobuild/presets/*.json`.
* **Migration**: if `config/how-to-circle.json` exists and the new file doesn't, the old values (size, fill, centre
  mode, plane, colours, label settings) are copied into the circle and oval tools. The old file is left untouched.
* **World context**: the anchor is never persisted. It is bound to the current client level and a world key (server
  address or singleplayer save), and cleared on disconnect.

## 12. Testing

* **JUnit (pure code, runs in `./gradlew build`)**:
  * every generator, at the sizes in the brief: circles 1/3/5/8/15, ovals 7×3/15×7/20×11, cylinders 5/10/20, spheres
    5/10/20, domes 5/10/20, spirals with 1/2/5 revolutions, varying widths and all 7 material-type combinations, and
    corridors (small, wide, tall, short, long; arch, dome, sphere);
  * symmetry and extent checks;
  * stair-orientation checks: the facing follows the tangent and the shape matches vanilla neighbour rules;
  * mirror exactness: every block maps to `S − x` (odd and even centres, ± offsets, X, Z and X+Z), states included;
  * the command planner covers exactly the input cells, uses `/fill` only on exact boxes, and splits oversized boxes;
  * formatter and label outputs, validation warnings, and determinism (same settings give the same result).
* **Client game test (real 26.2 client)**:
  * opens the GUI;
  * runs every acceptance scenario (circle, detailed hollow cylinder, spiral with blocks+slabs+stairs, hollow detailed
    sphere, dome with ribs and crown, 6×10×20 arch corridor with arches, mirrored corridor);
  * checks the block-picker filters against the real registry;
  * switches the label format to `6`;
  * runs a real command build as an op player and compares every placed `BlockState` on the server with the preview;
  * takes screenshots.

## 13. Implementation order

1. Rename, then the core framework (`GeometryResult`, `BuildTool`, the registry and parameters). Circle, oval,
   square and rectangle become tools. Unit tests.
2. The 3D tools (cylinder, sphere, dome), details, patterns and variation.
3. Spiral staircase (roles, the material-type matrix, stair orientation and shapes, slab modes, validation), then the
   corridor (profiles, repetition, details, smoothing).
4. Mirror, the label formatter and the command planner, all pure, with tests.
5. Client: session and async generation, catalog and resolver, mesh renderer, labels, progress tracking, command queue,
   config migration and presets.
6. GUI: main screen, block picker, command screen, presets and help.
7. CI compile and game test after each step, fixing everything; then screenshots and the README.

## 14. Minecraft 26.2 API risks (and how they are handled)

| Risk | Handling |
|---|---|
| Names differ from 1.21.x (`GuiGraphicsExtractor`, `extractRenderState`, `client.gui.setScreen`, `LevelRenderEvents`, `SubmitNodeCollector`, no `MultiBufferSource`) | Only APIs seen in 26.2-compiling sources are used. CI compiles against real 26.2. |
| `Screen.renderables` is private | The screen keeps its own widget list (already done). |
| Permission API changed (`PermissionSet`, `Permissions`) | `player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`, verified in the Fabric permission API. |
| Command syntax | `BlockStateParser.serialize` for states. `/fill` and `/setblock` grammar unchanged; verified by the end-to-end game test that builds with commands. |
| `/fill` volume limit and game rule renames (e.g. `GameRules.ADVANCE_TIME`) | Boxes split to a configurable limit (default 32,768). "Too big" responses trigger further splitting. |
| `MapColor.col` | Used for tints; compile-checked. Falls back to the role colour. |

## 15. Performance

* Generation happens only on settings change, on a single background thread (pure code, no world access). Results
  are adopted on the client thread by generation id, and stale results are discarded.
* Stage caches: shape → resolved states → mesh → labels → command plan. Each is rebuilt only when its inputs change.
  For example a colour change rebuilds only the mesh.
* The per-frame cost is copying cached vertices plus label layout, with no geometry work.
* Collections use packed `long` keys (fastutil, bundled with Minecraft) to avoid boxing.
* Progress tracking checks a bounded number of world positions per tick.

## 16. How the tools share infrastructure

Every tool produces only `Placement`s. From there the same code runs for every tool:

```text
ToolSettings ─► validate ─► generate ─► GeometryResult
   ─► details / patterns / variation / stair shapes (shared passes)
   ─► mirror (shared, exact)
   ─► MaterialResolver (shared) ─┬─► RenderMesh ─► hologram
                                 ├─► LabelSet   ─► dimension labels and pop-ups
                                 ├─► ProgressTracker ─► completed and conflict colouring (guided normal build)
                                 └─► CommandPlanner ─► preview / copy / save / execute queue
```

A new tool such as Cone or Arch gets the GUI, materials, details, labels, mirror, presets and command building with
no changes outside its own class and one registry line.

---

# Part 2: Unified coordinate, centre and dimension pass

This pass makes every tool use one coordinate, centre and dimension system. It is a focused refactor of the existing
code, not a rewrite.

## 17. Findings from inspecting the code (root causes)

| Symptom | Actual cause |
|---|---|
| A 33 × 33 spiral overflows a 33 × 33 circle | The spiral builds its own disc of diameter `2 × outer_radius + 1` around the anchor block. It has no notion of the circle's width/length, cannot be even-sized, and measures angles from the anchor block centre instead of the shape's true centre. |
| Spiral details stick out of the footprint | *Wall attachment* and *ring support* are drawn on a ring of radius `outer + 1`, one block outside the steps. |
| Blocks below the base (Y − 1), and command builds destroying ground | The bottom **landing** is placed at `baseY − 1`. Preview and build both use the same geometry, so the command build faithfully replaced the ground under it. Nothing in the pipeline checks that base-anchored tools stay at or above their base layer. |
| "Offset Y = −1" | The code has no hidden Y adjustment. Offset Y defaults to 0, and a legacy How to Circle config only migrates its own `verticalOffset`. A −1 is a value someone typed, most likely to compensate for the landing bug above. With the bug fixed, no compensation is needed. |
| 2 × 2 centres drift after rotation | The pipeline rotates about the anchor block's centre `(x, z) → (−z, x)`. For an even extent the true centre is a block corner, so a 180° turn moves a 32 × 32 shape by one block. |
| The centre is not visible | The centre marker is a 0.36-block cube drawn inside the depth-tested translucent mesh, so it disappears inside filled blocks. |
| Offsets "removed" | Offset X and Z are only shown in Advanced mode, below the tool parameters. |
| Forced 1×1 / 2×2 on the wrong parity | The size is silently grown by one, with only a yellow note. |
| Corridor only builds blocks | `CorridorTool.usesMaterialTypes()` is false, so the Blocks / Slabs / Stairs selection is ignored. |

## 18. The single source of truth

```
GeometryCenter (extents + alignment → min offset, 1×1/2×2 cells, doubled centre)
      │
CircularFootprint (exact ellipse cells of W × L around a GeometryCenter)
      │                         │
 Circle / Oval tools        Spiral (own diameter, or Follow Circle Dimensions)
      │                         │
GeometryResult (exact placements relative to the anchor, centre cells, guides)
      │
GeometryPipeline: rotation about the true centre (exact for 1×1 and 2×2 centres)
      │
WorldTransform: anchor (selected centre) + offset (X, Y, Z) = origin
      │
MirrorTransform → resolved BlockStates ──► hologram · labels · progress · /fill + /setblock · export
```

- **Dimension contract.** A dimension is the exact block extent of the generated footprint: 33 × 33 means the
  bounds are exactly 33 × 33.
- **Centre rule.** An odd extent has a 1-block centre and an even extent a 2-block centre, extending towards the
  configured side. *Automatic* follows parity. A forced 1×1 or 2×2 on the wrong parity is now a validation error
  with a fix suggestion, instead of silently growing the shape.
- **Y rule.** Every tool declares a vertical anchor:
  - **Base:** the anchor layer is the lowest layer. This applies to the cylinder, dome, spiral, corridor and floor
    shapes. The pipeline reports a bug if a base-anchored tool emits anything below layer 0, and a unit test
    enforces it for every tool.
  - **Centre:** the anchor is the middle layer. This applies to the sphere and wall shapes.
- **Offsets** are applied once, in `WorldTransform`, for every tool. Changing dimensions or tools never resets them.
- **Preview = build.** The hologram, labels, progress tracking and command planner all consume the same resolved
  placements. Tests compare the commands, expanded back to block positions, with the preview's positions.

## 19. Spiral staircase changes

**Footprint**
- The footprint is a `CircularFootprint`. With its own size it is exactly `diameter × diameter`, odd or even.
  The old `outer_radius` (always `2r + 1`) is gone; saved settings are migrated to the equivalent diameter.
  Nothing rounds a size to odd: even sizes have a genuine 2 × 2 centre.
- With **Follow Circle Dimensions** it uses the master shape's exact width × length, Circle or Oval.
- **Copy from Circle tool** takes the size from the Circle tool. **Fit spiral to circle** turns following on,
  copies the size and keeps every part inside.
- Angles and stair facing are measured from the true (doubled) centre, so 2 × 2 spirals are symmetric.

**Thickness**
- Stair width grows inward from the fixed outer boundary (exact erosion, which also works for ovals).
- A non-zero *inner radius* defines the hole instead. The values shown are the real ones.

**Details**
- Every detail is generated from the footprint: column = interior; wall or ring support = the footprint's outer ring,
  with the steps moving inward.
- *Allow details outside boundary* (off by default) restores the outer ring at radius + 1.
- A final containment check warns about any cell outside the master footprint, and only when that option is on.
  The geometry is never clipped silently.

**Y**
- The bottom landing sits at the base layer, and nothing goes below it.

**Guide**
- The master footprint's boundary is exported as a guide and drawn in a separate style.

## 20. Corridor / arch materials

- The corridor now uses Blocks / Slabs / Stairs, with an explicit part assignment: **structure**, **arch curve** and
  **trim**, each Auto / Blocks / Slabs / Stairs.
- Shaping happens in place, so the corridor's dimensions never change:
  - **Stair facing:** corner cells of the curved zone become stairs facing the solid side. They are upside-down
    under the curve (intrados) and upright on the roof (extrados).
  - **Slab half:** curve or trim cells take their half from the exposed side (top slab under the curve, bottom slab
    on top) or from the slab mode.
- The vanilla stair-shape pass resolves corners afterwards.

## 21. GUI

**Layout**
- The panel width is relative to the screen, with minimum and maximum widths.
- The form uses two columns when there is room; sections are placed whole into the shorter column.
- Section headers are collapsible.

**Tabs**
- Tabs: Geometry, **Center**, Materials, Details, Labels, Mirror, Build.

**Center section** (also shown in the Geometry tab)
- Center mode, centre coordinates, centre size and X / Y / Z offsets, always visible.
- Select Center and My Position.
- Debug bounds: min/max X/Z, width and length.

**Spiral sections**
- Shape, Circle Integration, Center, Staircase Parts.

**Centre in the world**
- A distinct pulsing outlined cube per centre cell, drawn through blocks; *Show centre* toggles it.
- During centre selection the full 1×1 or 2×2 centre is previewed at the target.

## 22. Tests added in this pass

- **Exact sizes:** spirals of 1–8, 16, 30–34 and 64 blocks keep exactly that size, in both modes, with a 1×1 or
  2×2 centre by parity, centred on the footprint and identical to the circle's centre.
- **Spiral vs circle:**
  - spiral ⊆ circle for 33 × 33 (and other odd, even and oval sizes, widths 1–5, all details);
  - identical centre cells for 32 × 32;
  - offsets move both identically.
- **Y:** no base-anchored tool emits y < 0; offset Y = −1 moves everything down exactly one block.
- **Rotation:** 2 × 2 shapes keep their centre cells and footprint under every rotation.
- **Corridor:** all 7 material combinations keep the exact 6 × 10 × 20 bounds; stairs follow the curve (facing,
  half) and slabs take the correct half.
- **Preview = build:** the commands expanded back to positions equal the preview's world positions exactly, and no
  y below the base.
- **Client game test:**
  - follow-circle in a real world;
  - 2 × 2 centre rendering;
  - a command-built spiral on solid ground leaves the ground untouched and matches the preview block for block.

---

# Part 3: Randomisation, terrain, saved builds, inset spirals and the material system

## 23. Existing architecture (what this builds on)

**Generation pipeline**

```
BuildTool.generate ─► GeometryBuilder ─► GeometryPipeline (smooth, rotate about the true centre, pattern, variation,
stair shapes) ─► GeometryResult (exact placements: x, y, z, role, BlockShape, variant, mirrored)
```

**Client side**

```
BuildSession: GeometryResult ─► MirrorTransform ─► MaterialResolver (role + shape → BlockState) ─► Resolved
              (states[], WorldTransform) ─► RenderMesh / LabelSet / ProgressTracker / CommandPlanner / BuildAnalysis
```

**Shared infrastructure**
- `GeometryCenter`, `CircularFootprint` and `WorldTransform` define centres, footprints and world placement.
- The GUI is driven by `ToolParameter`s; tools are registered in `ToolRegistry`.
- The block picker (`BlockCatalog`) classifies blocks by class and shape.

## 24. Limitations this pass removes

| Limitation | Consequence |
|---|---|
| A placement can only say *role*; the block comes from one material slot per role | No per-block palettes: no randomisation, terrain layers or exact saved states |
| No representation for an exact `BlockState` inside the pure pipeline | A saved build could not flow through the same pipeline, rotation and mirror |
| `ToolParameter` has only int / bool / enum | No file names (saved builds) |
| Tools cannot see the world | Terrain cannot blend into existing ground |
| Material counts exist only as a block total | No material list, block-state breakdown or resource check |
| The Build tab is a settings page | It is not a build dashboard |

## 25. Reusable systems and refactors

**`Placement.material`** (new, −1 = none): an index into `GeometryResult.materials()`, a list of `MaterialRef`.

**`MaterialRef`**
- A *block id* is shaped by the placement's `BlockShape` (used by randomisation palettes and terrain layers).
- An *exact state string* is used by saved builds.
- Roles still provide the default material, so every existing tool is unchanged.

**`StateTransform`** (pure)
- Rotates and mirrors exact state strings by rewriting the properties that carry orientation: `facing`, `axis`,
  `rotation`, stair `shape`, `hinge`, side connections, chest `type`.
- This lets exact saved states go through the same pipeline rotation and `MirrorTransform` as generated shapes.

**`GenerationContext`**
- Gains a `Palettes` component: the randomisation settings, the terrain layers, and a `HeightSampler` snapshot of
  existing ground.
- The snapshot is taken on the client thread before generation, so workers never touch the world.

**Other refactors**
- **`ToolParameter.text`:** a new string parameter, used for the saved-build file name.
- **`GeometryResult`:** gains `groups` (per-position group ids, e.g. spiral step or revolution), `annotations`
  (positioned label texts, e.g. `Y=90`) and typed `guides` (master outline, protected area, boundary, contour).

## 26. Randomisation system

**Palette**
- `WeightedPalette`: entries with block id, weight, enabled flag and order.
- Normalisation is optional; when it is off, the GUI shows the total and a warning.

**Assignment is exact and rank-based**
1. Each candidate placement gets a scalar from the chosen **pattern**: completely random, subtle variation,
   natural, clustered, patchy, gradient, edge weighted, centre weighted, striped, radial, noise, or custom noise
   (scale, strength, octaves, contrast, threshold).
2. Candidates are sorted by that value (ties broken by a hash).
3. They are cut into consecutive runs whose sizes are the weights' largest-remainder shares.

The result has *exactly* the requested proportions, keeps the pattern's spatial structure, and is reproducible
from the seed.

**Modes**
- *Weighted* uses the percentages.
- *Fully Random* gives every entry equal weight.
- *Deterministic* is weighted with the seed locked.

**Seed**
- The seed is always explicit, so preview, build and save always agree.
- *Randomise again* draws a new seed unless the seed is locked.

**Scope**
- **Layers:** a set of material roles is randomised; the others keep their fixed material.
- **Edges:** protect outer edge plus edge variation (0–100 %).
- **Spirals:** symmetry per block, per step or per revolution (uses `groups`).
- **Mirror:** the mirrored copy either copies the original's randomisation (*Mirrored*) or is randomised
  independently (*Independent*).

**Geometry is never changed**
- Only `Placement.material` changes, which tests verify.

**Tools**
- The **Randomisation** tool generates a floor, wall or volume region (rectangle, square, circle, oval) filled
  with the palette.
- Every other tool can enable randomisation globally.

## 27. Terrain / terraforming system

**Pipeline**

```
Region mask (circle / oval / square / rectangle, exact CircularFootprint / rectangle cells)
 ─► Protected mask (shape + size + offset)
 ─► Height field
 ─► Smoothing
 ─► Boundary blending
 ─► Material layers (+ randomised layer palettes)
 ─► Placements
```

**Height field** (deterministic fBm Perlin noise, pure Java)
- **Template:** flat, rolling hills, hill, smooth/rocky/jagged/layered/volcanic/alpine mountain, valley, ridge,
  plateau, crater, basin, island, cliff, mountain range, foothills, twin hills, long ridge.
- **Plus a variation layer:** rolling, noise or ridged.
- **Plus a detail layer:** procedural parameters scale, amplitude, octaves, persistence, lacunarity, ridge,
  erosion, slope and valley strength.
- *Custom* is procedural only.

**Quality**
- The quality setting picks the smoothing passes and octaves.
- A slope limiter removes one-block spikes and impossible cliffs, unless *Cliff* is chosen.
- Erosion-like shaping makes the field's channels deeper and its ridges sharper.

**Protected area**
- Columns inside the protected mask are never generated.
- Heights within the blend radius of it interpolate (Sharp / Smooth / Natural / Very Smooth) towards the sampled
  existing ground at the boundary, or towards the base where nothing is sampled.

**Outer boundary**
- *Natural* (default) fades to the existing ground; *Cliff* does not.

**Materials and output**
- Layers are a list of (`WeightedPalette`, thickness); the last layer fills to the base.
- *Fill depth* can limit the column to the top N blocks.

**Validation**
- `GeometryValidator` asserts that no position is inside the protected set or outside the region.

**Preview**
- The terrain shows contour guides every N blocks, `Y=…` annotations, the protected-area and boundary guides,
  and a summary: area, min/max Y, block counts, protected blocks.

**Performance**
- Generation runs off-thread from the snapshot and stays under the 1M-block limit.

## 28. Saved builds (`.hwb`)

**Format**
- A small binary header (`HWB`, version) followed by gzip'd data:
  - name, author, description, tool, timestamps;
  - dimensions, origin mode, centre cells, offsets, seed and generator settings (JSON);
  - a **palette of exact state strings**;
  - per-block (x, y, z relative to the origin, palette index).
- Pure codec with validation: version, dimensions, palette indices, state syntax.

**Saving**
- The default is the **final result**: geometry, details, randomisation, mirror, overrides; exact states as
  resolved by the client.
- Options:
  - geometry without mirror;
  - a world capture inside the preview bounds;
  - *Procedural Preset* (stores tool settings and seed instead of blocks).
- Writes are atomic: a temp file, then a move. A failed save never destroys an existing build.
- Saving runs off-thread with a status line.

**Loading**
- The **Saved Build** tool reads the file (off-thread) into a `GeometryResult` whose materials are the exact
  states.
- The usual pipeline then applies Y rotation, X/Z flips, mirror, offsets and material overrides.
- **Saved = placed:** nothing is regenerated from settings.

**Validation on load**
- Missing blocks (other mods) are listed and can be replaced; the load never crashes.

**Compare with world**
- Matched / missing / incorrect, plus *extra*: unexpected blocks inside the build's box.
- Shown as hologram states and counts.

## 29. Spiral inset (Fit Inside Circle)

**Circle mode:** `circle_mode` = Off / Follow circle dimensions / **Fit inside circle**. Saved `follow_circle`
settings are migrated.

**Fit inside**
- The master footprint is the wall boundary.
- The steps use `master.inset(wall + clearance)`: exact erosion of discrete cells, never a float radius.
- *Automatic inset* = wall thickness + clearance, plus one block when an outer curb or rail is enabled.
- The *Wall* detail fills the wall zone (`master − master.inset(wall)`), and nothing else enters it.
- Stair width grows inwards (the inner radius adapts).

**Edges**
- Outer edge: simple, straight (rail), rounded (slabs), trimmed, stepped (curb), detailed; with thickness and
  material role.
- Inner edge: open, central column, inner rail, inner wall, trim, decorative ring.

**Roles**
- New roles: secondary, highlight, outer edge, inner edge.
- Every role is a real block in Build → Materials.
- *Material themes* (stone, deepslate, sandstone, oak, quartz, …) set all roles at once.

## 30. Material accounting and the Build tab

**`MaterialCounter`** (pure, over the final state strings) groups by:
- block: icon, name, count, %;
- exact state: facing / half / shape breakdown;
- category: blocks, slabs, stairs, structural, details, accent.

Counts come from the final resolved build, so they include mirror (unique positions only), randomisation and
overrides.

**Build dashboard**
- Summary: tool, dimensions, centre, blocks, materials, details, randomisation, mirror, inset.
- Material list with item icons and filters.
- Click a material to **replace** it. Overrides are applied in the resolver and keep facing, half, shape,
  type and waterlogged when the target block has them.
- *Show block list* with exact states.
- Resource check against the player's inventory: a warning for normal building only.
- Command estimate; save / load buttons.

**`GeometryValidator`** checks duplicates, the region, the protected set, below-base positions and missing states.
Building is blocked while it reports errors.

## 31. Rendering changes

**Guides**
- Typed guides drawn in distinct styles: master outline, protected area (red), region boundary, contours.

**Annotations**
- Annotations (`Y=…`) become labels.

**Compare states**
- Compare states reuse the mesh colours: matched green, incorrect red, missing default, extra orange boxes.

**Everything else**
- Still batched custom geometry through `LevelRenderEvents`, with no entities and no raw OpenGL.

## 32. GUI changes

**Layout**
- Three columns: tools | settings (one or two columns) | **preview pane**.
- The preview pane is a live top-down mini-map of the final build coloured by material, with dimensions, centre and
  counts.
- It fits 1280×720 through 3840×2160 at any GUI scale, and collapses when narrow.

**New tools**
- Randomisation, Terrain, Saved Build.

**New tabs**
- **Randomise:** palette editor with icons, %, enable, remove and reorder; total; normalise; mode, pattern and
  seed buttons; cluster and noise; edges; layers; symmetry.
- **Terrain layers:** a layer list.
- **Builds:** save, load, import, export, delete, compare.

**Build tab**
- Becomes the dashboard (section 30).

## 33. Configuration changes

**New fields**
- `random` (settings + palette), `terrainLayers`, `materialOverrides`, `buildFilter`, `showBlockList`.

**Migrations**
- `follow_circle` → `circle_mode`.
- New roles default to sensible blocks.
- Presets include all of it.

## 34. Testing strategy

**Pure unit tests**
- **Randomisation:**
  - exact proportions, including 100 %, 50/50, invalid totals and normalisation;
  - determinism by seed, equal mode;
  - clustered and noise patterns are spatially coherent (fewer material changes between neighbours than
    white noise);
  - geometry is unchanged;
  - roles and edges are respected.
- **Terrain:**
  - every template plus procedural, at low and high roughness, on small and large regions;
  - no position in the protected set or outside the region;
  - edge blending is smooth (bounded neighbour height difference);
  - existing-height sampling is honoured;
  - slope limiting.
- **Spiral inset:** 16 × 16 at inset 0/1/2 never enters the reserved ring; 32 × 32 keeps its 2×2 centre.
- **Saved builds:**
  - round trip for circle, spiral (stairs and slabs), randomised floor, corridor, terrain and mirrored build;
  - rotation, flips and translation are exact (positions and states);
  - invalid files are rejected cleanly.
- **Material counts:** counts equal the final placements; mirrored overlaps are counted once.
- **Commands:** they expand to exactly the final states for randomised, mirrored and saved builds.

**Client game test**
- Randomised floor counts.
- Terrain with a protected area: the world inside it is unchanged after a command build.
- Inset spiral.
- Save → load → place round trip: the world matches the saved file block for block.
- GUI at several window sizes.

## 35. Performance strategy

- All generation (noise, terrain, randomisation, file parsing) is pure and runs on the existing background
  generator thread.
- Caching:
  - results are keyed by settings, palette and the height snapshot;
  - the resolved build, mesh, labels and material counts are cached per result;
  - nothing runs per frame.
- Rank-based assignment is O(n log n) once per generation.
- Terrain stores heights in an `int[]` and only creates placements for its output.
- The 1M-block limit applies.
- The block picker draws only visible rows (already virtualised).
- Save and load run off-thread, with atomic writes.

## 36. Minecraft 26.2 compatibility

**Block states**
- Uses only APIs already proven by CI: `BlockStateParser`, `BuiltInRegistries.BLOCK`, property copying through
  `StateDefinition` / `Property`, inventory via `Inventory.getItem` / `getContainerSize`.

**Height snapshot**
- Uses `Level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)` on the client thread.

**Not used**
- No new networking, entities or OpenGL.

## 37. Implementation order

1. Pure core: `Placement.material`, `MaterialRef`, `StateTransform`, palettes, noise, randomiser, validator,
   material counter, `.hwb` codec, text parameters, context palettes, guides and annotations. Unit tests.
2. Tools: Randomisation, Terrain (templates, procedural, protected, blending), spiral circle modes, edges, roles,
   Saved Build. Unit tests.
3. Client:
   - session sources and overrides;
   - height snapshot;
   - resolver for material refs;
   - accounting;
   - Build dashboard;
   - Randomise / Terrain / Builds tabs;
   - preview pane;
   - guides;
   - save/load IO;
   - compare.
4. CI build plus the client game test, fixing until green; README.
