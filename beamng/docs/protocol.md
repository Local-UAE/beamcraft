# Control protocol (v1)

The gameplay link between BeamNG and its clients (the Minecraft mod, `bridge/bngdiag.py`).
Rendered frames do not use it; they go through shared memory (see `architecture.md`).

Implementations: `beamng-mod/lua/ge/extensions/mccross/bridge.lua` and `rays.lua` (BeamNG),
`minecraft/.../link/` (Java), `bridge/bngdiag.py` (Python). Keep the three in step and bump
`VERSION` on any incompatible change.

## Transport

- UDP on 127.0.0.1. BeamNG binds port **47020** (override with BeamNG's `-mccrossport N`, the
  JVM's `-Dbngbridge.port=N`, or `BNGBRIDGE_PORT` for the Python tools). Clients bind an
  ephemeral port. Nothing listens on other interfaces.
- One JSON object per datagram. Datagrams *to* BeamNG must stay under **8000 bytes**: LuaSocket
  reads at most 8192 bytes per UDP receive. Datagrams from BeamNG may be up to 64 KiB.
- UDP fits the data: almost everything is "latest state wins", a lost camera pose or state
  message is replaced 16 ms later, and nothing ever queues up behind a lost packet. The one
  request/response exchange (terrain rays) has a timeout and is simply asked again.
- BeamNG never blocks: its socket has `settimeout(0)` and is drained (at most 256 datagrams)
  in `onUpdate` and again right before the camera is computed.

Why JSON: it is readable in logs and packet captures, and BeamNG's `jsonEncode` plus LuaJIT
make the current volume cheap (BeamNG spends ~0.1 ms per frame in our update, see the perf log
line). A compact binary encoding can be added behind the same envelope (a first byte other
than `{`) when terrain or vehicle traffic grows. The envelope and sequence rules stay the same.

## Envelope

Every message carries:

| Field | Type | Meaning |
|---|---|---|
| `v` | int | protocol version, 1 |
| `t` | string | message type |
| `sid` | int | session id assigned by BeamNG in `welcome` (0 in `hello`) |
| `seq` | int | sender's sequence number, +1 per message in this session |
| `ts` | float | sender's monotonic clock, milliseconds |

Receivers:

- drop messages whose `v` differs (counted as `bad`),
- drop messages whose `sid` isn't the current session (counted as `stale`),
- drop messages whose `seq` is not newer than the last one seen (`stale`; nothing older than
  what was already applied is ever applied),
- count gaps in `seq` as `dropped`.

## Session lifecycle

1. Client → `hello {client, protocol}`, repeated every 500 ms until answered.
2. BeamNG → `welcome {bngVersion, level, protocol, stateHz, port, ready, user}` with a new `sid`. A repeated
   `hello` from the same address gets the same session back. `ready` is true once BeamNG has
   finished loading the level (its `worldReadyState` is 2); `scripts/run-beamng.ps1 -Wait` waits
   for it.
3. Client → `ping` every 250 ms. BeamNG → `pong {echo, bts}`; `echo` is the ping's `ts`, so the
   client measures the round trip without synchronised clocks.
4. Either side considers the other gone after silence: BeamNG drops a client after 3 s, the
   client reconnects after 8 s without data. A dropped client that was driving the camera
   releases it. Before a request that freezes BeamNG (a car export: 17-29 s for big mods; a spawn)
   the Minecraft client expects the stall and keeps the session through up to 90 s (export) or
   60 s (spawn) of silence; messages that must arrive (terrain patches, block cubes, ground boxes, a
   spawn) wait until BeamNG has been heard from within 1 s.
5. A message with an unknown `sid` gets `error {code: "unknown_session"}` (envelope `sid` = the
   offending one), and the client says `hello` again at once. That covers BeamNG restarts and
   extension reloads.
6. `bye` from either side ends the session immediately.

Session ids start from `(os.time() % 100000) * 100`, so a restarted BeamNG doesn't reuse old ids.

## BeamNG → client

### `state` (every frame, at most 60 Hz)

All positions and vectors are canonical, which is BeamNG world space (metres, Z up). Quaternions are
BeamNG's own `LuaQuat` components: convert with `CrossoverCoords.beamngToCanonicalRotation`
(a conjugate, see `coordinates.md`).

```json
{"t":"state", "level":"smallgrid", "paused":false, "fps":60.1, "frame":1571,
 "veh":{"id":18052, "model":"pickup", "pos":[x,y,z], "rot":[x,y,z,w], "fwd":[..], "up":[..],
        "vel":[..], "speed":0.0},
 "cam":{"pos":[..], "fwd":[..], "up":[..], "rot":[x,y,z,w], "fov":65.0, "mode":"orbit",
        "ovr":true, "cseq":1131}}
```

- `veh` is the player's vehicle. `pos` is its reference node, not its box centre. `rot` turns
  (0,1,0) into `fwd` and (0,0,1) into `up` in BeamNG's quaternion convention.
- `cam` is the camera as rendered last frame. `fov` is vertical, degrees. `ovr` is true when that
  frame used a client's pose, and `cseq` is the newest client camera sequence applied.
- `dash` (when a player car exists) is its dashboard, read from the car's own electrics at 20 Hz:
  `{"gear":"N", "rpm":937.0, "maxrpm":8600, "mode":"arcade", "ign":2, "wet":false, "flooded":false}`.
  `wet`: the car's reference node is under water (`obj:inWater`); `flooded`: its engine hydrolocked
  (BeamNG's damage tracker, `engine`/`engineHydrolocked`). `gear` is a string from
  automatic and DCT gearboxes ("P", "R", "N", "D", "S1", "M3") and a number from manual and
  sequential ones (0 neutral, negative reverse). `mode` is the gearbox behaviour ("arcade" or
  "realistic"), `ign` the ignition level (0 off, 1 accessory, 2 on, 3 starting).

### `vehicles` (20 Hz)

```json
{"t":"vehicles", "n":2, "list":[{"id":..., "model":..., "pos":..., "rot":..., "fwd":..., "up":...,
  "vel":..., "speed":..., "center":[..], "axes":[[..],[..],[..]], "active":true, "player":true}]}
```

Vehicles within 400 m of the camera, at most 64. `center` and `axes` are the world-space
oriented bounding box (`axes[i]` are half-axis vectors). Angular velocity is not available in
GE Lua yet (see `beamng-api-notes.md`).

### `rayhits` (answer to `raycols`)

```json
{"t":"rayhits", "req":7, "n":600, "stride":8, "surfaces":4, "miss":-9999, "res":[...], "ms":0.75,
 "rays":2400, "slow":3, "wallMs":17.2}
```

Per column, `stride` numbers: up to `surfaces` surface heights from the top down (a ramp over
the ground, a bridge deck over a road: each is its own surface, at least 1.8 m apart; `miss`
pads the rest), then probe-X hit (0/1), probe-X normal z, probe-Y hit, probe-Y normal z. A probe
only reports a hit if it is blocked at both zProbe and zProbe + 0.8 m (stepped ramps and kerbs
aren't walls). `ms` is BeamNG thread time spent, `wallMs` the time
from request to answer (the work is spread over frames, 2 ms per frame at most). `res` is
absent when `n` is 0.

### `vmesh` and `vtris` (car shapes, only after `vmesh_sub`)

For Minecraft's car cutouts (`minecraft-host.md`). Every frame, for each car within the
subscriber's range of the camera:

```json
{"t":"vmesh", "id":32957, "tv":1, "n":791, "pos":[x,y,z], "fwd":[..], "up":[..], "p":[dx,dy,dz, ...], "q":1000}
```

`pos` is the car position (reference node, canonical); `fwd` and `up` its facing. `p` holds `n`
node offsets from `pos` in whole units of `1/q` metre (`q` = 1000, millimetres; a message without
`q` is in centimetres), world-aligned (no rotation): node i is at `pos + p[3i..3i+2] / q`. The
envelope's `ts` is when BeamNG read the nodes; Minecraft blends between messages by it. A
Scintilla is about 17 KB per message. Centimetres were too coarse for native drawing: a mesh
vertex rides on three nodes, and their rounding shook body panels by up to 21 cm
(`RealExportJitterTest`); in millimetres the worst is 4-7 mm. `tv` names the car's skin; 0 means
it is still being read.

Once per skin and subscriber (again after `vtris_req`):

```json
{"t":"vtris", "id":32957, "tv":1, "n":791, "tris":[a,b,c, ...]}
```

`tris` are node index triples (0-based, into `p`), wheels included: 1124 triangles for the
pickup. A client that sees a `vmesh` whose `tv` it has no `vtris` for asks with `vtris_req`.

### `vlist` and `vconfigs` (answers to `vlist_req` and `vconfigs_req {model}`)

BeamNG's own vehicle list, the one its vehicle selector shows (`core_vehicles.getModelList` /
`getModel`), in pages of 40 so a big modded list stays under the datagram limit:

```json
{"t":"vlist", "page":1, "pages":4, "models":[{"k":"scintilla", "name":"Scintilla", "brand":"Civetta",
  "type":"Car", "configs":16, "def":"gts", "thumb":"C:/.../temp/mccross/thumbs/vehicles_scintilla_default.jpg"}, ...]}
{"t":"vconfigs", "model":"scintilla", "page":1, "pages":1, "configs":[{"k":"gts", "name":"GTs (DCT)",
  "def":true, "thumb":"C:/.../vehicles_scintilla_gts.jpg"}, ...]}
```

`thumb` is the preview picture copied out of the vehicle zip into BeamNG's user folder (absolute
path, absent when the car has none). Models are sorted by brand and name; configurations put the
default first. The first `vlist_req` after BeamNG starts takes about 4 s (BeamNG loads every
model's data and the pictures are copied once); later ones under 1 s.

### `terrain_ack` (answer to every `terrain_cells` with a `pid`)

`{pid, n, err?}`: `n` samples set, `err` why none were (no terrain, out of range). Minecraft sends a
chunk again when a patch's ack hasn't come within 3 s, and only once BeamNG has acknowledged anything.

### `vehicle_rescued`

`{id, model, why, from, pos}`: BeamNG put a car back on top of the terrain (`why` "was inside the
ground" or "fell through the floor", `from` its z before, `pos` where it went). Minecraft logs it and
says it in the chat.

### `trigger_hit` (answer to `trigger_aim`)

`{n, found}`; when `found`: `vid`, `tr` (BeamNG's vehicle and trigger ids; `v` and `t` are the
envelope's), `name`, `actions {action0?, action1?, action2?}` (translated titles), the box in BeamNG
coordinates (`c` centre, `a` three unit axes, `h` half extents in metres) and `dist` (m from the eye).

### Others

`pong {echo, bts}`, `error {code, req?, msg?}`, `bye {reason}`.

## Client → BeamNG

### `camera` (every Minecraft frame, at most 240 Hz)

```json
{"t":"camera", "cseq":1132, "pos":[x,y,z], "fwd":[x,y,z], "up":[x,y,z], "fovV":70.0}
```

Canonical eye position, look and up vectors, vertical FOV in degrees. Vectors rather than a
quaternion, so BeamNG builds its own rotation (`LuaQuat:setFromDir`) and no quaternion
convention crosses the wire. BeamNG's camera filter applies the newest pose each frame. If no
pose arrives for 0.5 s, BeamNG's own camera takes over again.

### `camera_release`

Hands BeamNG's camera back immediately.

### `raycols` (one in flight per client)

```json
{"t":"raycols", "req":7, "bx":-3, "by":-1, "zTop":40.3, "zMid":4.3, "zBot":-39.7, "zProbe":1.3,
 "cols":[0,0, 1,0, 0,-1, ...]}
```

The surface scan runs from `zTop` down to `zBot`; `zMid` is accepted for compatibility and
unused.

Columns are canonical 1×1 m cells `(bx + dx, by + dy)`; rays use the cell centre. At most 1000
columns, and the 8000-byte limit caps a request at roughly 900 anyway (Minecraft sends 600). A
new request from the same client replaces an unfinished one.

### Gameplay

| Message | Effect in BeamNG |
|---|---|
| `explosion {pos:[x,y,z], power}` | every car within `power`·4 m gets a repulsive planet for 0.15 s (`beamstate.addPlanet`); a blast within `power`·0.75 m of a car's body blows it up as BeamNG's own "explode vehicle" does (`fire.explodeVehicle`, `beamstate.breakAllBreakgroups`) |
| `impulse {id, dv:[x,y,z]}` | adds `dv` m/s to the whole car (`applyClusterVelocityScaleAdd`), clamped to 20 m/s |
| `enter_vehicle {id}` | makes that car the player's vehicle (`be:enterVehicle`) |
| `vehicle_spawn {model, config?, mode, pos?, fwd?}` | car selection (`carselect.lua`). `mode` `"replace"` swaps the player's car in place (`core_vehicles.replaceVehicle`, as BeamNG's selector does); `"new"` spawns another car (`spawnNewVehicle`, not entered) and puts it on the ground at `pos` facing `fwd` (`spawn.safeTeleport`). Only a model and config BeamNG lists are accepted; no `config` means the model's default. Answer: `vehicle_spawned {id, model, config, mode}` or `error {code: "spawn_failed", msg}` |
| `ground_chunk {cx, cz, boxes}` | host worlds: one Minecraft chunk's ground (`ground.lua`), canonical boxes `[x0,y0,z0, x1,y1,z1, ...]` (whole metres, max exclusive, at most 16 x 16 x 64 each), smallgrid's collision cube scaled; replaces that chunk's earlier boxes |
| `ground_on {top, lift}` | the ground boxes around the player are in: cars still on BeamNG's floor are lifted by `lift` m onto the surface (`top`), then collision is rebuilt with the boxes |
| `ground_reset` | boxes out, cars on the ground lowered back to the floor (also when the client leaves) |
| `post_chunk {cx, cz, boxes}` | terrain worlds: one chunk's thin posts and tree trunks near a car (`TerrainPosts`), canonical boxes `[x0,y0,z0, x1,y1,z1, ...]` in metres; replaces that chunk's earlier ones, an empty list takes them out. A second `ground.lua` box set; collision rebuilt as for `blocks` |
| `post_reset` | all posts out (a new terrain) |
| `vehicle_remove {id}` | deletes that car (`vehicle:delete()`, as BeamNG's "remove current vehicle") |
| `vehicle_fx {id, kind, pos?, dir?, damage?, tire?}` | an item's effect (`carfx.lua`): `ignite`, `extinguish`, `dent` at `pos` along `dir` sized by `damage` (Minecraft damage, capped at 40) with `tire` true to pop a tire within 0.8 m of the hit, or `tire` alone |
| `vehicle_action {action, down}` | the player car's other controls, as BeamNG's own bindings run them. `down` true is a press, false a release (default true). `shift_up`, `shift_down` (press and release, for sequential gearboxes), `gear_reverse`, `gear_1` through `gear_6` (select the H-pattern gear; release returns to neutral), `gearbox_mode` (arcade/realistic), `starter` (hold to crank), `horn` (hold), `lights`, `recover` (hold to rewind, release to stop), `reset` (back to the spawn point, repaired: `resetGameplay(0)`). Unknown names get `error {code: "bad_request"}` |
| `trigger_aim {o:[x,y,z], d:[x,y,z], reach?, n}` | the player's eye ray (BeamNG coordinates, `reach` m, default 3, 0.5-8): the vehicle trigger it reaches first (door handles, bonnet, boot, cabin switches; `triggers.lua`). Answer `trigger_hit` |
| `trigger_use {vid, tr, action, down}` | fires a trigger's action (0-2) as BeamNG's own click does: `core_vehicleTriggers.triggerEvent('action'..action, down and 1 or 0, tr, vid, vdata)` |
| `blocks {add:[x,y,z,...], remove:[...], clear, cell?}` | collision cubes at block cells (min corners), `cell` metres each (default 1; host worlds 1/1.4); vehicle collision rebuilt at once if a car could reach the changed cells within 1.5 s, otherwise after 2 s without changes, or 8 s after the first change if they never pause (`collsched.lua`); at most 4000 cubes |

### Debug and tests

| Message | Effect |
|---|---|
| `debug {text}` | BeamNG logs `[MCCROSS] debug from <client>: text` and shows a UI toast |
| `camera_test {pattern:"orbit", seconds}` | BeamNG orbits its camera around the vehicle by itself (no client pose needed) |
| `markers {pts:[x,y,z,...], r, ttl}` | BeamNG draws spheres at canonical points (alignment checks) |
| `reload` | BeamNG reloads the extension from disk (after `install-beamng-mod.ps1`) |
| `scene_count` | answer `scene_counts {blk, gnd, other, cubes, posts, ground}`: our TSStatics in the scene by name prefix against what the bridge owns (strays show as `blk` over `cubes`) |
| `vehicle_place {pos, fwd}` | teleports and repairs the player's car, facing `fwd` (`spawn.safeTeleport`) |
| `vehicle_drive {throttle?, brake?, steering?, clutch?, parkingbrake?, filter?, ttl?}` | sets the player car's inputs (clamped numbers; omitted ones are left alone). `filter` is BeamNG's input filter: 0 keyboard (BeamNG ramps the value like a key press), 1 gamepad (default), 2 direct. With `ttl` (seconds) the inputs are released when no newer `vehicle_drive` arrives in time |
| `vmesh_sub {on, range?}` | start (`on` true, `range` in m, default 200) or stop the `vmesh`/`vtris` stream to this client |
| `vtris_req {id}` | send that car's `vtris` again |
| `vexport {id?}` | export that car (the player car without `id`) with BeamNG's own glTF exporter (`util_export`) to the BeamNG user folder: `temp/mccross/<model>_<id>.glb` plus a `.json` side file (the node offsets it was exported in, flexbody node groups, paint, and per material what the exporter leaves out: `baseColorFactor` per layer, `aoUv1`, a `detail` map `{file, layer, strength, scale, uv1}` converted to PNG under `temp/mccross/tex/`, and `paletteBaseColor` per layer). The exporter only does player 0's car, so another car gets the player slot for the length of the call and gives it straight back. Textures are converted a few per frame first; the export itself still holds BeamNG for about 2 s on a big car (8 s or more for the M3). Asked again for a car it exported before and that hasn't changed since (same model, node count and vmesh `tv`), BeamNG answers at once from those files with `cached: true`, so a Minecraft restart reloads every car without another export; a new car built from the same model and part configuration (`veh.partConfig`) as an undamaged earlier export is answered from that `.glb` with a side file of its own (its id and paint) and `cached: true, shared: true` (the list survives restarts in `temp/mccross/exports.json`); asked again for the car being exported, the running export answers when done (Minecraft repeats the question every 5 s, the answer being one datagram). Answer: `vexported {id, model, file, side, ms, bytes, cached?}` (absolute paths) or `error {code: "export_failed"|"busy"}` |
| `terrain_load {file, size, square, x0, y0, z0, height, sea?, id?}` | real-terrain worlds (`terrain.lua`): a `size` x `size` 16-bit heightmap PNG under `/temp/mccross/` (0..65535 = 0..`height` m), samples `square` m apart, its corner (grid 0, 0 = the image's last row, first column) at (`x0`, `y0`, `z0`); built with BeamNG's own terrain generator, replacing any earlier one; cars under the surface are lifted onto it. With `sea` (m), a `WaterPlane` at that height, replacing any earlier one. Answer: `terrain_loaded {size, ms, lifted, id}` or `error {code: "terrain_failed"}`. Minecraft resends the load every 10 s until it is answered; a load whose `id` BeamNG has already built is answered `terrain_loaded {size, id, same: true}` and not built again. Minecraft sends `size` 1024 (one sample per block) for smooth ground and 2048 (2 x 2 per block, `square` 0.357 m) for real blocks; BeamNG takes up to 4096 |
| `terrain_cells {x, y, w, h, z, pid?}` | a patch of the terrain: `w` x `h` samples from grid (`x`, `y`), `z` row by row (grid y up = north), metres above the terrain's `z0`, clamped to just under its height (exactly the height wraps to 0 in BeamNG's 16-bit heights). Minecraft sends at most 512 samples per message (BeamNG reads 8192 bytes a datagram), so a chunk of real blocks is two patches; BeamNG takes up to 4096. With `pid`, BeamNG answers `terrain_ack`. When a new `terrain_load` leaves a car that stood on the old terrain outside the new one, BeamNG puts a flat stand under it |
| `terrain_reset {}` | the terrain goes (also when the client leaves) |
| `terrain_height {points: [[x, y], ...]}` | diagnostic: the terrain's height at up to 64 points. Answer: `terrain_heights {z}` (false where there is none) |
| `shot {name}` | diagnostic: BeamNG's own picture of the scene (`screenshot.doScreenshot` with a path, as `core/vehicle/partmgmt.lua:679`), the game image only, to set beside Minecraft's drawing; needs `render_main {on: true}`. Written a few frames later to `temp/mccross/shots/<name>.png`. Answer: `shot {file}` |
| `material_info {name}` | diagnostic, read only: a live material's `class`, `version`, `activeLayers`, `mapTo` and for layers 0-3 its texture and colour fields. Answer: `material_info {name, found, class, version, activeLayers, mapTo, layers}` |
| `render_main {on}` | turn BeamNG's main world render off or on (`setRenderWorldMain`); it comes back on when the client leaves |
| `bye` | end the session |

## Reserved for later

These are designed but not implemented, so the base protocol doesn't have to change for them:
Steve as a physical body in BeamNG (so cars feel him), and a binary encoding of `state`,
`vehicles` and `rayhits`.
