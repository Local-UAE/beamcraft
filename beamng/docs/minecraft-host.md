# Minecraft hosts: BeamNG cars in Minecraft

Design agreed with Jas on 2026-10-02. Branch `minecraft-host`, off `beamng-dev`. The forward
direction (Minecraft inside BeamNG) stays the default and keeps working.

This file is the build log, oldest first: later sections replace earlier ones where they differ
(the cutouts below became Minecraft drawing the car itself). Commit hashes and branch names refer
to the private development history, not this repository's.

## Status (2026-10-02)

Steps 1-6 below are done and proven in the running games (smallgrid, pickup, BeamNG 0.39.4):

- **Host world.** "BeamNG Host" superflat, grass top at y = -60 = BeamNG z = 0. The pickup's box
  sits at y -60.02 on the grass; Steve is placed beside it.
- **Cutouts.** The real BeamNG pickup shows on the Minecraft grass under Minecraft's sky, wheels
  included, with no BeamNG grid around it. A 2-high brick wall between Steve and the car hides
  everything but the top of the cab; a cow standing in front of the car covers it. 1124 skin
  triangles, ~8.7 KB of node offsets per frame.
- **Driving (keyboard).** W: 8.7 m/s after 1.5 s; D while heading south turned the car west
  (fwd (0, -1) -> (-0.99, -0.17)); S brakes, then reverses once stopped (BeamNG's arcade gearbox).
  The chase camera swings in behind the moving car.
- **Crashes and chaos.** Into the brick wall at 13.4 m/s: stopped dead, front end crumpled (hood
  buckled, visible through the cutout). TNT beside the car shoved it 1.4 m away at 4.3 m/s. The
  car hit a cow at 10.8 m/s (7.8 damage).
Screenshots (game area only): `img/host-pickup-on-grass.png`, `img/host-wall-in-front.png`,
`img/host-cow-in-front.png`, `img/host-crash-crumple.png`.

- Fixed on the way: the car "hit" its own driver (now spared), the name tag floated over the car
  while driving, a driver's punch shoved their own car.

**Controller: TESTED by Jas** ("controller works", 2026-10-02). Getting out put him inside the
grass (the box bottom is a few cm below ground); the dismount spot is now snapped to the floor
(not yet re-tested). Not proven yet: engine
sound with BeamNG in the background (the mute setting is off in the test folder; untested by
ear), more than one car at a time, cars other than the pickup.

Known issues: at speed a car's edge can lag its cutout by a frame (two render clocks); the
driver takes no crash damage yet (forward mode's `CarRide` does that for BeamNG-side driving);
Minecraft takes 40-65 s to quit while linked.

## Native mode: Minecraft draws the car itself (2026-10-02, branch `native-car`)

Jas wanted the overlay gone ("fully built in"). Now, in the host world, Minecraft draws the real
BeamNG car with its own renderer and BeamNG is only the physics engine:

1. Getting into a car asks BeamNG to export it (`vexport`) with BeamNG's own glTF exporter, into
   BeamNG's user folder. Nothing of BeamNG's is copied into the mod or the repo.
2. Minecraft loads the .glb (`nativecar/CarModel`) and binds every vertex to three nodes of its
   own flexbody's node group (`nativecar/FlexBinding`, the way BeamNG's flexbodies follow nodes).
3. Every frame the VMESH node stream bends the mesh (3-4 ms for 153 k vertices) and
   `client/NativeCars` draws it with a small shader of its own after the entities: textures,
   paint colour, see-through glass, depth-tested like any Minecraft thing.
4. The overlay and cutouts are off in this mode, and BeamNG's own world render is switched off
   (`render_main`), which took its GPU load from 83% to 18%.

Proven live (Scintilla on smallgrid): car drawn by Minecraft in a normal Minecraft window with
paint, rims, red calipers and glass; driving and steering from Minecraft; crashed into the brick
wall, the front crumpled visibly in Minecraft's rendering; Minecraft 107 fps with it. Screenshots:
`img/native-scintilla.png`, `img/native-crash.png`.

Not done / not proven: only the car you get into is exported (BeamNG's exporter works on the
player car), so other cars aren't drawn in native mode yet; the ground shadow added for native
cars didn't show up in the test; props (steering wheel, visors) are skipped; no shadows; holes dug
in the ground aren't holes for BeamNG.
`native off` (dev command) or `-Dbngbridge.noNative=true` brings back the cutout mode.

### Driving it properly (2026-10-02)

**Smoothness.** BeamNG sends the node stream every frame with its own timestamp. Minecraft keeps
the last 8 snapshots (`link/MeshTimeline`) and draws the car 25 ms in BeamNG's past, blended
between the two snapshots around that time, so uneven UDP arrival doesn't show as stutter. The
camera reads the same blended pose, so car and camera never disagree. Jas: "It is smoother".
Not measured yet: `native stats N` while driving, with blend on and off.

**Materials.** BeamNG cars use layered materials (v1.5): a base layer, a paint layer whose
palette mask picks paint 1, 2 or 3, opacity masks, AO and metallic maps. `nativecar/BngMaterial`
reads them from the glTF extras, and the side file adds what the exporter leaves out: base
colour factors per layer (untextured trims are black only through them), detail maps (the carbon
weave, tiled 80 x 40 on UV1) and which UV set the AO map uses. `CarShader` lights it with sky
light from above, darker bounce light from the grass, sun specular and a Fresnel reflection of
Minecraft's sky, stronger on clear-coated paint. Carbon parts that drew white before now come out
dark, as in BeamNG.

**Dashboard.** BeamNG's own electrics (gear, rpm, max rpm, gearbox mode) arrive in STATE at 20 Hz
and show bottom right while driving, with the key hint above them.

**Controls** (`client/CarControls`, rebindable under Controls > "BeamNG car"). Each one runs the
same vehicle Lua as BeamNG's own binding, through `vehicle_action`:

| Action | Key | Controller |
|---|---|---|
| Shift up / down | X / Z | X / A (Square / Cross on Jas's PlayStation pad, 2026-10-03) |
| G923 paddle shift up / down | Right / left paddle | Buttons 4 / 5 |
| G923 H-shifter | Driving Force Shifter: gears 1–6 / R; gate release selects neutral | Buttons 12–18 |
| G923 clutch | Clutch pedal | Axis 3 |
| Gearbox mode (arcade / realistic) | M | RB |
| Reset car (spawn point, repaired) | R | Y |
| Repair in place (tap) / rewind (hold) | Backspace | View |
| Ignition / starter (hold) | V | LB |
| Horn (hold) | H | left stick click |
| Headlights | N | D-pad up |
| ESC / traction control / drive mode | G | D-pad right |

ESC is BeamNG's own toggleESCMode (`core/input/actions/vehicle.json`, Ctrl+Q in BeamNG): the next
drive mode on cars that have them (they set ESC and traction control), else the next ESC setting
(on, traction control only, off, as the car defines them). The dashboard shows the current one
on its right (`dash.esc`: `driveModes.getDriveModeData(getCurrentDriveModeKey()).name`, else
`esc.getCurrentConfigData().name`). BeamNG has no separate traction control action.

The G923 clutch axis and H-shifter positions are forwarded through BeamNG's native `clutch` and
`gearR`/`gear1`–`gear6` actions. The shifter selects a gear while held and releases to neutral
between gates. Selecting an H-pattern gear switches an arcade manual gearbox to realistic mode,
as BeamNG's own gear-selection action does.

Throttle, brake and steering stay on WASD or the triggers and left stick, B is the handbrake, and
the right stick turns the chase camera. Keys are read from the window's raw key state, so X still
works although vanilla also binds it (Load Hotbar Activator). Anything held is released on
getting out, opening a screen or leaving the world.

Proven 2026-10-02 with real keypresses into the Minecraft window: M switched arcade to
realistic, Z went N -> R -> P, X went P -> R -> N, M switched back, each in BeamNG about 50 ms
after the key went down. Reset (bridge probe) put the Scintilla back at the spawn point in N with
the driver still seated. Not tested by hand: the controller buttons, starter, horn, lights.
Screenshot: `img/native-controls-hud.png`.

**Skin.** `minecraft/run/bngbridge-local.properties` (gitignored) sets the dev username and skin;
the skin is fetched from Mojang's public API only.

### Car selection (2026-10-02)

**B** opens the car picker: BeamNG's own list (the one its vehicle selector shows), with BeamNG's
preview pictures. Cars and trucks by default, "Everything" for props and trailers too, a search
box, then a model's versions (configs), the default first. Picking one:

- sitting in a car: swaps it in place, as BeamNG's selector does ("Add a new car" puts one beside
  you instead);
- on foot: the car is put on the ground 8 m in front of you, side-on.

Every car in range is exported and drawn natively, not just the one you sit in. Minecraft exports
them one at a time (`vexport {id}`); BeamNG's exporter only does the player's car, so the bridge
lends the car the player slot for the length of the call. Until every car in range can be drawn,
the cutout mode shows them all, so a new car is never invisible. A model's first export converts
its textures and stalls BeamNG for 20-30 s (the Minecraft side says so); after that it takes
under a second.

Proven 2026-10-02: the picker listed 38 cars and trucks (122 models with everything) with their
pictures; the Bruckell Moonhawk's 25 versions; picking the default put a red Moonhawk on the grass
in front of the player, drawn natively 24 s later (first export of that model); the Scintilla was
exported without being entered. Jas then drove both with the controller. Screenshots:
`img/native-picker.png`, `img/native-picker-versions.png`, `img/native-two-cars.png`.
Not tested yet: "Replace my car" while seated, props and trailers.

### Holes, TNT and getting out (2026-10-02)

**Holes.** A hole dug or blown in Minecraft is a hole for BeamNG's cars. smallgrid's floor is
solid at z = 0 and can't be lowered: moving its GroundPlanes, or deleting all four, leaves the
floor exactly where it was (rays and a car both said so). So the floor is Minecraft's bedrock
now: `HOST_REGION` puts the bedrock top (y = -63) at BeamNG z = 0 and the grass top (y = -60) 3
blocks above it. The dirt and grass layers between go to BeamNG as boxes, per chunk, merged (`GroundSync`:
a flat chunk is one box, a hole splits it into a few; smallgrid's collision cube scaled, which
scales its collision too). Chunks within 6 of the player and 2 of every car are sent, again
whenever a block in them changes. Once the ground around the player is in, `ground_on` lifts the
cars still standing on BeamNG's floor 3 m onto it; when Minecraft leaves, the boxes go and the
cars come back down.

Proven 2026-10-02: a 6 x 6 pit dug with `/fill` showed in BeamNG's rays at exactly those cells,
and the Moonhawk put over it dropped to the bedrock (z 0.23) and showed at the bottom of the pit
in Minecraft (`img/native-hole.png`). Both cars were lifted onto the ground on connect and
lowered again on leaving.

**TNT blows cars up.** A blast within 3 m of a car's body (0.75 m per unit of Minecraft power)
does what BeamNG's own "explode vehicle" does: `fire.explodeVehicle()` and
`beamstate.breakAllBreakgroups()`; farther away it only pushes. Before, only a blast inside the
car's outline set it off, so Jas's TNT only threw the Scintilla 40 m. Proven: a TNT-strength blast
2.5 m from the Scintilla broke it apart and threw it 13 m.

**Getting out with the controller:** right stick click (holds Minecraft's sneak briefly, as Shift
does). Not tested with a controller yet.

### Terrain: a real Minecraft world (2026-10-03, night)

`scripts\run-minecraft.ps1 -Mode terrain` opens "BeamNG Terrain": Minecraft's normal generation,
everything else as in the host world (same region, scale 1.4, cars, items, perspectives).

**Why not boxes.** Minecraft terrain climbs one block at a time, and one block is 0.71 m at the
host scale: a wall, not a step, for a car. So the ground goes to BeamNG as a terrain, a
heightfield, with the steps smoothed into slopes.

- `TerrainSync` reads 1024 x 1024 columns around the player: each column's surface is the top of
  its highest block that blocks motion and isn't a log, leaves or fluid (so the sea floor under
  water, the ground under trees). `TerrainHeights` blurs it over 5 x 5 blocks: a one-block step
  becomes a slope of 0.2 blocks per block (11 degrees); a column the blur would move by more than
  a block keeps its height, so dug pits, cliffs and built walls stay sharp.
