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
 Circle / Oval tools        Spiral (own radius, or Follow Circle Dimensions)
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
- The footprint is a `CircularFootprint`. With its own radius it is `2r + 1` (1×1 centre) or `2r` (2×2 centre).
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
