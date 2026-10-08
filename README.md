# How to Build

A client-side Fabric mod for **Minecraft 26.2**: a geometry and construction toolkit that shows block-exact holograms of
circles, ovals, squares, rectangles, cylinders, spheres, domes, spiral staircases, corridors, randomised surfaces,
terrain and saved builds before you place anything. It started as *How to Circle*, and every How to Circle feature is still here.

Pick a tool and type its sizes. The hologram updates while you type and shows every block as the real block:
- slabs are drawn as half blocks and stairs as real stair shapes, facing and corner shapes included;
- each block is tinted with its material colour.

CAD-style labels measure the result. The mirror tool copies it exactly across X, Z or both. If the server already
lets you use `/fill` and `/setblock`, the mod can build it for you with those commands; otherwise it guides you while
you build by hand.

**Preview never changes the world.** The mod spawns no entities, sends no custom packets and bypasses nothing.

<!-- screenshots: docs/screenshots -->

## Tools

| Tool | What it makes | Main options |
|---|---|---|
| **Circle / Oval** | Block-perfect circles and ellipses | Size or width × length, filled / outline / ring with thickness, 1×1 or 2×2 centre, floor or wall |
| **Square / Rectangle** | Squares and rectangles | Same options as circles |
| **Cylinder** | Round or oval cylinders | Width, length, height, solid or hollow, wall thickness, caps, vertical or lying along X / Z |
| **Sphere** | Spheres and ellipsoids | Three diameters, solid or hollow, thickness, half / quarter slices, top / bottom / side opening |
| **Dome** | Half-ellipsoid domes | Width, length, height, hollow, floor, open top (oculus), cutaway |
| **Spiral Staircase** | Spiral stairs | Exact diameter, odd or even (32 stays 32 × 32 with a 2×2 centre), or a master circle (**Follow** or **Fit Inside** with wall thickness, clearance and inset), outer and inner edge styles, stair width, inner radius, height, revolutions + extra angle, step rise, direction, start angle and height, a block type for each part (step, support, trim), support depth |
| **Corridor** | Tunnels | Width, height, length, thickness, arch / dome / sphere profile, filled / hollow / shell / open style, floor, ceiling and walls on or off, an arch every N blocks, runs along X or Z, Blocks / Slabs / Stairs for structure, arch curve and trim |
| **Mirror** | Exact copies of any shape | Axis X, Z or X + Z; preview, both or replace; 1- or 2-block mirror centre; offset |
| **Randomise** | Floors, walls or blocks filled with a palette | Circle, oval, square or rectangle region, size, thickness, floor or wall; the palette and pattern come from the Random tab |
| **Terrain** | Hills, mountains, valleys and more | Region shape and size, 21 templates + custom, variation layer, height range, roughness, max slope, procedural noise (scale, octaves, persistence, lacunarity, smoothing, ridges, erosion, valleys), seed, material layers, protected area with edge blending, natural / smooth / cliff boundary, contours and `Y=` labels |
| **Saved Build** | Any saved `.hwb` build | The saved blocks with their exact states, flip X / Z, upside down; move, rotate, mirror and build like any shape |

**Every tool shares the following:**