- It writes a 16-bit heightmap PNG into BeamNG's user folder (the path comes in the welcome
  message) and sends `terrain_load`. BeamNG builds a `TerrainBlock` with its own terrain
  generator (`util/terrainGenerator.lua`), one sample per block, 0.714 m apart: 450-540 ms for
  1024 x 1024. Chunks not loaded yet stand in at the median height until they load; they and every
  block change go as `terrain_cells` patches (edits first). Driving within 192 blocks of the edge
  builds a new terrain around the player.
- The load waits for `terrain_loaded` and is sent again every 10 s: the first one was lost while
  BeamNG stood still for a 23 s car export (UDP). Each load has an `id`; BeamNG answers a load it
  already built with `same: true` instead of building it again, which would throw away the patches
  sent since. Minecraft ignores an answer to an older id.
- **Water.** The load carries `sea`, Minecraft's sea level in BeamNG metres, and BeamNG puts a
  `WaterPlane` there (the fields BeamNG's editor gives a new one). It is real BeamNG water: vehicle
  code finds it with `obj:inWater`, the check BeamNG's engine, wheels and fire code make, and an
  engine whose water-damage nodes are all under hydrolocks. The dashboard says "in water" and
  "engine flooded" (red) from those same two checks. Rivers at sea level get water too; lakes above it
  don't.
- Cars under the surface are lifted onto it, and once a session the player's car is brought to
  open ground near the player (the nearest flat, dry, tree-free 9 x 9 patch within 40 blocks),
  instead of the player being moved to the car. Only tree trunks become BeamNG cubes here
  (within 4 chunks): everything else is terrain, and leaves would be thousands of cubes.

**Proven:** the Porsche drove down a terraced hillside at 56 km/h and then into the sea. A 3 x 3
pit dug in Minecraft read y 64.0 in BeamNG's terrain within a second, while 4 blocks away still
read 71.9. With the water in, the Porsche on the sea floor 11 m down reads `wet: true,
flooded: true` in BeamNG's state, at 0 rpm, and Minecraft's dashboard shows "engine flooded". A
load answer lost at startup was resent and answered "already built" without a rebuild. Jas went
about 1100 blocks from the start (2026-10-03 midday) and the terrain was built anew around him
three times on the way, BeamNG answering each in about 0.6 s. **Not yet:** a car driven in from
the shore (only one already sunk was seen), lakes above sea level, terrain materials (BeamNG logs
`ground model not found for collision: 'DEFAULT' - using asphalt`, so grass and sand grip like
asphalt), caves under the surface. A `WaterPlane` has no edges, so dry ground below sea level
(a valley, a hole dug deep) should count as water in BeamNG too; expected, not seen.

### Settings, and real blocks (2026-10-03, midday)

**O**, or "BeamNG settings" at the top left of the pause menu, opens the crossover's settings
(`client/SettingsScreen`, Minecraft's own options list with a tooltip on every row). They live in
`run/config/bngbridge.json` (`BridgeSettings`, one immutable record read by the server and render
threads) and take effect together when the screen closes:

| Setting | What it changes |
|---|---|
| Ground: Smooth (arcade) / Real blocks | how the terrain world's ground reaches BeamNG (below) |
| Slope length | smooth ground: a one-block step spreads over 3, 5, 7 or 9 blocks (blur radius 1-4) |
| Water | the sea's `WaterPlane`, or none (cars drive on the sea floor) |
| Dent strength, Hit push | 0-300 % of the dent and of the shove a hit gives the car (0 % sends none) |
| Crashes hurt you, Cars hit mobs | `CrashDamage` for the driver; `VehicleHits` for whatever a car runs into |
| Chase distance, height, Camera follows | the third-person camera (5.5 m and 1.2 m by default; the swing back behind a moving car) |
| Dashboard, Debug view | the gear/speed/rpm HUD; the status text (F9 toggles it too, and it is now remembered; off by default) |

Dev commands: `settings` prints them, `settings open` shows the screen, `set <name> <json>` changes
one (`set ground "blocks"`).

**Real blocks.** No blur, and 2 x 2 terrain samples per block: a 2048 x 2048 terrain for the same
1024 x 1024 blocks, samples 0.357 m apart, so a block's top is flat and a one-block step rises
0.71 m over 0.36 m (63 degrees). That is a wall to a wheel of 0.33 m radius and to a bumper.
Patches are split into messages of at most 512 samples (BeamNG reads 8 KB a datagram), two per
chunk. In both modes a column's height is now the top of what its block collides with, so a slab
is half a step and a dirt path 15/16. Changing the ground, slope length or water builds the
terrain again (a new load id).

**Proven:** `set ground "blocks"`: Minecraft wrote the 2048 heightmap in 324 ms and BeamNG built it
in 736 ms. Across a one-block step BeamNG's terrain rose 0.700 m within 0.35 m, where the smooth
terrain at the same spot rose 0.056 m (`getTerrainHeight` every 5 cm along five 30 m lines). Jas
opened the screen himself and switched back to Smooth; BeamNG rebuilt in 0.36 s. 155 Java and 51
Lua tests pass. **Not yet:** a car driven into a one-block step (it should stop it: not seen).

**Garages: drive into buildings (2026-10-03, afternoon).** A heightfield has no overhangs, so a
house used to reach BeamNG as one solid lump the height of its roof. Now each column is scanned
down from the top (`TerrainHeights.columnSurface`): a solid run with a built block in it (anything
but world-generation blocks, `TerrainSync.natural`), under 8 blocks deep and with at least 2 blocks
of air under it, is a roof, a lintel or an upper floor, and the ground is further down. Walls (no
air under them) stay walls, a bridge over water stays a bridge, and natural overhangs and caves
stay as they were. The setting "Drive into buildings" (on by default) turns it off.

**Downloaded maps.** `scripts\run-minecraft.ps1 -Mode terrain -World <save>` opens a save of its own
from `minecraft\run\saves` (`-Dbngbridge.world`), and any world whose name ends in `[BeamNG]` is a
real-terrain world (`BngWorld.TERRAIN_NAME_TAG`). The first one is "A Modern House #9" by
Jumbo_Studio from minecraftmaps.com (a 1.16.5 superflat world, upgraded on open), saved as
`bng-house` and renamed "Modern House 9 [BeamNG]". Its garage doors were solid iron blocks
(87-91 and 94-98, y 4-6, z -240); they were filled with air. The dev command `press <text>`
presses a button on the open screen.

**Proven:** with the rule, BeamNG's terrain reads ground level (y 4) through both garage door
openings and the bays behind them, where it read the 7-block lintel before, and the house, its
walls and the bay divider stay solid (`getTerrainHeight` block by block over the house). 159 Java
tests pass. **Not yet:** a car driven into the garage.

**Shaders (2026-10-03, afternoon).** Sodium 0.6.13 and Iris 1.8.8 run in the dev client, plus the
Complementary Reimagined r5.9.3 shader pack in `run/shaderpacks` (`run-minecraft.ps1` downloads it,
SHA-512 checked; `-NoShaderMods` leaves Sodium and Iris out).
- They come through Gradle (`modRuntimeOnly` from Modrinth's maven, `build.gradle`), not `run/mods`:
  Iris's mixins fail to apply from `run/mods` in a dev environment (a `@ModifyArg` with a null
  descriptor). Newer builds (Sodium 0.8, Iris 1.8.14) were made with Loom 1.16, which Loom 1.11
  refuses ("Mod was built with a newer version of Loom"), hence 0.6.13 + 1.8.8. Iris's bundled
  libraries (antlr4-runtime 4.13.1, glsl-transformer 2.0.1, jcpp 1.4.14) are added as
  `runtimeOnly`, since a dev environment doesn't unpack them.
- A pack renders the world through its own buffers, so the car drawn with our shader among the
  entities was invisible. Now (`ShaderCompat`, `ShaderCarTarget`): when a pack is on, the cars are
  drawn into a target of our own whose depth is a copy of the world's depth at entity time, and
  once the pack has finished the frame (`GameRendererMixin`, after `LevelRenderer.renderLevel`) that
  picture goes over it with premultiplied alpha. Walls and blocks in front still hide the car; the
  car keeps its own lighting, not the pack's, and casts no pack shadow.
- Iris keeps its own record of GL state: it skips `GlStateManager._glUseProgram` when it thinks the
  program is already bound and holds back depth and colour masks during its passes. The car
  drawing now sets program, depth, blend, cull and masks with plain GL and puts the previous
  values back (`NativeCars.GlSave`); `CarShader` binds its program with plain GL too.
- Keys: Iris's defaults clash with ours (O opens its pack menu, R reloads shaders). In
  `run/options.txt` the pack menu is on I, toggling shaders on K, reload unbound.
- Dev: `native shaderdebug 1` tints the overlay red (it lands), `2` drops the world's depth, `3`
  logs the entity passes.

**Proven:** with Complementary on, the Scintilla shows in the garage, the hay bale in front of it
hides its nose; 176-181 fps looking out of the garage, 93-121 with the car in view (a 1600x900
window; 226 with Sodium alone).

**Cars lit by the pack (2026-10-03, evening).** The overlay above keeps the car out of the pack's
light: no sunset on the paint, no torchlight at night, no shadow. Now the car is drawn as an entity
(`PackCarRenderer`, from `VehicleRenderer.render`) with Minecraft's entity shaders, which Iris
swaps for the pack's own entity and shadow programs, so the pack lights it, shades it and puts it
in its shadow map like any mob.
- The bending stays on the GPU. Once a frame a transform feedback pass runs `CarShader`'s bending
  and writes every triangle out in the vertex layout Iris gives entity programs
  (`IrisVertexFormats.ENTITY`: position, colour, UV, overlay, light, normal, then `iris_Entity`,
  `mc_midTexCoord`, `at_tangent`, attribute locations 0-8, read from Iris 1.8.8's classes). A torn
  triangle is collapsed to a point, as `CarShader`'s geometry shader drops it. Each material is then
  drawn from that buffer.
- Materials are simpler under a pack: one texture (the painted layer's base map, else the first
  layer's) times one colour (the car's paint on painted parts, else the layer's base colour). The
  pack does the lighting; BeamNG's layered paint and clear coat are only drawn without a pack.
- A car Minecraft doesn't draw this frame (no entity on this client, beyond its tracking range)
  still goes over the frame the old way. `VehicleRenderer.shouldRender` ignores the entity render
  distance under a pack, so a car doesn't vanish at 160 blocks.
- Dev: `native packpath` switches between the two ways. **Off by default since 2026-10-03:** with no
  specular data the pack shades the car matte, and Jas found it "pastel", without the reflections
  of BeamNG's paint. `-Dbngbridge.packLitCars=true` starts with it on.

**Proven so far:** the bending program compiles and links on the RTX 3070 Ti (driver 617.14), and a
standalone transform feedback test wrote two triangles byte for byte as expected (positions bent
and scaled, the torn one collapsed, colour, overlay 0/10, light 240/240, normals, entity -1, mid
UV, tangent). 160 Java tests pass. **Not yet:** seen in the running game.

**The car on the garage roof.** Every Minecraft restart lifted the car onto the roof. Minecraft
told BeamNG to take the terrain out when its world closed, the car fell to smallgrid's floor
(z 0.14, rolling 3 m), and the next load's lift put it on whatever stood over it. BeamNG now keeps
the terrain when Minecraft leaves (`bridge.lua` dropClient, `TerrainSync.onServerStopping`), and a
linked world without a terrain sends `terrain_reset` when it opens. Proven: the car stayed at
z 47.99 across a quit and a restart.

**Two fixes found while Jas played (built and tested, not yet seen in the game):**
- A car's proxy only follows its car in its own tick, and an entity in a chunk that isn't ticking
  doesn't tick. The terrain world brought the pickup 1100 blocks to Jas, its proxy stayed behind,
  and the car was drawn with no hitbox, so the remover stick went through it. `VehicleBridge` now
  moves a proxy that isn't ticking to its car.
- A driver sits inside the car's box, and BeamNG's ground doesn't match Minecraft's blocks exactly,
  so in survival Jas kept taking damage while driving (no crash damage in the log). Suffocation
  (`in_wall`) is now cancelled for anyone in a BeamNG car, and other damage to a driver is logged.

### Shading, the driver's seat, a demo video (2026-10-03, night)

**The normals were wrong all along.** BeamNG's exporter writes vertex positions in glTF's axes
(y up) and the normals still in BeamNG's (z up): `util/export.lua:864-879` stores both as the
engine hands them over. Every car was lit as if its sides faced the ground, which is why cars
looked too dark and too shiny at once. Over 18 exports (10 models) the stored normals matched
their triangles at 0.33-0.43 (mean |cos| to the face normal), turned like the vertices
(x, y, z) -> (x, z, -y) at 0.91-0.97. `CarModel.bngNormalsToGltf` turns them on load. Found
with the shader's debug views (`native debug 1-5`: top-layer opacity, base colour,
metallic/roughness/clear coat, normal, sky).

**Shading follows BeamNG's layers.** `CarShader` builds each layer as `defaultMat.hlsl`
`processLayers` does (212-424): base, metallic, roughness, AO, clear coat and normal maps per
layer; a palette mask picks the three paints and their metallic, roughness, clear coat and clear
coat roughness (`veh:getMetallicPaintData()`, `shadergen.h.hlsl:163-192`); upper layers blend by
opacity. Lighting in linear colour: Minecraft's sun with a GGX highlight, the sky and ground as a
reflection blurred by roughness, a clear coat on top. The side file carries every layer's live
fields (the exporter's extras miss the palette switches, `roughnessFactor`, `opacityFactor` and
most UV sets). A base map BeamNG couldn't load is black, as BeamNG draws it (the M3's tyres:
"non pow2 size" in BeamNG's log). Side by side from the same camera, the M2 matches BeamNG's
render (a side-by-side capture kept locally; BeamNG shots via the dev `shot` handler).

**Props** (steering wheel, mirrors, pedals, needles) have their own vertex buffers and node
transforms in the export. `CarProps` places them through their node chain; their normals turn from
BeamNG's axes and with the node (0.85-1.0 match on the M2).

**Back faces** are culled where BeamNG culls them: its own `doubleSided` in the extras (the
exporter sets glTF `doubleSided` true for everything, `export.lua:270` vs `:800`). The M2's driver
camera sits 5 cm inside the headrest; BeamNG never shows it because the headrest's inside faces
are culled, and now ours are too.

**Sky occlusion** is baked per vertex on load (`SkyOcclusion`: a 3.5 cm voxel copy of the car, 16
cosine-weighted rays per vertex, glass left out so light comes in through the windows): the
cabin, wheel wells and panel gaps get less sky light, no sun and dimmer reflections. 306 ms for
the M2's 621k vertices.

**Perspectives**: F5 (controller D-pad down) cycles first person = the driver's seat on BeamNG's
own driver camera node (`onboard.lua:53-110`; `DriverEye`, the view turns with the car and the
mouse looks around inside it), third person = the chase camera (now 5.5 m behind, 7 made the car
small), third person front = from ahead looking back. The hand isn't drawn in the seat.

**Demo video**: `bridge/hostdemo.py stage|record|edit|review` scripts 11 scenes (walk up, get in,
chase, cockpit, front view, a wall crash, sword dents, TNT, the picker, the Porsche). It records
Minecraft fullscreen on the main screen with ffmpeg (ddagrab, Quick Sync: the laptop panel hangs
off the iGPU and NVENC can't open those frames), and only after one captured frame matches
Minecraft's own screenshot (an earlier take captured the desktop on the wrong screen; deleted).
Cuts with JASGENIUS / JASGEN1US watermarks in `demo-out/native/` (git-ignored).

### Door handles (2026-10-03, evening)

Look at a door handle (or the bonnet, the boot, a switch in the cabin) and press use, as clicking
it does in BeamNG. BeamNG calls these vehicle triggers: boxes pinned to the car's nodes, each with
up to three actions (`triggerEventLinksDict`). BeamNG's own click raycasts from its camera
(`be:triggerRaycastClosest`), which isn't where Minecraft's player looks, so:
- Each tick with a car within 12 blocks, `CarTriggers` sends the eye's ray (`trigger_aim`, BeamNG
  coordinates): the camera's in first person (in the seat that is the driver's eye), the player's
  own in third person. `triggers.lua` tests it against the triggers with an action on the cars
  within 12 m, each box placed as BeamNG places it (`Trigger:getCenter()`,
  `core_vehicle_triggerLabelPlacement.computeTriggerBasisAndHalfExtents`), and answers with the
  nearest one within 3 m (`trigger_hit`: ids, action titles, box). Only triggers whose bounding
  ball the ray passes get their box worked out, and the titles are cached per car, so a car's
  other few dozen triggers cost little on BeamNG's frame. An answer older than 300 ms is dropped.
- Only a trigger's first action is used (action0, else action1, else action2): BeamNG's second
  and third actions (a mouse's other buttons) have no key here yet.
- Minecraft outlines that box in yellow and names its action under the crosshair.
- On foot, the use key (right-click; a controller's use button) fires it instead of what it would
  have done (getting in). In the driver's seat it's right-click or the D-pad left, because the
  controller's use button is the brake there (a tap shorter than a tick still counts: the key is
  watched every frame). `trigger_use` runs
  `core_vehicleTriggers.triggerEvent('action0', 1/0, ...)`, the call BeamNG's own click makes.

**Proven so far:** 11 Lua tests (the ray-box test, the bounding-ball filter, nearest across cars,
reach, action titles), 160
Java tests, `bridge.lua` parses. **Not yet:** in the running game (needs the BeamNG mod reinstalled
and a Minecraft restart).

**The town map (2026-10-03, evening).** `run-minecraft.ps1 -Mode terrain -World bng-town` opens
"Town [BeamNG]": a city ("Red Rocks" by Crucial), a neighbourhood ("Millionaires Row" by
TacticGravity) and a rest stop ("Gas Station II" by EuphoriaLuna), all from Planet Minecraft. A
helper merged them on a flat plain and joined them with generated roads: the city's highway runs
east to a junction at x 445..455, north to the neighbourhood and south to the gas station. Road
surface y 23, spawn (448, 24, 8). The merge tool and the source maps stayed local
(the maps aren't ours to share). Two things went wrong on the first opening, and both are fixed:
- **A 1944-block pit.** The world's dimension is the BeamNG host's tall one (y -2032..2031), and its
  flat generator stacks layers from the floor. So every chunk Minecraft generated itself (the grass
  between the pieces) had its grass at y -1945, while the maps and roads sit at 23. BeamNG got a
  1970 m deep plain around the roads. The air layer is now 2032 high (bedrock at y 0, grass top 23),
  and the 1159 chunks already generated deep were deleted so they come back level. Proven: the next
  terrain read gave heights 20..41.
- **Heightmaps of the wrong length.** The 1.19 and 1.20 chunks carried 37-long heightmaps for a
  384-high world. Minecraft ignores those and leaves them empty, which reads as the world's floor.
  They were stripped (Minecraft primes missing ones on load), and `TerrainSync.surface` now finds
  the top block itself when a heightmap is empty.

**The GPU ran out of memory and the driver reset (2026-10-03, 16:18).** With Complementary on,
Jas had two car mods loaded, the SF90 (520k triangles) and a G87 (706k). Each car's textures were
uploaded at full size, and these mods ship 4K and 8K maps: the SF90's 123 images alone take 1.8 GB
of video memory. When he removed the G87, `NativeCars.active()` went false (no cars), so Minecraft
asked BeamNG to switch its own world render back on. With the 8 GB card already full, BeamNG hung
for 116 s, logged `texture allocation failed`, then `DXGI_ERROR_DEVICE_REMOVED`. The NVIDIA driver
reset (System log, nvlddmkm event 153, at 16:18:36 and 16:20:32) and BeamNG died. Fixes:
- Car textures are scaled to at most 1024 px on load (`NativeCars.fit`): the SF90 goes from
  1.8 GB to 326 MB. The load log line now gives each car's texture memory.
- With no cars, or with a shader pack on, BeamNG's world render stays off. A car still exporting
  is missing for those seconds instead of shown through BeamNG's picture.

**A car that never loaded (the SF90, 2026-10-03).** Jas's SF90 mod exports to 134 MB (520 693
triangles, 123 images) and held BeamNG still for 17 s doing it. In that time its shape lapsed in
Minecraft and the link reconnected, and each counted as one of the car's two export tries, so
Minecraft gave up while BeamNG finished. Those two cases now give the try back; only BeamNG saying
the export failed, or 150 s with no answer, count. That evening `native export` brought it in from
BeamNG's finished export: loaded and drawn in 5.7 s.

### New worlds, the End, a solid Gallardo (2026-10-03, late)

- **F1 world.** `run-minecraft.ps1 -Mode terrain -World bng-f1` opens "F1 Circuit [BeamNG]": BULLDOZOR's
  5.15 km street circuit (Planet Minecraft, 1.16.5), spawn on the start line (-184, 62, 551). Built
  with a vanilla 1.21.1 server's `--forceUpgrade` on a copy, then a local world-merging script
  (not in this repo; the maps aren't ours to share). The map's texture pack is the world's
  `resources.zip`, so it loads in this world only. BeamNG's 1024-block terrain window means a short
  rebuild stall every ~600 blocks around the lap.
- **Newisle world.** `-World bng-newisle` opens "Newisle City [BeamNG]": City of Newisle v1.3.2
  (Blip, September 2025, 4.5 km^2). It was saved in 1.21.4, so `relabel.py` relabels its newer
  chunks to 1.21.1 (3955) and swaps the blocks 1.21.1 lacks (pale oak -> birch, pale moss -> moss,
  eyeblossom -> daisy, per-wood boats -> `boat` with a Type), then the 1.21.1 server upgrades its
  older chunks and `merge_worlds.py newisle_layout.json` builds the save. Verified, not yet opened.
- **The End.** BeamNG follows the player into the End (`BngWorld.activeLevel`): the terrain is read
  from the End and rebuilt on the way in and out, BeamNG's car is brought to the player, car
  stand-ins, explosions and the picker use the End, and the overworld's trunk cubes leave BeamNG
  while away. Empty columns become a floor at y -60, about 90 m under the islands and above
  smallgrid's floor. The Nether is left out (read from the top, its ground would be the bedrock
  roof). Proven: terrain -60..103 around the main island, a midtruck and an M2 spawned there with
  stand-ins.
- **Solid "translucent" materials.** `translucent` with `translucentBlendOp = "None"` is solid in
  BeamNG (`editor/materialEditor.lua:2349`). The Gallardo mod marks 22 materials so, its paint
  among them, and Minecraft drew them as glass. Proven: the Gallardo is solid orange.
- Open: `mc.ps1 "pick svct5"` without a config asked BeamNG for `base_v6_A` and failed; the picker
  screen itself is fine.

### What a car hits in a city (2026-10-05, night)

Jas, in Newisle: "it's hitting random shit... I guess it only works good on a flat world". An
autopilot (a local test script, not in this repo) drove BeamNG's pickup along the roads through BeamNG's
own inputs, the Minecraft player following as a spectator, and logged every sudden stop, jolt and
stall with BeamNG's terrain around the car next to TerrainSync's own columns (new dev command
`tdump x0 z0 x1 z1`). A static audit (also local) compares BeamNG's terrain with every road
block in a 370 x 360 block part of the city. Commits `cf0e1cd` to `70a6083`, BeamNG side included
(mod reinstalled and reloaded).

What was wrong, worst first:

- **Streets over the subway were roofs.** Since `60ddb1d` (the sign gantry) any run with a built
  block on top and room under it was a roof, however deep. Newisle's streets are concrete on a dozen
  blocks of dirt over the subway, so BeamNG's ground there was the tunnel floor 19 blocks down, and
  the street only came back as a deck when a car got close: 36 % of the audited road blocks. Loose
  soil (dirt, grass, sand, gravel: `TerrainHeights.SOIL`) right under a built block is a street's,
  and more than ROOF_SOIL of it is the ground whatever is under it.
- **BeamNG's physics lagged the terrain.** The physics takes up new heights about 0.7 s after the
  last `tb:updateGrid` anywhere, and every call starts the wait again (beamng-api-notes.md).
  Minecraft sends patches all the time while you drive, so the physics stayed on old ground and the
  autopilot fell through the highway deck while BeamNG already reported the deck's heights. A
  collision rebuild after each terrain flush, at most every 0.25 s (5-10 ms on smallgrid), puts them
  in at once (`collsched.lua`). The state stream stayed at 59.5 Hz with it.
- **Ridges along walls.** The blur used every neighbour and KEEP let a column move a whole block,
  so the road two blocks from a barrier rose by up to 0.7 m. Only neighbours a step away count now:
  one block, two between natural columns on a hillside. Audited ridges went from 1689 road blocks to
  575, the slopes up to one-block kerbs that are meant to be there.
- **Street signs were pillars.** Minecraft calls signs, banners and pressure plates solid
  (`forceSolidOn`) though nothing collides with them, and a sign over the road stood in BeamNG as a
  three-block pillar. Ground and mirrored blocks need a collision shape (`TerrainSync.collides`).
- **Levels by where the car can drive.** A column with several levels (a street under the highway)
  takes the one the car can reach soonest, walking the ground a block up or down at a step
  (`TerrainHeights.reach`), 2 s of travel ahead and 32 blocks at least. The old rule, the level
  nearest the car's height carried along its nose's pitch, put lamp arms and awnings across the
  road whenever the car pitched.
- **Builds over roads stood as walls.** A stone block of the monorail over the highway counted as
  a floating island, a twelve-block wall the autopilot hit at 26 km/h. Natural blocks over a gap are
  only the ground when no built floor with room over it comes before the ground (`floorBelow`), a
  hill of up to TUNNEL_COVER natural blocks over a built lining is a tunnel (the park's road), and
  clay, stone and ore used for walls and arches don't make a street. Covered roads under a wall at
  street level went from 354 blocks to 51, the rest under towers taller than the 128-block scan.

Proven in the running games: after the fixes the audit finds no bumps, holes or lost patches; three
runs onto the elevated highway from a reset, up to 77 km/h, never put the car under BeamNG's ground
(the first such run fell through before); and two random drives of 3 and 3.4 km through the city hit
only real blocks (lamp posts, the yellow median posts, fences, building corners).

Then, the same night, the rest of what was left:

- **Posts are boxes of their own size.** A lamp post, bollard or lone fence post filled a whole
  block of the heightfield, and the slope between samples spread it out another block each way: a
  0.36 m post stood about 1.2 m wide at bumper height. Blocks whose collision shape is narrower than
  0.75 blocks both ways, and logs, are no terrain now. Each chunk read keeps the ones standing on
  something as boxes (`TerrainPosts`), and those within 24 blocks plus a second of a car's travel go
  to BeamNG (`post_chunk`), leaving again when no car is near. Connected fences and railings are long,
  not thin, and stay terrain. Proven at Newisle's lamp post: a car passing 40 cm from it drove on, a
  car aimed at it stopped against it.
- **No cube per log.** Terrain worlds used to mirror every log BlockSync had ever scanned as a cube:
  about 3000 by the end of the night. With the posts BeamNG held about 8000 collision objects and
  each rebuild took 145 ms, which also switched the terrain rebuilds off (over 30 ms). Tree trunks are
  posts now, so only the ones near a car are in BeamNG: rebuilds take 4-5 ms with about 500 objects.
- **Strays.** A Minecraft that sent its blocks while BeamNG was still loading the level got its cubes
  made, then the level-loaded hook reset the list without deleting them: 2996 cubes nobody owned.
  The hook now deletes what the lists own, and a new instance of the extension deletes our
  TSStatics it doesn't keep. The `scene_count` message shows them.
- **Towers and clay.** The column scan reaches 256 blocks, and clay is a building block (Newisle's
  white walls), so a road under a tower is a road. Covered street walls in the audit: 5 left, a lava
  pit and a rail line under a stone building, none of them roads.

On the finished build: a 3.3 km random drive through the city hit only real things (railings,
posts and building corners the autopilot cut too close), two more runs onto the elevated highway
from a reset at up to 77 km/h stayed on the deck, and collision rebuilds took 3.5-5.3 ms.

Left as it is: connected railings (iron bars, fences) stay terrain, so their face is still up to
about half a metre out from the bars. Making them boxes too would catch connected window panes,
and building walls would open at every window. An elevated road uses the ground under it until a
car is near it, which only matters to cars.

### Doors, kerbs, colours and glass (2026-10-03, night)

Jas's list after the audit, worst first. Commits `6be6ef5` to `7382828`.

- **Jitter.** Minecraft ran uncapped at about 260 fps and left BeamNG short of GPU: BeamNG 68-111
  fps, car state 46-53 Hz, so the car stuttered. `run-minecraft.ps1` now caps Minecraft at 120 fps
  (`-MaxFps`, written to `options.txt`) and opens it on the main 240 Hz screen (`-Screen main`, the
  default; `second` is the 60 Hz Acer). Measured after: Minecraft 118-120 fps, BeamNG 120 fps, state
  59-60 Hz.
- **Doors on cars without door triggers.** The M3 and the Cadillac CT5 mods have no door triggers,
  but their doors shut on couplers. `latches.lua` lists the car's `advancedCouplerControl`
  controllers (their `couplerNodes` header table, read as `tableFromHeaderTable` reads it,
  `advancedCouplerControl.lua:429`) and its latch breakgroups, and puts a 0.4 m box on each latch's
  nodes. Use on one runs the controller's `toggleGroup`, or `beamstate.breakBreakGroup` for a
  breakgroup (`beamstate.lua:166`). Couplers with the same label are one target: a bonnet has a
  latch and a safety catch, and toggling one leaves it shut. Proven: the Cadillac's doors open from
  Minecraft. The bonnet's double toggle has a Lua test; the in-game check was hidden by the pause
  menu.
- **Handles only with J.** The yellow boxes were on every car and in the way. On foot J turns them
  on and off (a toast says which); with them off, right-click on a car gets in as before. In the
  seat J is still the controls list. Outlines are at most 0.15 blocks from their middle each way.
- **Kerbs.** In Real blocks a half-block step is now a ramp (`TerrainHeights.KERB`, `sampleKerbs`).
  A sharp 0.36 m edge is about a sports car's wheel radius, and the M3 needed 6 m/s to climb one. A
  full block stays a wall. Not yet driven over in game.
- **Lawns over garages.** Up to 3 blocks of natural blocks over a built ceiling with room under it
  count as a roof (`ROOF_SOIL`), so a garden on a garage roof no longer fills the garage. Deeper
  soil is the ground. Not yet checked in game.
- **Dents you can see.** Each node within 0.3 m of the hit gets a push of its own mass times the
  speed change, over 15 ms (`obj:getNodeMass`, `bdebugImpl.lua:777`): 8 m/s per point of damage,
  fading to the edge, at most 120 m/s. The old fixed force tore light nodes off and barely moved
  heavy ones. Tuned on a pickup.
- **A Lua reload keeps the terrain.** Reloading the extension used to drop BeamNG's terrain, and the
  Cadillac parked on it fell 62 m. The terrain now rides across the reload
  (`_G.mccrossKeptTerrain`). Proven: the Cadillac stayed at z 62.35.

**The Cadillac's colours.** Four separate causes:
- BeamNG's material colours are picked in the material editor as they look
  (`materialEditor.lua:1641-1656`), and BeamNG turns paint to linear light for its shader
  (`toLinearColor`, `shadergen.h.hlsl:173-175`). Minecraft took them as linear, so the black trims
  (0.11-0.45) came out mid grey. `NativeExport.linearColor` now applies the 2.2 power. A v1 material
  keeps its colour in `diffuseColor` (`materialEditor.lua:1283`), which the export now reads. A map
  named `@...` (a plate, a screen) is drawn by BeamNG itself and no longer counts as missing.
- **JPEG textures were dropped.** Minecraft's `NativeImage.read` checks for the PNG header first,
  and the exporter embeds each texture file as it is. 32 of the CT5's 90 images are JPEGs,
  including the tyres (`michelin02.jpg`, metallic 1) and the carbon splitter and skirts, so they
  were drawn from bare factors: white mirrors of the sky. Non-PNG images now go through stb
  (`NativeCars.decode`), and one that still fails is logged.
- **Detail maps without a base map.** BeamNG samples white where a layer has no base map and still
  blends the detail map in (`defaultMat.hlsl:294-307`). The rims are a 0.38 grey made black by an
  anthracite detail map, and came out silver.
- **Glass.** All glass was drawn at a flat 35 %. Now it follows BeamNG's opacity: the bottom layer's
  factor and opacity map (the map used to be dropped), upper layers blended in by their own, colour
  premultiplied and the reflection on top (`defaultMat.hlsl:571-573`). A v1 material takes its
  colour and base map alpha. The CT5's tinted side windows have opacity 1 and are as black as in
  BeamNG.

Proven side by side with BeamNG's own pictures (`temp/mccross/shots/cadillac_bng_side.png`,
`_front.png`): tyres, skirts, splitter and rims black, windows tinted. Still different: the DRLs
aren't lit, the plate is blank, and the body looks bluer under Minecraft's sky.

**Sound.** BeamNG plays the engine itself. Its FMOD output follows the Windows default device on
its own (`beamng.log`: "Successfully switched to new audio output"), which is the device Minecraft
uses too ("System Virtual Line (MSI Sound Tune)" that night). With Jas driving, BeamNG's audio
session on that device peaked at 0.94 (Windows' audio session meter).
BeamNG's options-menu switch can't be called from Lua: `switchOutputDevice` calls
`Engine.Audio.createAudioDevice`, which is nil in 0.39.4 (`core/settings/audio.lua:310`), so a
"follow Minecraft's device" message was written and reverted. Whether Jas hears it: not confirmed.

### The audit: spawning, the link and the town's terrain (2026-10-03, evening)

A full audit while Jas was out (the report stayed local). Seven bugs fixed, each
seen working in the running games (commits `324ef36`, `fddf0d4`, `c29fc9d`):

- **The link holds through a car load.** A big mod's export holds BeamNG's thread 17-29 s and the link
  used to drop after 8 s, so the picker said "BeamNG isn't connected". Before asking for an export
  (90 s) or a spawn (60 s) Minecraft calls `BngLink.expectStall`, and silence inside that window keeps
  the session. Terrain patches, block cubes and ground boxes wait while BeamNG is silent
  (`BngLink.responsive`: heard from within 1 s), since datagrams sent to a frozen BeamNG overflow its
  socket. The status HUD counts held stalls. Proven: the G87's 21.3 s export and the M3's 24.0 s one,
  0 reconnects.
- **One export per model and parts.** `carexport.lua` keys finished exports by model and
  `veh.partConfig` and keeps the list in `temp/mccross/exports.json`. A new car built the same way gets
  that `.glb` and a side file of its own (its id and paint; Minecraft reads paint only from the side
  file). Only an export made below 100 of BeamNG's damage tally is shared. Proven: a second G87 drawn
  4 s after the question with no freeze; the M3 answered from the list after a Lua reload.
- **New cars go to open ground.** `TerrainSync.spawnSpot` takes the spot ahead of the player only if
  nothing is over it (Minecraft's motion-blocking heightmap no higher than the ground), it is flat to
  half a block across 9 x 9 and inside BeamNG's terrain; else the nearest such spot within 64 blocks,
  and the chat says where. Proven: a pickup asked for inside the gas station shop went 12 blocks west
  onto grass.
- **Never under the terrain, never lost.** `spawn.safeTeleport` looks for the ground only 10-50 m down,
  so a car asked for under the terrain stood on smallgrid's floor beneath it (the G87 at z -19874).
  Spawns and `vehicle_place` now start at least 0.4 m over the terrain (`terrain.lua T.spawnZ`), and
  twice a second a car more than 2 m inside the ground, or off the terrain below z -50, is put back on
  top, repaired, and Minecraft hears `vehicle_rescued` (`T.rescue`, never while a patch waits for its
  grid update, and not again for 5 s). Proven: a spawn asked for at z 30 landed at 62.54; the rescue
  fired on a buried pickup (its re-placement after the grid fix not re-run).
- **No pits for tall columns.** A sample set to exactly the terrain's height wraps to 0 in BeamNG's
  16-bit heights, and towers taller than the range were clamped to exactly that, so the city's towers
  were pits at the terrain's bottom. Lua clamps a step under the top; Java builds at least 320 blocks
  of range.
- **Patches are acknowledged.** Each `terrain_cells` has a `pid` and BeamNG answers `terrain_ack`; a chunk
  without an answer after 3 s is sent again. About one patch in 150 was lost (5 of 746 after a city
  teleport), which had left a city street chunk 0.7 m high.
- **Parked cars stay parked.** A terrain built elsewhere replaced the old one and every car left on it
  fell 62 m to smallgrid's floor. Now each car standing on the old terrain outside the new one gets a
  flat stand at its height (the ground's scaled cube, `T.keepCars`), removed when a terrain covers it
  again. Proven: the M3 stayed at z 62.2 when the player went to the city.

**Checked and fine:** terrain heights match to a millimetre on roads, forecourts, house floors under
roofs and city streets, in Real blocks and Smooth; block edits reach BeamNG in about 0.5 s; items
(dents, fire, water, TNT, remover) reach BeamNG; the garage drive-in on the house map; two big mods with
shaders use 3.7 GB.

**Open:** dents too weak to see; the M3 mod's triggers sit at the world origin (no door triggers);
Real blocks makes a half-block kerb a wall to a slow car; Minecraft's 260 fps cap starves BeamNG of GPU
(BeamNG 68-111 fps, state 46-53 Hz; 120 fps / 60 Hz with Minecraft capped at 120); chunks more than
160 blocks from the player keep the stand-in height (TerrainSync's patch radius 10 chunks against
Minecraft's 12).

**Underground garages, design (not built).** One heightfield can't be a lawn and the hall under it.
Around each car, each column's surface would be the top of the highest run at or below the car's
wheels (plus a step), sent as patches while the car moves, with the top surface away from cars. The
patch path, its batching and its acks already exist; the risk is two cars at different levels close
together.

### Frame rate, cars that always load (2026-10-03)

**Two causes, both measured.** Minecraft bent every car's whole mesh on the CPU each frame: 20.2 ms
a frame for the M3 (809k triangles) alone, drawn even when off screen. And the dev client ran on
the laptop's Intel Iris Xe, not the RTX 3070 Ti (the shader logs its GPU now: "Car shader on
Intel(R) Iris(R) Xe Graphics"); Jas's normal launcher Java had Windows' high-performance GPU
setting, the Gradle JDK didn't.

- The vertex shader bends the car now (`CarShader`): each vertex carries its node frame and its
  position and normal in it (`FlexBinding.write`), and per frame only the nodes go up, as a buffer
  texture. `FlexBindingTest` checks the shader's math against `FlexBinding.deform`. Cars outside
  the view (their node box plus 1.5 blocks) aren't drawn. CPU time for the M3: 20.2 ms -> 0.2 ms.
- `HKCU\Software\Microsoft\DirectX\UserGpuPreferences`: `GpuPreference=2;` for the Adoptium JDK's
  `java.exe` and `javaw.exe` (what Settings > Display > Graphics writes). Logged after:
  "NVIDIA GeForce RTX 3070 Ti Laptop GPU".
- With the M3 on screen: 104 fps on the Intel chip with the shader bending, 233-240 fps on the RTX
  (`native stats 600`, the status HUD).

**Cars that wouldn't load.** A car export holds BeamNG still (2.1 s for the Porsche, more than 8 s
for the M3, measured: the link reported "no data for 8002 ms"). With a 2 s link timeout the answer
came in while the link was reconnecting and was thrown away, and Minecraft waited 150 s before
asking again. Now the timeout is 8 s, Minecraft repeats the question every 5 s while it waits, and
BeamNG answers a repeat from the files of the last export of that car while it is unchanged
(`carexport.lua`, `done`). Proven: the M3's answer arrived after a reconnect, and after a Minecraft
restart it was drawn 2 s after the question, with no second export.

### Controller, own mods, real dents (2026-10-03)

**Controller.** Controlify 3.0.1+lts plays the character (walk, look, fight, hotbar, menus). Its
jars go in `minecraft/run/mods`, not Gradle: they use Fabric's `classTweaker v1` format, which Loom
1.11 can't read, while Fabric Loader 0.19.5 remaps that folder at start. Seated in a BeamNG car,
`ControlifyInGameMixin` skips Controlify's `inputTick`/`processPlayerLook` and
`ControlifyMovementMixin` makes `shouldBeControllerInput` false, so its B (sneak) can't throw the
driver out and the car's controls own the pad. Loaded with the controller seen through SDL3; one
optional Controlify mixin (recipe book mouse snapping) fails on a dev mapping clash.

**Own mods.** `scripts/mod_picker.py` lists the 66 car mods of Jas's normal profile (a vehicles
folder with its own info.json counts as a car; patch mods don't), flags duplicates (same vehicle
folder), and copies the picked zips into the test profile; it only deletes copies it made. The
BMW M2 G87 (`g87st`) and the Lamborghini Gallardo were added first; BeamNG mounted them while
running and listed them. The car picker asks BeamNG for a fresh list each time it opens.

**Dents.** A blow pushes the nodes within 0.45 m of the hit inward (4000 N per point of damage
at the centre, 15 ms, `obj:applyForceVectorTime`) and every other node gets an equal and opposite
share, so the panel caves and the car stays put (a sword hit moved the ETK 4 mm). The separate
shove is down to 0.03 m/s per damage, at most 1 m/s. The point force used before barely dented
and pushed the car ~3 m/s (Jas).

### Items, bigger cars, stalls (2026-10-02, late)

**What items do** (`BngVehicleEntity.hurt/interact` -> `vehicle_fx`/`vehicle_remove` -> `carfx.lua`,
BeamNG's own damage calls):

| Item | Effect in BeamNG |
|---|---|
| Car Remover (new, a glinting stick; Tools tab) | the car is deleted (`vehicle:delete()`, as "remove current vehicle") |
| swords, axes, mace, held trident, arrows, thrown trident, punches | a dent where the blow lands, sized by the Minecraft damage (`beamstate.addPlanet`, small and short), and a shove; an edge or point next to a wheel pops that tire (`beamstate.deflateTire`) |
| snowballs, eggs | a small shove |
| flint and steel, fire charge (right-click), fire, lava, lightning | the car catches fire (`fire.igniteVehicle`) and Minecraft flames burn on it |
| water bucket (right-click) | the fire goes out (`fire.extinguishVehicle`) |
| falling anvil | a dent from above |
| TNT and other explosions | push; within 0.75 m per power of the body, blown up |

Proven: BeamNG took ignite, dent, tire and extinguish without errors (ETK 800). Not yet seen:
how big the dents look (the first try showed nothing obvious at 10 m), flames, popped tires.

**Bigger cars.** `HOST_REGION` now maps 1 m of BeamNG to 1.4 blocks (`BngWorld.HOST_SCALE`,
`-Dbngbridge.hostScale`), so a car's roof comes to about Steve's head. Everything goes through
the scale: drawing, hitboxes, the chase camera, the stand-ins' exit spot, block cubes (BeamNG
sizes them by the `cell` in `blocks`) and ground boxes (sent in metres), so what is drawn is
what collides. The bedrock is still BeamNG's floor; the surface is 3 blocks = 2.14 m above it.

**Stalls.** A car swap stalls BeamNG ~2 s and threw the driver out of his seat (the stand-ins
were removed after 1.5 s without data); now stand-ins stay until 90 s without data, and cars
keep their last pose instead of vanishing. A model's first export no longer freezes BeamNG for
20-30 s: its DDS textures are converted a few per frame first (ETK 800: 22 textures in 3.4 s).
Triangles torn to more than twice their rest length are dropped by a geometry shader, as BeamNG
hides a broken flexmesh, so parts that break off don't trail strands.

**Paint like BeamNG.** A layer is painted when it has a palette mask and its `paletteBaseColor` is
on, or when it has no mask and `instanceDiffuse` is on; the mask's alpha is how much paint, its
rgb which of the three paints (BeamNG's `getColorPalette`, `shaders/common/material/shadergen/
shadergen.h.hlsl:163-179`). The Moonhawk's body paints this way and first came out white.

**Less jelly.** Each vertex rides on three nodes of its part. Nodes 1 cm apart were allowed, and
the stream rounded nodes to whole cm, so the bent mesh shook: up to 21 cm on the Moonhawk
(`RealExportJitterTest`, cm rounding). Now frame nodes are at least 10 cm apart with a wide angle
where the part has them, and the stream sends millimetres: worst 4 mm (Moonhawk) and 7 mm
(Scintilla) from rounding. Not measured: how much of what Jas saw was BeamNG's own body flex.

## What it is

You play Minecraft in a superflat world and drive real BeamNG cars through it. BeamNG runs the
car (soft-body physics, damage, engine, gearbox) and draws it; Minecraft draws everything else
and is where you play. Anything spawned in BeamNG (from its menu, AI, traffic) is in the
Minecraft world too.

It is the forward direction with each half flipped:

| Forward (BeamNG hosts) | Reverse (Minecraft hosts) |
|---|---|
| BeamNG draws the world; Minecraft's see-through window draws Steve, blocks and mobs on top | Minecraft draws the world opaque; it cuts car-shaped holes where BeamNG cars are, and the real BeamNG car shows through from the window behind |
| BeamNG ground becomes invisible Minecraft terrain blocks | Minecraft blocks become BeamNG collision cubes (BlockSync, existing); the superflat ground is BeamNG's own floor |
| BeamNG cars are invisible solid proxies in Minecraft (TNT, mobs, punches) | Unchanged |
| Minecraft's camera drives BeamNG's camera | Unchanged |
| F4 hands keyboard and mouse to BeamNG | Minecraft keeps them and drives the car itself (`vehicle_drive`); keyboard and controller |

## Pieces

**World and anchor.** Minecraft creates and opens a superflat creative world, "BeamNG Host"
(bedrock, 2 dirt, grass; grass top at Minecraft y = -60), when started with `-Mode host`.
BeamNG runs `smallgrid`, whose floor is an infinite `GroundPlane` at z = 0 (smallgrid.zip,
`main/MissionGroup/Groundplanes/items.level.json`) with no collision instances of its own. The
anchor is a fixed region: Minecraft (0, -60, 0) = BeamNG (0, 0, 0). `CrossoverCoords.Region`
already takes any origin.

**Collision.** BlockSync mirrors solid blocks as 1 m cubes, as now, but ignores the superflat
ground (y < -60): the GroundPlane is that ground. Rebuilds go through `collsched.lua`. On
smallgrid a rebuild only has our cubes to process. Limit: digging into the ground does not make
a hole BeamNG cars can fall into.

**Car cutouts (the main new part).** BeamNG streams, per car near the camera, its node
positions every frame (`veh:getNodeCount()`, `veh:getNodePosition(i)`, relative to the car
position; call site `career/modules/inventory.lua:705-706`) and, once per car, its skin
triangles (vehicle Lua `v.data.triangles`, `vehicle/bdebugImpl.lua:483`, fetched with
`queueLuaCommand` and `obj:queueGameEngineLua`). Minecraft builds a mesh from them and, after the
world is drawn and before the hand and HUD, writes it into the frame as fully transparent
pixels (0, 0, 0, 0), depth-tested against Minecraft's depth buffer. The overlay window (existing)
lets BeamNG's frame show through those pixels only. Blocks and mobs in front of a car stay in
front; the car covers what's behind it. Fallback if the skin doesn't match the car's outline:
the deformed GPU mesh (`GPUMesh.bng_getGPUMesh`, `util/export.lua:256`).

**Driving.** Right-clicking a car makes it BeamNG's player car (`enter_vehicle`, existing) and
seats Steve in it. While seated, Minecraft sends `vehicle_drive` every frame (at most 60 Hz): keys with BeamNG's
keyboard filter (0), a controller read through GLFW's gamepad API with the gamepad filter (1)
(`lua/common/inputFilters.lua:7-11`, `vehicle/input.lua:614-631`). BeamNG zeroes the inputs if
they stop arriving (`input.event` keeps the last value). Sneak gets out. A chase camera behind
the car; Minecraft's camera still drives BeamNG's.

**Sound.** BeamNG mutes itself when unfocused (`AudioMuteOnWindowLoseFocus`,
`settings/defaults.json:314`); the test folder turns that off so the engine is audible.

## Steps (each proven in the running games before the next)

1. Branch; forward direction still passes its tests and a live check.
2. Probes: skin triangles vs the car's outline (wheels?), node streaming cost, rebuild time for
   a few thousand cubes on smallgrid, `vehicle_drive` with filters and a watchdog.
3. Host world: car on the superflat grass within a few cm; Steve walks beside it.
4. Cutouts: the car visible on grass; half hidden behind a wall; a cow in front of it.
5. Driving: keyboard and controller, 100 m straight, turns both ways, a clean stop. A controller
   test needs Jas if none is available here (TEST REQUEST).
6. Crash into a wall (stops, crumples, visible), TNT, mobs.

## Not in the first version

Driver's-seat view (through the glass you'd see BeamNG's empty grid), car shadows on Minecraft
ground, fire and smoke outside the car's outline, hills and generated worlds (need merged
collision, see beamng-api-notes.md on rebuild cost), holes in the ground.

## Known weak spots

The two games draw on separate clocks (as in overlay mode now), so at speed a car's edge can be
a frame off its hole. Measure first; if needed, draw the hole with the camera pose BeamNG used
(`state.cam`) and shrink it slightly.