**Geometry**
- Exact integer geometry, so the result is symmetric, gap-free and the same every time.
- One coordinate system for every tool (see [Centre, offsets and dimensions](#centre-offsets-and-dimensions)):
  - the common 1×1 / 2×2 centre;
  - X / Y / Z offsets;
  - 90° rotation about the true centre.

**Materials**
- Material types *Blocks*, *Slabs* and *Stairs*, in any combination.
- Material roles (primary, secondary, trim, accent, highlight, step, support, cap, inner, rail, floor, outer edge and
  inner edge), each with its own block, slab and stairs.
- **Randomisation** of any tool (see [Randomisation](#randomisation)) and **material replacement** from the Build tab.
- **Material themes** (stone, deepslate, sandstone, oak, quartz, blackstone, prismarine, brick) set every role at
  once.
- Deterministic variation (none / subtle / medium / heavy) with variant blocks.
- Patterns: stripes, checker, rings, bands and sections.

**Details**
- Presets: None, Simple, Detailed, Architectural and Decorative, or Custom to pick each detail.
- Planar tools: edge trim, rings and accents.
- Cylinders: rims and bands.
- Spheres and domes:
  - latitude and longitude rings, equator band, pole caps;
  - radial ribs, crown and finial;
  - smooth curves built from stairs and slabs.
- Staircases: rails, support pillars, central column, landing, ring supports and wall attachment.
- Corridors:
  - ribbing, arch frames and keystones;
  - ceiling ribs, side columns, floor border;
  - wall panels, light recesses, alternating arches and entry frame.

**Spiral staircase**
- **Follow Circle Dimensions:**
  - the staircase is generated inside the exact footprint of a circle or oval;
  - every block, including rails, supports, landings and walls, is one of the circle's own blocks, so a 33 × 33
    spiral never sticks out of a 33 × 33 circle;
  - **Copy from Circle tool** takes the Circle (or Oval) tool's size; **Fit spiral inside circle** switches to Fit
    Inside, keeps all details inside and makes sure the steps fit;
  - the master circle's outline is drawn as a guide.
- **Fit Inside Circle:** the circle is the outer space (a tower wall). The steps use the circle shrunk by the wall
  thickness plus the clearance (automatic) or a manual inset, by exact erosion of the circle's own cells: a 16 circle
  with a 1-block wall gives a 14 × 14 staircase, and 32 with wall 1 and clearance 1 gives 28 × 28, all with the same
  2×2 centre. Only the *Wall* detail may use the wall zone, nothing enters the clearance, and the usable boundary is
  drawn in green.
- **Edges:** outer edge simple, straight, rounded (slabs), trimmed, stepped (curb), detailed (fascia, rail and
  highlights) or custom, 1–8 blocks thick in the *outer edge* material; inner edge open, central column, inner rail,
  inner wall, trim or decorative ring.
- **Thickness:** stair width grows inward from the fixed outer boundary, or an inner radius sets the well.
- **Details outside:** *Allow details outside boundary* (off by default) lets a surrounding wall sit outside.
- **Any size, odd or even:** the footprint is exactly the size you ask for: 32 is 32 × 32 with a genuine 2×2
  centre, 33 is 33 × 33 with a 1×1 centre. Sizes are never rounded to odd, with or without Follow Circle
  Dimensions.
- **Centre:** shared with the circle; angles and stair facing are measured from the true centre.
- Works with any combination of blocks, slabs and stairs.
- Stair blocks face along the direction of travel; inner and outer corner shapes are computed exactly as vanilla
  does.
- Slab mode: automatic, bottom, top or double.
- Warns about impossible settings, such as a stair width larger than the radius, too little headroom or steps too
  far apart to walk.

**Corridor / arch**
- Blocks, Slabs and Stairs in any combination, assigned to the **structure**, the **arch curve** and the **trim**
  (for example blocks for the walls, stairs on the curve, slabs for the frames).
- Stairs follow the curvature on both faces of the arch: upside-down underneath, upright on the roof, each facing
  away from the air it smooths.
- Slabs take the half that matches the exposed side, or the chosen slab mode.
- Shaping replaces blocks in place, so the corridor keeps its exact dimensions.

## Randomisation

The **Random** tab holds a palette: blocks from the block picker, each with a percentage (normalised to 100%
automatically, or checked when normalising is off) and an on/off switch.

- **Exact counts.** Blocks are ranked by the pattern and cut into runs of exactly the palette's shares, so 60 / 40 of
  400 blocks is always 240 / 160.
- **Modes:** weighted, fully random (equal shares) or deterministic. The same seed always gives the same result;
  *Randomise again* picks a new seed and *Lock* keeps it.
- **Patterns:** completely random, subtle, natural, clustered (cluster size), patchy, gradient, edge weighted, centre
  weighted, striped, radial, noise and custom noise (scale, strength, octaves, contrast, threshold).
- **Never changes geometry:** only materials change; positions, shapes and counts stay the same.
- **Selective:** choose which roles are randomised; protect the outer edge in one block with 0–100% edge variation.
- **Symmetry:** spirals can keep each step in one block or repeat every revolution; mirrored copies can repeat the
  original or be randomised independently.
- **Breakdown:** the exact counts are listed from the final blocks.

## Terrain

- **Templates:** flat, rolling hills, hill, twin hills, long ridge, low rolling hills, mountain (rocky, jagged, layered,
  volcanic, alpine), mountain range, foothills, valley, ridge, plateau, crater, basin, island, cliff, or custom.
- **Quality:** columns are solid (no holes), neighbouring columns differ by at most the max slope (no spikes), and
  smoothing and erosion-like passes soften the result.
- **Material layers** (Materials tab): any number of layers from the top, each a thickness (0 = to the base) and one or
  more blocks with exact percentages. The default is grass, 3 dirt, then stone with andesite.
- **Protected area:** a circle, oval, square or rectangle with its own size and offset that is never changed (the
  validator blocks any build that would). The terrain blends into the real ground around it (sharp, smooth, natural
  or very smooth, with a radius), sampled from the world on the client when the terrain is placed.
- **Boundary:** natural (meets the surrounding ground), smooth or cliff.
- **Display:** contour lines every N blocks with `Y=` labels in world heights, and the protected area and boundary
  outlined.

## Saved builds

The **Builds** tab saves the current build to `.minecraft/howtobuild/builds/<name>.hwb`:

- **Exact build:** every block's exact state, from the preview as shown, the generated shape, exactly what Build would
  place, or the real world blocks inside the preview's bounds. **Procedural preset:** the tool and all settings, which
  regenerate.
- **Metadata:** name, author, description, tool, settings, seed, centre cells and offsets. Positions are relative to
  the origin: the saved centre (so 1×1 and 2×2 centres are kept) or the build's minimum corner.
- **Format:** a compact palette of block states plus 4 integers per block, gzip-compressed and validated on read. A
  damaged file is reported, never crashes the game, and blocks from mods that are not installed are shown as purpur
  and not built.
- **Safe:** files are written in the background through a temporary file that is read back before it replaces
  anything, so a failed save never damages an existing build.
- **Loading** places the build as the *Saved Build* hologram. Saved = placed: move it with the centre and offsets,
  rotate it, mirror it, flip it or turn it upside down; every block state turns with it.
- **Compare with world** (Build tab) counts correct, missing, incorrect and extra blocks.
- The list shows each build's size, block count, number of states and file size. *Open folder* imports and exports
  `.hwb` files.

## Centre, offsets and dimensions

Every tool, the hologram, labels, mirror, progress tracking and command building use the same rules:

- **Dimensions are exact.** 33 × 33 means the generated footprint spans exactly 33 blocks along X and 33 along Z.
- **Centre.**
  - Odd sizes have a 1×1 centre and even sizes a 2×2 centre; *Center mode* can force either.
  - A forced centre that does not fit the size shows a warning instead of changing the size.
  - The centre is drawn as glowing blocks with a beam, visible through walls; *Show centre* toggles it.
  - While you pick a centre, the full 1×1 or 2×2 centre is previewed.
- **Offsets.**
  - X / Y / Z offsets move the shape relative to the selected centre: centre (100, 80, 200) with offset (+5, +2, −3)
    builds at (105, 82, 197).
  - Changing sizes or tools keeps them.
- **Y.**
  - For floor shapes, cylinders, domes, corridors and spirals, the centre's Y is the lowest layer, and nothing is
    generated below it.
  - Spheres and wall shapes are centred on it.
  - There is no hidden vertical adjustment; only your Y offset moves the shape up or down.
- **Preview = build.** The hologram and the `/fill` / `/setblock` commands come from the same block list, so a
  command build changes exactly the previewed blocks, and nothing under them.
- **Dimension debug** (Center tab) shows the exact world bounds (min / max X, Z and Y), size and centre.

## Labels

Labels are measured from the generated block coordinates, never from the input values, so a label always matches
the blocks.

- **Sections**:
  - flat shapes are split into straight runs and rectangles (`7 × 1`, `4 × 3`), each with a CAD dimension line;
  - 3D shapes are split into exact boxes per material.
- **Overall size**: width (X), length (Z) and height (Y) lines.
- **Summary**: tool, width, height, length, thickness, radius, diameter, steps, revolutions, material, block count.
  You choose which appear.
- **Formats**:
  - Full (`6 × 1`), Simplified (`6`), Width only, Height only, Named (`WIDTH: 6`);
  - a custom template such as `W {width} / H {height}`;
  - units and decimals.
- **Style**:
  - placement: automatic, above, below, inside, outside, left or right;
  - text size and opacity; background, border and padding;
  - leader lines, distance scaling, facing the player or fixed;
  - visible through walls.
- Labels fade in and out and move aside or hide when they would overlap.

## Building

| Method | What happens |
|---|---|
| **Normal placement** (default view) | You build by hand along the hologram. Placed blocks turn green, wrong blocks red, and the HUD counts progress. Nothing is automated. |
| **Command build** | The build screen shows a full summary and asks for confirmation. Then `/fill` and `/setblock` commands are sent through the normal command path, as if you typed them. |
| **Export** | Copy the commands, or save them as `.mcfunction` (with an undo file) under `howtobuild/exports`. |

The **Build** tab starts with a summary of what will be built: tool, size, centre, block and material counts, details,
randomisation, mirror and spiral inset. The material list shows a 3D icon, the exact count and share and a summary of
states (facing, half, type, …) for each block, filtered by All, Blocks, Slabs, Stairs, Details, Structural or Accent.
Click a material to replace it everywhere; facing, half, shape, type, waterlogged and every other property both
blocks share are kept. *Show block list* lists every exact state. For normal building it checks your inventory;
for command building it shows the command count.

Command building:

**Planning**
- **Exact.** Blocks are grouped by their exact block state and split into exact boxes. A `/fill` never covers space
  that should stay empty, and stair facing, half and shape and slab type are preserved.
- **Within limits.** Boxes are split at the fill limit (32768 by default) and built bottom-up. Blocks that are
  already correct are skipped. *Keep existing* uses `fill … keep`.

**Permissions and rate**
- **Permission.** The mod checks the permission level and the command tree the server sent. Without permission,
  building is unavailable and the screen says why; copy and save still work. Nothing is spoofed, and the server
  checks every command.
- **Rate.** Operators send 2 commands per tick by default (configurable). Players who may use the commands but are
  not operators are throttled to 1 command every 1.25 s, which stays under vanilla spam protection.

**While building**
- Pause, resume and stop.
- Progress and estimated time remaining.
- Server errors (unknown command, area too large, unloaded chunk, invalid block) pause or stop the build with an
  explanation. Retry re-sends the last two seconds of commands, which is safe because `/fill` and `/setblock` are
  idempotent.
- The success message for each command is hidden from your chat while building.

**Safety**
- Warnings before replacing existing blocks, blocks with contents (chests, signs), unloaded chunks and positions
  outside the world height.
- **Undo** restores the blocks that were there before the last build (as your client saw them).

## Using it

| Key (default) | Action |
|---|---|
| `H` | Open How to Build |
| `J` | Select the centre (left-click confirms, right-click cancels, sneak targets the block itself) |
| *(unbound)* | Select the mirror centre, show/hide the hologram, open the build screen, pause/resume a command build |

The screen takes about two thirds of the window and adapts to the resolution, so you can watch the hologram change
beside it.

**Layout**
- **Tools**: the tool list, with favourites first (★), and a Simple / Advanced switch.
- **Tabs**: *Geometry*, *Center*, *Materials*, *Random*, *Details*, *Labels*, *Mirror*, *Build* and *Builds*.
- **Preview pane**: on wide screens a third column shows a top-down map of the preview (each column in its top
  block's colour) with its size, block count and flags; it can be hidden.
- **Sections**:
  - sections are laid out in two columns when there is room;
  - every section header can be clicked to collapse it.
- **Center section** (in the Geometry and Center tabs):
  - centre mode, the centre's coordinates and size;
  - the X / Y / Z offset;
  - Select Center and My Position.
- **Help**: every control has a tooltip, and **?** opens help for the current tab.

**Materials and presets**
- **Block picker**: 3D item icons, search by name, id, namespace (`create:`) or tag (`#logs`), and categories (All,
  Building, Full blocks, Slabs, Stairs, Walls, Pillars, Decorative, Functional, Modded).
- Combine Blocks, Slabs and Stairs to show any block that is one of those types.
- Types are decided by the block's class and shape, not its name, so modded blocks work.
- **Presets** save and load complete setups.

**Preview vs build**
- **Preview** only shows or hides the hologram.
- **Build…** opens the separate build screen, where nothing happens until you confirm.

Settings are saved to `config/howtobuild.json`. An existing `config/how-to-circle.json` is migrated on first start
and left in place. The hologram's centre belongs to the current world and is cleared when you leave it.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer for Minecraft 26.2.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) 0.161.0+26.2 or newer and `how-to-build-<version>.jar` in
   your `mods` folder. Remove the old `how-to-circle` jar; How to Build replaces it.
3. Java 25 is required, the same as Minecraft 26.2.

The mod is client-only and works on any server. Command building is only offered where the server already allows
those commands.

## How it works

```
GUI ─► config ─► BuildTool.generate (background thread, pure) ─► GeometryPipeline
       (smoothing, rotation, patterns, variation, randomisation, stair shapes, validation) ─► GeometryResult
   ─► MirrorTransform ─► MaterialResolver (BlockStates) ─┬─► RenderMesh  ─► HologramRenderer
                                                         ├─► LabelSet    ─► DimensionLabelRenderer
                                                         ├─► ProgressTracker (normal building)
                                                         └─► CommandPlanner ─► CommandExecutor / export
```

**Geometry**
- Tools only describe their parameters and write placements: (x, y, z, role, shape, material). A material is either
  the role's block, a palette block or an exact saved state; rotation and mirroring transform exact states too.
  Shared stages do everything else.
- **One result for everything:** the hologram, labels, mirror, material counts, saves, commands and normal building
  all read the same `GeometryResult`. `GeometryValidator` checks it (duplicates, blocks below the base, inside a
  protected area or outside the region, invalid materials) and blocks building when it fails.
- All curves use exact integer tests on doubled coordinates.
- Hollow shapes are built by erosion, so walls have exactly the requested thickness and are watertight.
- Mirroring uses `x' = P − 1 − x` with the plane `P` in doubled coordinates, so every copy is exact.

**Caching and threading**
- Generation runs off the render thread.
- Each later stage is cached by its inputs, so nothing is regenerated per frame.
- Changing a label setting rebuilds only the labels.

**Rendering**
- Uses Fabric's `LevelRenderEvents.COLLECT_SUBMITS` with vanilla render pipelines; there are no raw OpenGL calls.
- Hidden faces are culled and coplanar faces merged.
- Edges are only drawn near the camera.
- Shapes are limited to 1,000,000 blocks, with an estimate checked before allocating.

Measured by the client game test on the GitHub Actions runner (software OpenGL), as CPU time per frame for the
hologram and labels:

| Shape | CPU per frame |
|---|---|
| 100 × 100 filled circle | 0.35 ms |
| 100 × 100 outline circle | 0.43 ms |
| 64-block hollow sphere | 7.3 ms |

## Building from source

Requires JDK 25. The Gradle wrapper downloads Gradle 9.7.1.

```sh
./gradlew build              # compiles and runs the unit tests; the jar is in build/libs/
./gradlew runClient          # development client
./gradlew runClientGameTest  # real client running the end-to-end acceptance test
```

The project comes from the official Fabric template for 26.2 (Loom 1.18, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2)
and uses Mojang's official names. See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the architecture.

### Project layout

```
src/main/java/com/howtobuild/        pure Java, no Minecraft classes (unit-tested)
  geometry/    GeometryResult, Placement, BlockShape, Mask2D, Grid3D, Solids, BoxDecomposer, StairShapes, …
  tools/       BuildTool, ToolParameter, GeometryPipeline, ToolRegistry, capability/, impl/ (all tools)
  details/     DetailFeature, presets, patterns, variation, SmoothingPass
  transform/   MirrorTransform, MirrorSettings
  dimensions/  SectionDetector, DimensionFormatter, LabelLayout
  commands/    CommandPlanner, CommandPlan, MaterialCounter
  palette/     WeightedPalette, RandomSettings, Randomiser, Noise
  saves/       BuildFile, HwbCodec (.hwb), SavedBuilds
src/client/java/com/howtobuild/
  client/      HowToBuildClient (entrypoint), BuildSession, BuildLibrary, ProgressTracker
  config/      HowToBuildConfig, LegacyMigration, PresetStore, …
  materials/   BlockCatalog, MaterialResolver, MaterialFamilies
  render/      RenderMesh, HologramRenderer, LabelSet, DimensionLabelRenderer, SelectionHud
  building/    CommandPermission, CommandExecutor, BuildAnalysis, CommandExport
  gui/         HowToBuildScreen, BlockPickerScreen, CommandBuildScreen, PresetScreen
  input/       KeyBindings, CentreSelectionHandler
src/test/java/       JUnit tests
src/gametest/java/   Fabric client game test
```

### Tests

**Unit tests** (`./gradlew test`) cover:
- every generator at many sizes, odd and even;
- exact bounds, symmetry, thickness and hollowness;
- stair facing and shapes, and every Blocks / Slabs / Stairs combination for the staircase;
- corridor profiles and repeating arches;
- mirror exactness for every axis, centre width and offset;
- label formats and templates;
- the command planner: exact coverage, no empty space, state preservation, splitting and undo;
- the unified coordinate system:
  - a spiral following circles and ovals (odd, even, 7 to 33 blocks) stays inside the circle's blocks for every
    stair width, detail preset and material combination;
  - circle and spiral share their 1×1 and 2×2 centres, and offsets move both identically;
  - no base-anchored tool generates below its base;
  - rotation keeps 2×2 centres;
  - commands expand back to exactly the preview's blocks;
- the corridor's 7 Blocks / Slabs / Stairs combinations, curve-following stairs, slab halves and explicit part
  assignment;
- randomisation: exact counts for every pattern, determinism, unchanged geometry, selected roles, edge protection and
  variation, per-step and per-revolution symmetry, independent mirror randomisation;
- terrain: every template and variation, slopes and solid columns, exact regions, protected areas never touched,
  blending into the ground, boundaries, layers with exact shares, contours;
- spiral Fit Inside: 16 with insets 0 / 1 / 2, 32 with a 2×2 centre, nothing in the wall or clearance zone, manual
  inset, edge options and per-step groups;
- `.hwb`: round trips, damaged and invalid files, a failed save keeping the old file, the folder cache, saved = placed,
  flips, rotation and mirror of exact states, material counts;
- block state transforms (rotation, mirror, upside down) for stairs, logs, doors, signs, fences, chests, rails, slabs
  and levers;
- a translation for every tool, parameter, option, section and role.

**Client game test** (`./gradlew runClientGameTest`, run in CI under Xvfb) uses a real client:
- the GUI and every tab;
- circles and ovals;
- a hollow cylinder with trim;
- a spiral of blocks, slabs and stairs;
- a hollow detailed sphere;
- a dome with ribs and crown;
- the 6 × 10 × 20 arch corridor;
- an exact mirror with offset;
- block picker filters against the real block registry;
- the label `6` updating to `6 × 1` immediately;
- a spiral following a 33 × 33 circle staying inside it in the world;
- 32 × 32 circle and spiral sharing a 2×2 centre;
- offsets moving circle and spiral identically;
- an arch corridor with a stair curve and slab trim;
- render cost of large shapes;
- centre selection with real clicks;
- randomising a 20 × 20 floor at 60 / 40 (exactly 240 / 160) and a spiral without changing its geometry;
- terrain with a protected area;
- a spiral fitted inside a 16 circle (14 × 14, 2×2 centre);
- material replacement keeping stair facing;
- saving a spiral to `.hwb`, loading it back with every block state identical, and comparing it with the world;
- that previewing changed no blocks;
- finally, a command build as an operator: a detailed spiral with landings on solid ground. It checks every block
  state on the server and that the ground below is untouched, then undoes the build.

Screenshots are saved to `build/run/clientGameTest/screenshots`.

## License

CC0-1.0, the same as the Fabric template.
