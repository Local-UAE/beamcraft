# BeamNG API notes

Every BeamNG API the crossover relies on, with where it was found and how sure we are.
BeamNG version: **0.39.4.0, build 20972** (`beamng_version` = `"0.39.4.0.20972"`).
Paths are relative to the BeamNG.drive install folder. All Lua is
loose under `lua\` (`gameengine.zip` holds no `.lua`). BeamNG's Lua is under the bCDDL licence,
so this file cites call sites rather than copying code.

States:

- **TESTED**: exercised in the running game by our code, with the observed result written down.
- **CONFIRMED**: read in the source (definition and real call sites), not yet exercised by us.
- **UNVERIFIED**: plausible from usage, details unknown.
- **REJECTED**: tried or read and found unusable.

## Loading

| API | Source | Use | State |
|---|---|---|---|
| `scripts/<any>/modScript.lua`, run with `dofile` at mod DB init | `lua/ge/extensions/core/modmanager.lua:704-712` (startup), `:1000-1013` (activation) | Our `beamng-mod/scripts/mccross/modScript.lua` | TESTED: log line `core_modmanager.initDB| mountEntry -- /mods/unpacked/mccrossover/` then our extension loads |
| `setExtensionUnloadMode(name, "manual")` | defined `lua/ge/main.lua:414-443`; after modScripts, `loadManualUnloadExtensions()` (`modmanager.lua:723`) loads every manual extension | Loads `mccross_bridge` and keeps it across level loads (`freeroam.lua:37` unloads "auto" extensions) | TESTED |
| Extension naming `a_b` ↔ `lua/ge/extensions/a/b.lua` | `lua/common/extensions.lua:55-72`; module must `return M` (`:526-530`); published as global `_G.a_b` (`:464`) | `mccross_bridge` | TESTED |
| `require()` goes through BeamNG's VFS (mod files visible) | `extensions.lua:526` loads extensions with `require(extPath)` | `require('extensions/mccross/rays')` | TESTED |
| `extensions.reload(name)` | `extensions.lua:922-925` (unload + load from disk) | dev `reload` message, deferred to the end of `onUpdate` | TESTED: about a dozen live reloads, clients reconnect with a new session |
| Hooks `onExtensionLoaded`, `onExtensionUnloaded`, `onUpdate(dtReal, dtSim, dtRaw)`, `onClientPostStartMission(levelPath)` | `extensions.lua:606-611, 240-241`; `main.lua:918, 935, 774` | bridge.lua | TESTED (`onUpdate` runs at the frame rate; level-loaded line logged) |
| `log(level, tag, msg)` | `main.lua:30-32` → C++ `Lua:log` | Lines appear as `GELua.mccross_bridge.mccross| [MCCROSS] ...` | TESTED. beamng.log is buffered: lines can take minutes to reach the file |
| `beamng_version` (C++ global) | `core/versionUpdate.lua:23` | WELCOME message, startup log | TESTED: `0.39.4.0.20972` |
| `Engine.getStartingArgs()` + `tableFindKey` | `mcp/server.lua:15-17` | `-mccrossport N` | CONFIRMED |
| `ui_message(msg, ttl, category, icon)` | `lua/common/utils.lua:919`; call `core/camera.lua:1695` | `debug` message toast | CONFIRMED |

## Command line and user folder

| Item | Source | State |
|---|---|---|
| `-level <name>` → `core_loadMapCmd`, waits for mods | `lua/ge/client/parseArgs.lua:101-107`, `main.lua:1495-1508` | TESTED: `-level smallgrid` loads in ~33 s |
| `-vehicle <model>` | `parseArgs.lua:66-72` | TESTED: `-vehicle pickup` spawns the Gavril D-Series |
| `-userpath X` (C++) | string in `Bin64/BeamNG.drive.x64.exe` | TESTED: the real user folder becomes `X\current` (log: `user path: ...\current`) |
| `-windowed` | `parseArgs.lua:57-58` calls global `setFullScreen`, which doesn't exist in 0.39.4 | **REJECTED**: fatal Lua error, then a C++ crash at startup. Window mode goes in settings instead |
| `-gfx <api>` (C++), values `d3d11`/`dx11`/`d3d12` | strings in the exe: `Skipping automatic graphics API selection because the effective '-gfx' value is '{}'`, `dx11` | UNVERIFIED (needed for the D3D11 compositor stage) |
| Default renderer | log `d3d12 render initialized` | TESTED: DX12 on this PC |
| Settings JSON must have no BOM | `jsonDecode` error `JSON error (line 1, col 1)` on a BOM file | TESTED (PowerShell's `-Encoding UTF8` writes a BOM) |
| First-run privacy wizard: `onlineFeatures`, `telemetry` (cloud settings, default `"ask"`) | `settings/defaults.json:33-34`, values `enable`/`disable` (`core/settings/settings.lua:26`) | TESTED: seeding `disable` in `settings/cloud/settings.json` skips the wizard |
| Background FPS limit | `settings/defaults.json:163-165`: `fpsLimit` 60, `fpsLimitBackgroundEnabled` true, `fpsLimitBackground` 30; applied in `core/metrics.lua:196` | TESTED: unfocused BeamNG ran at exactly 30.0 fps |
| `settings.getValue/setValue` | `core/settings/settings.lua:316-350`; `setValue` saves to disk; `tech/techCore.lua:852` turns the background limit off the same way | TESTED: after switching it off while a client is connected, 60.3 Hz state |

## Networking

| API | Source | State |
|---|---|---|
| LuaSocket core built in (`package.cpath = ''`), helper `require('socket.socket')` | `main.lua:11-12`; helper `lua/common/libs/luasocket/socket/socket.lua`; used `lua/common/tcpServer.lua:73` | TESTED |
| `socket.udp()`, `:setsockname('127.0.0.1', port)`, `:settimeout(0)`, `:receivefrom(n)`, `:sendto(data, ip, port)` | template `core/schemeCommandServer.lua:12-26`, `core/remoteController.lua:56-121` | TESTED: 60.3 Hz, 0.66 ms RTT, 0 drops |
| UDP receive limit 8192 bytes | LuaSocket `UDP_DATAGRAMSIZE` | UNVERIFIED for BeamNG's build; protocol keeps client→BeamNG datagrams ≤ 8000 bytes |
| `jsonEncode(v)` | `lua/common/utils.lua:347`; numbers via string.buffer (`%.14g`), NaN/inf → `±9e999` | TESTED |
| `json.decode` (global `json = require("json")`) | `main.lua:51` | TESTED (wrapped in `pcall`; `jsonDecode` would log an error per bad datagram) |
| `os.clockhp()` | `extensions.lua:525` | TESTED |

## Math and orientation conventions

| API | Source | State |
|---|---|---|
| `vec3`, `quat(x,y,z,w)`, `quatFromDir(dir, up)`, `quatFromAxisAngle`, `LuaQuat:setFromDir`, `q * vec3` | `lua/common/mathlib.lua:885-899, 1150-1170` | TESTED |
| `quat()` with no arguments is (1,0,0,0), not identity | `mathlib.lua:892-893`; warned at `cameraModes/phoneHeadTrack.lua:19-20` | CONFIRMED (we always pass `quat(0,0,0,1)`) |
| World axes: right-handed, Z up | smallgrid's painted "+X" arrow is screen-left when looking −Y (screenshot) | TESTED |
| Cameras look along +Y, up +Z, right +X | `core/camera.lua:1084-1086`; `LuaQuat:toDirUp` = `q*(0,1,0), q*(0,0,1)` | TESTED: `quatFromDir(east, up)` → fwd (1,0,0), up (0,0,1), right (0,−1,0) |
| **Quaternion multiplication is the inverse of Hamilton's** (Torque3D) | `mathlib.lua:887` comment "T3d's quats use -w" | TESTED: `quatFromAxisAngle(Z,+90°)*(1,0,0)` = (0,−1,0), `quatFromDir(east,up)` = (0,0,0.7071,0.7071) where Hamilton gives (0,0,−0.7071,0.7071). `CrossoverCoords.beamngToCanonicalRotation` conjugates |

## Camera

| API | Source | State |
|---|---|---|
| Camera pipeline: active camera, then "running cameras" sorted by `runningOrder`, then `gameengine.lua` (1.0) sends `data.res` to C++ `setCameraPosRotFovNearClipC` | `core/cameraUpdate.lua:191-236`; `cameraModes/gameengine.lua:69-74` | TESTED |
| Camera modes are discovered from `/lua/ge/extensions/core/cameraModes/*.lua` (cached at first use) | `core/camera.lua:136-147`, running filters `:165-176` | TESTED: our `mccrossCamera.lua` (isGlobal, isFilter, hidden, runningOrder 0.95) loads from the mod |
| Filter edits `data.res.pos`, `data.res.rot`, `data.res.fov`; skip when `data.renderView ~= 'main'` | template `cameraModes/phoneHeadTrack.lua:29-89`; `renderView` default `"main"` (`camera.lua:51-56`) | TESTED: an external client circled the camera around the pickup; every state frame reported the override |
| `data.res.fov` is the **vertical** FOV in degrees | `gameengine.lua:31-35` sizes the ortho box with `halfH = tan(fov/2)·d`, `halfW = halfH·aspect` so switching projection "doesn't jump the scale" | CONFIRMED from source. A pixel-level check is part of the overlay stage |
| Getters `core_camera.getPositionXYZ/getForwardXYZ/getUp/getQuatXYZW/getFovDeg/getActiveCamName` | `core/camera.lua:1620-1680, 797` | TESTED (orbit camera at (0.53, 6.15, 2.43), FOV 65) |
| `commands.setFreeCamera`, `core_camera.setPosRot` | `server/commands.lua:38-45`, `camera.lua:1573-1579` | CONFIRMED, not used (the filter needs no mode switch) |
| `onCameraPreRender(camData)` hook | `cameraUpdate.lua:198`, only with a global camera active | CONFIRMED, not used |

## Raycasts

| API | Source | State |
|---|---|---|
| `castRayStatic(origin, dir, maxDist)` (C++) → distance, `>= maxDist` = miss | `cameraModes/crash.lua:29`, `cameraModes/external.lua:145-151` | TESTED: 196 rays in 0.11 ms; flat ground read as z = 0.000 on smallgrid; **does not hit vehicles** (columns under the pickup read the floor, probes through its body miss); a ray starting inside gridmap_v2's slab ramp passed through it to the ground below (so ramp cells read two surfaces); stopping a scan on a near-zero hit guards against meshes that behave otherwise (UNVERIFIED in-game) |
| `Engine.castRay(a, b, includeTerrain, renderGeometry)` → `{pt, norm}` or nil; "SLOW" | wrapper `lua/ge/ge_utils.lua:1329-1340`; call `editor/rayCastTest.lua:72` | TESTED on gridmap_v2: walls read normal z ≈ -0.01, the slab ramp ≈ 0.9; cubes read 0 |
| `be:getSurfaceHeightBelow(pos)` | `core/multiSpawn.lua:362-364` | CONFIRMED, not used |
| `core_terrain.getTerrainHeight(pos)` | `core/terrain.lua:21-26` | CONFIRMED, not used (terrain only, misses roads/buildings) |

## Vehicles

| API | Source | State |
|---|---|---|
| `getPlayerVehicle(0)`, `be:getPlayerVehicleID(0)` | `ge_utils.lua:441-448`, `core/camera.lua:800` | TESTED (pickup id 18052) |
| `activeVehiclesIterator()` → `(id, veh)` | `ge_utils.lua:554-575` | TESTED |
| `veh:getID()`, `getJBeamFilename()`, `getActive()` | `main.lua:1092`, `mcp/tools/vehicle.lua:21`, `ge_utils.lua:558` | TESTED (`pickup`) |
| `getPositionXYZ`, `getVelocityXYZ`, `getDirectionVectorXYZ`, `getDirectionVectorUpXYZ` | `lua/ge/map.lua:3827-3830` | TESTED: spawned pickup faces (0,−1,0); position is the reference node, not the box centre |
| `be:getObjectOOBBCenterXYZ(id)`, `be:getObjectOOBBHalfAxisXYZ(id, i)` | `core/camera.lua:765-768` | TESTED: pickup half-axes (1.15,0,0), (0,2.48,0), (0,0,0.86), centre (0.55, 1.05, 0.86) |
| Angular velocity in GE | only a commented line, `cameraModes/driver.lua:268` | NOT FOUND in GE; vehicle Lua has `obj:getRollPitchYawAngularVelocity()` (`lua/vehicle/protocols.lua:58`). Plan: differentiate rotation in GE |
| `veh:applyClusterVelocityScaleAdd(refNode, scale, vx, vy, vz)` | `core/funstuff.lua:26, 45`; `spawn.lua:534` | CONFIRMED (for later impulses) |
| `veh:queueLuaCommand(str)` | `core/funstuff.lua:74` | CONFIRMED (for later) |
| `veh:getNodeCount()`, `veh:getNodePositionXYZ(i)` (GE side, `i` = node cid 0..n-1) | `career/modules/inventory.lua:705-706` (`getNodePosition`), `cameraModes/driver.lua:226-227` (`XYZ` form) | TESTED 2026-10-02: pickup 791 nodes; reading all of them takes 0.48 ms, JSON-encoding them (mm) 0.37 ms / 15 KB. The offset is **world-aligned**, relative to `veh:getPositionXYZ()`: turning the car from +Y to +X turned node 0's offset (-1.12, 0.631) into (0.631, 1.12). World position = car position + offset |
| Vehicle Lua `v.data.triangles` (0-based, `tableSizeC`; fields `id1..id3` node cids, `triangleType`, `pressure`) | `vehicle/bdebugImpl.lua:483, 2842`; wheels add tread and sidewall triangles, `common/jbeam/sections/wheels.lua:1767-1806` | TESTED 2026-10-02: pickup 1124 triangles (565 type 0, 559 type 2 = non-collidable, mostly tyres; 512 pressure), node ids 20..790 |
| `extensions.load('util_export')`; `util_export.exportFile(path)` with `gltfBinaryFormat`, `embedBuffers`, `exportNormals`, `exportTexCoords` = true (BeamNG's in-game 3D export app) | `lua/ge/extensions/util/export.lua:1140-1247`; mesh from `GPUMesh.bng_getGPUMesh(vid)` (:256), textures DDS -> PNG via `convertDDSToPNG` (:690-700), node positions written as `{x, z, -y}` (:455-458) | TESTED 2026-10-02: Scintilla -> 77.7 MB .glb in 29 s the first time (texture conversion), 0.4 s after (cached in the user folder's `/temp`). 153 170 vertices, 168 217 triangles, 223 flexmesh primitives sharing one vertex buffer, 23 materials, 68 PNG images, 7 props with their own buffers. Vertices are relative to the car position in glTF axes; their bounds match the node offsets converted the same way to the cm |
| Flexbody node groups: `core_vehicle_manager.getPlayerVehicleData().vdata.flexbodies[i]` -> `.mesh`, `._group_nodes` (node cids) | `common/jbeam/sections/meshs.lua:247-266` (`f:addNodeBinding`) | TESTED 2026-10-02: 186 groups for the Scintilla; with them every vertex binds to its own part's nodes |
| Paint: `veh.color`, `veh.colorPalette0/1` (x, y, z, w) | `core/vehicle/colors.lua:80-82` | TESTED 2026-10-02 (Scintilla: 0.14, 0.35, 0.47) |
| `FS:getUserPath()`, `FS:stat(p).filesize`, `FS:removeFile(p)`, `jsonWriteFile(p, t, pretty)` | `core/modmanager.lua:1426`, `core/audio.lua:77`, `career/modules/inventory.lua:240`, `util/export.lua:1152` | TESTED 2026-10-02 |
| `setRenderWorldMain(bool)` (C++ global) | `editor/renderTest.lua:39`, `editor/sceneView.lua:168` | TESTED 2026-10-02: off -> NVIDIA GPU load 83% -> 18%, BeamNG still 120 fps, physics unaffected (the Scintilla reached 23.3 m/s in 2.5 s) |
| Vehicle Lua → GE: `obj:queueGameEngineLua(str)` | `vehicle/beamstate.lua:294`, `vehicle/extensions/inputTests.lua:17` | TESTED 2026-10-02 (triangle summary came back to our extension) |
| Dashboard from vehicle Lua `electrics.values`: `gear`, `rpm`, `maxrpm`, `gearboxMode`, `ignitionLevel` | `vehicleController.lua:549-557, 569`; `gear` is `getGearName()`: a string from DCT/automatic (`shiftLogic/dctGearbox.lua:100-111`), the gear index number from manual/sequential (`shiftLogic/manualGearbox.lua:84-85`); ignition 0 off, 1 accessory, 2 on (`electrics.lua:72-76`), 3 while the starter is held (comment at `:704`) | TESTED 2026-10-02: Scintilla gear "D", "N", "S1"; maxrpm 8600; `gearboxMode` "arcade" / "realistic" |
| Driver controls through `veh:queueLuaCommand`, with the strings BeamNG's own bindings run: `controller.mainController.shiftUpOnDown()` (else `shiftUp()`) and `shiftUpOnUp()`, the same for down, `cycleGearboxModes()`, `electrics.toggleIgnitionLevelOnDown/OnUp()`, `electrics.horn(bool)`, `electrics.toggle_lights()`, `recovery.startRecovering()` / `stopRecovering(0)` | `core/input/actions/vehicle.json:14-15, 26, 28, 44, 52`; `gameplay.json:6` (recover also fires `extensions.hook('trackVehReset')`) | TESTED 2026-10-02 from real Minecraft keypresses: M arcade -> realistic, Z N -> R -> P, X P -> R -> N, M back; probe also reached D and S1; recover held 1.2 s without error |
| Vehicle list: `core_vehicles.getModelList(true).models` (each `key`, `Name`, `Brand`, `Type`, `default_pc`, `preview`), `core_vehicles.getModel(key).configs` (each `key`, `Configuration`, `preview`, `is_default_config`) | `core/vehicles.lua:758-1045` (preview from `_imageExistsDefault`, `:599-606`: `/ui/images/appDefault.png` when none) | TESTED 2026-10-02: 122 models (28 Car, 10 Truck, 67 Prop, 13 Trailer, ...), 121 with previews (500 x 281 JPEG); first call 3.7 s on a fresh BeamNG, 0.8 s after |
| Previews out of the vehicle zips: `io.open(path, 'rb')` / `io.open(out, 'wb')` on BeamNG's virtual file system (`readFile` does the same in text mode, `common/utils.lua:892`) | | TESTED 2026-10-02: 137 previews copied to `temp/mccross/thumbs`, valid JPEGs (1.8 MB) |
| Spawn: `core_vehicles.spawnNewVehicle(model, {config = key, autoEnterVehicle = false})`, then `spawn.safeTeleport(veh, pos, rot, nil, nil, nil, nil, true)` the same frame, as career mode moves a new car (`career/modules/vehicleShopping.lua:517-518` -> `gameplay/sites/parkingSpot.lua:399`) | `core/vehicles.lua:1825-1851`; options `config` (key or .pc path, `prepareConfigData` `:1594-1620`) and `autoEnterVehicle` (`spawn.lua:794-815`) | TESTED 2026-10-02: pickup id 19437 on the ground at (6, 0, 0.3), player car unchanged; Moonhawk from the picker, on the grass 8 m ahead of the Minecraft player |
| Replace: `core_vehicles.replaceVehicle(model, {config = key})` (reuses the player car) | `core/vehicles.lua:1866-1900`, BeamNG's selector and `core/funstuff.lua:183` | CONFIRMED (not yet exercised from Minecraft) |
| `util_export` on a car that isn't the player's: `be:enterVehicle(0, veh)`, `util_export.exportFile`, `be:enterVehicle(0, prev)` in one call (the exporter asks for player 0's car at `util/export.lua:256, 651, 791, 958`; `exportFile` runs to the end without yielding, `:1140-1247`) | `core/vehicle/manager.lua:236` (`getVehicleData(id)` for the flexbody groups) | TESTED 2026-10-02: pickup exported by id (88 MB, 22.7 s first time) while every STATE message kept the Scintilla as the player car |
| Palette paint in BeamNG's shader: mask alpha = paint amount, mask rgb (normalised) = which of the three paints, rest white; applied when the layer's `paletteBaseColor` is on; without a mask, `instanceDiffuse` paints with paint 1 | `shaders/common/material/shadergen/shadergen.h.hlsl:163-179`, `defaultMat.hlsl:242-266`; field `paletteBaseColor` read with `mat:getField` (`editor/materialEditor.lua:1288, 2633`) | TESTED 2026-10-02: Moonhawk body (palette `nullcolormaskR`, no `instanceDiffuse`, `paletteBaseColor` on in all four layers) drew white before, red after |
| Moving smallgrid's floor: `obj:setPosition` on its GroundPlanes (`scenetree.findClassObjects('GroundPlane')`, `tech/impactgen/crashOutput.lua:571`), or deleting all four | `levels/smallgrid/main/MissionGroup/.../items.level.json` (4 GroundPlanes at z = 0, 3 hidden) | REJECTED 2026-10-02: after either, `be:reloadCollision()`, `castRayStatic` still hits z = 0 everywhere (also 3 km out) and a car over a hole in our boxes stays at z 0.23. The floor isn't in the static collision (152 verts / 228 tris = our 19 cubes) |
| Scaled collision boxes: TSStatic `gm_cube_1m.dae` + `obj:setScale(vec3(sx, sy, sz))` (as `editor/api/object.lua:786` scales) | | TESTED 2026-10-02: a 16 x 16 x 1 box read 1.0 m by rays across its whole extent; 279 such boxes rebuild in under 1 ms on smallgrid |
| Straight lift: `veh:setPosRot(x, y, z + dz, r.x, r.y, r.z, r.w)` with `r = quat(0,0,1,0) * quatFromDir(dir, up)`, the half turn `spawn.safeTeleport` applies (`spawn.lua`, `safeTeleport`); `safeTeleport` itself searches for ground and would put the car back on the floor | | TESTED 2026-10-02: Scintilla 0.13 -> 3.13 and Moonhawk 0.23 -> 3.23 onto the boxes, facing kept, and back down on leaving |
| Blow a car up: `fire.explodeVehicle()` then `beamstate.breakAllBreakgroups()` in vehicle Lua, as BeamNG's "explode vehicle" | `core/funstuff.lua:113-140` | TESTED 2026-10-02: Scintilla broken apart, thrown 13 m by a power-4 blast 2.5 m away |
| Vehicle fire: `fire.igniteVehicle()`, `fire.extinguishVehicle()` (vehicle Lua) | `lua/vehicle/fire.lua:419, 453, 471-475` | TESTED 2026-10-02: accepted on the ETK 800 without errors; flames not yet seen (BeamNG's render is off in native mode) |
| Pop a tire: `beamstate.deflateTire(wheelId)` for `v.data.wheels[id]`; hub world position `obj:getNodePosition(w.node1) + obj:getPosition()` | `lua/vehicle/beamstate.lua:517-540, 1543`; `vehicle/extensions/aeroDebug.lua:56` | CONFIRMED (sent without errors; not yet seen) |
| Delete a car: `veh:delete()` | `core/vehicles.lua:1853-1863` (`removeCurrent`) | CONFIRMED |
| Reset like BeamNG's R key: `extensions.hook('trackVehReset')` then `resetGameplay(0)` | `core/input/actions/gameplay.json:4` (`reset_physics`) | TESTED 2026-10-02: Scintilla S1 -> N, back at the spawn point (0, 0, 0.13); the Minecraft driver stays seated |
| Material fields the exporter leaves out, read per layer with `mat:getField(name, layer)` on `scenetree.findObject(name)` for each `veh:getMaterialNames()`: `baseColorFactor`, `detailMap`, `detailScale`, `detailBaseColorMapStrength`, `detailMapUseUV`, `ambientOcclusionMapUseUV` | getter `editor/materialEditor.lua:1272-1275`, field names `:1295, 1305, 1310, 1314, 1346-1347`; `getMaterialNames` `:499` | TESTED 2026-10-02: Scintilla `scintilla_main_carbon` has detail `carbonfiber_d` on UV1, scale 80 x 40, strength 0.89, AO on UV1. Colours come as "r g b a" or as a name ("White"); `getClassName()` returns lowercase `material` |
| `convertDDSToPNG(src, dst)`, `readFile(p)`, `writeFile(p, data)` | `util/export.lua:686`; `common/utils.lua:892, 906` | TESTED 2026-10-02 (carbon detail map converted into `temp/mccross/tex/`) |

## Interactions

| API | Source | State |
|---|---|---|
| Vehicle Lua `beamstate.addPlanet(center, radius, mass, dt)`: a timed point force on every node, removed after `dt` | `lua/vehicle/beamstate.lua:621-650` (timers in `updateGFX`, `:664-679`); BeamNG's own blast uses `obj:setPlanets` in `core/funstuff.lua:111-144` | TESTED: TNT 2 m from the pickup with mass -7.2e12, radius 3, 0.15 s shoved it 2.6 m at 5 m/s (now doubled) |
| `veh:applyClusterVelocityScaleAdd(veh:getRefNodeId(), 1, vx, vy, vz)` | `core/funstuff.lua:45`, crash tester `editor/vehicleEditor/liveEditor/veCrashTester.lua:40` | TESTED: `push` and melee hits (`/damage 8` → 3.0 m/s) |
| `veh:queueLuaCommand("input.event('parkingbrake', 0, 1)")` | `veCrashTester.lua:39` | TESTED (car rolls after a push) |
| Vehicle Lua `fire.explodeVehicle()` | `lua/vehicle/fire.lua:427`, used by `core/funstuff.lua:133` | CONFIRMED, used for blasts inside a car |
| `be:enterVehicle(0, veh)` | `career/modules/inventory.lua:502` | TESTED (control handed to BeamNG, camera back to the car) |
| `createObject('TSStatic')`, `setField('shapeName', 0, '/levels/smallgrid/art/shapes/misc/gm_cube_1m.dae')`, `setPosition`, `registerObject` | `lua/ge/main.lua:1602-1607`; `.dae` + `.cdae` collision mesh in `content/levels/smallgrid.zip` | TESTED: the mesh origin is its **bottom** centre; two stacked cubes read exactly +2.0 m, neighbours untouched |
| `be:reloadCollision()` after adding statics | `core/dynamicProps.lua:95`, `editor/assemblySpline/import.lua:562` | TESTED: a car at 7.7 m/s stops at a wall of cubes; `castRayStatic` sees new cubes after it |
| Cost of `be:reloadCollision()`: it rebuilds the **whole level's** static collision on the game thread, however little changed | log line `Physics collision reloaded in Xs (N instances ...)` | TESTED 2026-10-02 on gridmap_v2 (~20 900 instances): one cube added = 727 ms stall, one removed = 791 ms (gaps between `state` messages); the log shows 0.42-0.66 s per rebuild. smallgrid has no collision instances of its own, so there only our cubes count. `bridge.lua` schedules rebuilds with `collsched.lua` because of this |
| `be:reloadStaticCollision()` | `be` method list in `Bin64/BeamNG.drive.x64.exe` (next to `reloadVehicle`, `enterVehicle`); no Lua caller | TESTED 2026-10-02: works (a car stops at cubes added before it) but costs the same 533 ms on gridmap_v2. No gain |
| `be:reloadCollision(false, true)` / `(false, false)` | `core/dynamicProps.lua:95`, `:293` (argument meaning undocumented) | TESTED 2026-10-02: both work and cost 540 / 528 ms on gridmap_v2. No gain |
| New TSStatic cube with **no** rebuild | — | TESTED 2026-10-02: no collision at all, a car drives through at 11.7 m/s. No per-object collision call is exposed to Lua (`TSStatic::updatePhysicsCollision` exists only inside the exe) |
| `scenetree.findObjectById(id)`, `obj:delete()` | standard SimObject calls | TESTED (cube removal restores the ground reading) |
| BeamNG default keys | `settings/inputmaps/keyboard.json`: F7 `dropPlayerAtCameraNoReset`, F8 `dropCameraAtPlayer`; F3, F4, F6, F9-F12 free | CONFIRMED (why the switch key is F4) |
| `spawn.safeTeleport(veh, pos, rot, nil, nil, nil, nil, true)` (8th argument: reset/repair) | `lua/ge/spawn.lua:650`, call `career/modules/inventory.lua:558` | TESTED: with `rot = quatFromDir(fwd, up)` the car faces `fwd` in all four tested directions (safeTeleport turns the rotation 180° itself, `spawn.lua:655`) |
| Vehicle Lua `input.event("throttle", x, 1)`, also `brake`, `steering`, `parkingbrake` | `ge/extensions/tech/impactgen/crashOutput.lua:342`, `vehicle/input.lua:709` | TESTED: throttle 0.5 for 2.5 s → 8.6 m/s, brake 1 → 0.15 m/s. Holding brake at rest selects reverse (arcade gearbox) |
| Spawn velocity spike: a freshly spawned/teleported vehicle reports ~52 000 m/s for one message | observed on gridmap_v2 | TESTED (Minecraft ignores speeds > 150 m/s) |

## Spawning, exports and the terrain, audit (2026-10-03, evening)

| API | Source | State |
|---|---|---|
| `veh.partConfig` (a .pc path, or a serialized table once parts were changed) | read at `core/vehicles.lua:933` (`getVehicleDetails`) and `:1949` (cloning a car); set by `spawn.lua:581` | TESTED: the M3 reads `vehicles/sdd_g80/G80 M3 Competition X-Drive LCI (A).pc`; used with the model as the shared-export key |
| `map.objects[id].damage` (GE) | written by vehicle Lua `vehicle/mapmgr.lua:125` (`beamstate.damage`), read at `gameplay/traffic/vehicle.lua:707` (traffic counts 500 as big damage, `:712`) | TESTED: under 100 for freshly spawned cars (their exports were shared) |
| `jsonReadFile(path)` | `common/utils.lua:476` | TESTED (side files and `exports.json`) |
| `spawn.safeTeleport` ground search | rays from the box down `clamp(10 x height, 10, 50)` m (`spawn.lua:260-296`); nothing hit: the car keeps its spawn box (`:497-506`) | CONFIRMED; a spot under the terrain missed it and stood on smallgrid's floor. Spawns now start over the terrain |
| `veh:setActive(0/1)` | `core/vehicleActivePooling.lua:252`, `career/modules/playerDriving.lua:100` | CONFIRMED, not used: it would also hide the player's own car; parked cars get a stand instead |
| TerrainBlock `setHeight` at exactly the terrain's height | | TESTED: wraps to 0 (159.286 m on a 39.286 + 120 m terrain read back 39.286); 0.001 under the top reads 159.272 |
| `veh:getTrigger(t):getCenter()` | world space as BeamNG's hover code uses it (`core/vehicleTriggers.lua:757-760`) | OBSERVED: the M3 mod's 33 triggers are 1 cm buttons with centres at (0.005, 0.005, 0.005), not on the car; 420 eye rays found none |
| Loopback UDP to BeamNG under load | Windows gives a new UDP socket a 64 KB buffer | TESTED: bursts of 12 x 4 KB in steady state all arrived; while BeamNG was busy (cars falling after a teleport) 5 of 746 terrain patches never arrived, hence `terrain_ack` |

## Vehicle triggers (door handles) (2026-10-03)

| API | Source | State |
|---|---|---|
| `core_vehicleTriggers.triggerEvent(actionStr, value, triggerId, vehicleId, vdata)` | def `ge/extensions/core/vehicleTriggers.lua:993-1011`; BeamNG's own click `onActionEvent` `:1022-1044` (value 1 press, 0 release) | CONFIRMED, not yet exercised |
| `be:triggerRaycastClosest(dist, useCursor)` | `vehicleTriggers.lua:739, 1029` | CONFIRMED, not usable here: it casts from BeamNG's camera, not Minecraft's eye |
| `veh:getTrigger(id):getCenter()` | `vehicleTriggers.lua:757-760`, `core/vehicle/vehicleTriggerHighlight.lua:240-242` | CONFIRMED |
| `core_vehicle_triggerLabelPlacement.computeTriggerBasisAndHalfExtents(veh, triggerData)` | `core/vehicle/triggerLabelPlacement.lua:352-356`; basis `:130-189` ("Match C++ trigger basis"), half extents `:191-200` (a box's `size` is its full extent, a sphere's its radius) | CONFIRMED |
| `extensions.core_vehicle_manager.getVehicleData(vid).vdata`: `.triggers[id]`, `.triggerEventLinksDict[id]['action0'..'action2'][1].inputAction`, `.inputActions[name].title` | read the same way in `vehicleTriggers.lua:811-834` (updateHoveredTriggerActions); links built in `common/jbeam/sections/events.lua:198-275` | CONFIRMED |
| `_tr(key, fallback)` | `ge/main.lua:977` (the reserved global core_locales sets) | CONFIRMED |

## Level and simulation

| API | Source | State |
|---|---|---|
| `getCurrentLevelIdentifier()` | `lua/common/utils.lua:1086-1091` | TESTED (`smallgrid`) |
| `simTimeAuthority.getPause()` | `lua/ge/simTimeAuthority.lua:34-40` | TESTED (`false`) |
| `freeroam_freeroam.startFreeroam(path)`, `core_levels.startLevel` | `core/loadMapCmd.lua:49`, `core/levels.lua:508` | CONFIRMED, not used (we launch with `-level`) |
| Shipped levels | `content/levels/*.zip`; `GridMap.zip` has no entry point (log) | TESTED for smallgrid; gridmap_v2 next |
| `debugDrawer:drawSphere/drawLine/drawTextAdvanced` | `ge_utils.lua:1349, 1357`; `flowgraph/nodes/vehicle/groundDistance.lua:46` | CONFIRMED (for debug views) |

## Native drawing, shading, cameras and terrain (2026-10-03)

| API | Source | State |
|---|---|---|
| `util_export` vertex normals | `ge/extensions/util/export.lua:864-879` writes `normalsGet` as the engine gives them | TESTED: normals are BeamNG z-up while positions are glTF y-up; turned (x, z, -y) they match their triangles at 0.91-0.97 (0.33-0.43 as stored), 18 exports |
| `util_export` glTF `doubleSided` | `export.lua:270` (always true) vs `:800` (`materialObj:getField('doubleSided', 0)` into extras) | TESTED: use `extras.bngMaterial.doubleSided`; the M2's seat leather is "0" |
| Props in an export | primitives with their own POSITION accessor + node translation/rotation | TESTED: steering wheel, mirrors, pedals; normals turn like the flexmeshes' and then with the node (0.85-1.0) |
| Layered material pipeline | `shaders/common/material/shadergen/defaultMat.hlsl:212-424` (processLayers), `shadergen.h.hlsl:163-192` (getColorPalette), `:214-232` (decodeNormal) | CONFIRMED (CarShader follows it); M2 matches BeamNG's render side by side |
| Material layer fields | `editor/materialEditor.lua:1282-1340` (`paletteMetallic`, `paletteRoughness`, `paletteClearCoat`, `paletteClearCoatRoughness`, `roughnessFactor`, `opacityFactor`, `*MapUseUV`, `normalMapStrength`) via `mat:getField(name, layer)` | TESTED (side file `layers`) |
| `veh:getMetallicPaintData()` | `core/vehicle/colors.lua:79`; entries as `createVehiclePaint` reads them, `ge_utils.lua:953-966` (by name or position; defaults 0.2, 0.5, 0.8, 0) | TESTED: M3 red = {0, 1, 1, 0}, M2 grey = {1, 0.65, 1, 0.03} |
| `screenshot.doScreenshot(nil, nil, path, 'png')` | call `core/vehicle/partmgmt.lua:679`, def `ge/screenshot.lua:503` | TESTED (dev `shot`): game image only, written a few frames later |
| Onboard driver camera node | `common/jbeam/sections/camera.lua:93-108` (`camNodeID` = `addNodeWithOptions`, `common/jbeam/utils.lua:28-44`); aim: `core/cameraModes/onboard.lua:53-110`; config: `core/camera.lua:880-912` (`vdata.cameraData.onboard`, `refNodes[0]`, `cameraRefNodes`) | TESTED: M2 driver camera = node 452; BeamNG's view from it matches ours |
| `util_terrainGenerator.new{terrainScale, terrainHeight}`, `.heightMap = file`, `:createTerrain()`, `:setTerrainOffset(vec3)` | `ge/extensions/util/terrainGenerator.lua:9-22, 41-55, 98-103, 380-466` | TESTED: 512^2 in 519 ms, 1024^2 in 450-540 ms; image columns = +x, image rows flipped (row 0 = north edge); heights absolute above the offset. Its `init` deletes every `*heightMap.png` under `temp/`, so ours is `terrain_h.png` |
| `editor.createTerrain` | `editor/api/terrain.lua:17` | REJECTED: an empty stub (`--TODO`) |
| `tb:setHeight(x, y, h)` + `tb:updateGrid(vec3(x0, y0), vec3(x1, y1))` | `editor/terrainAndRoadImporter.lua:111-116` | TESTED: a 3x3 pit dug in Minecraft read y 64.0 in BeamNG a second later |
| `core_terrain.getTerrainHeight(vec3)` | `core/terrain.lua:21-26` (`terrain:getHeight`) | TESTED; returns 0 outside the terrain block |
| Physics after `tb:updateGrid` | none in Lua: the engine's own physics terrain | MEASURED 2026-10-05 (local probe scripts over the bridge's `terrain_cells`): `getTerrainHeight` reads new heights at once, but the physics takes them up ~0.7 s after the last `updateGrid` anywhere, and each call restarts the wait. Ground lowered 1.4 m under a parked car: it fell 0.77 s later; with an unchanged patch re-sent every 0.25 s 40 m away, it fell 0.55 s after they stopped; with the same patch re-sent over it, not at all |
| `be:reloadCollision()` after `updateGrid` | as above | TESTED: puts the new heights into the physics at once (the car fell 0.08 s after it, patches still streaming); 5-10 ms on smallgrid with ~1100 cubes. Used after each terrain flush, at most every 0.25 s (`collsched.lua`) |
| TerrainBlock without materials | log: "ground model not found for collision: 'DEFAULT' - using asphalt" | OBSERVED: drives as asphalt |
| `createObject('WaterPlane')` + the editor's fields, `setPosition`, `registerObject`, `MissionGroup:addObject` | `editor/createObjectTool.lua:706-737` (buildWaterBlock) | TESTED: water at Minecraft's sea level; `obj:inWater` true on the sunk Porsche. The editor's `depthGradientTex` `core/art/water/depthcolor_ramp_b.color.png` doesn't exist ("Texture missing" in the log); `gameengine.zip` has `core/art/water/depthcolor_ramp_b.png` |
| `obj:inWater(nodeCid)` (vehicle Lua) | `vehicle/powertrain/combustionEngine.lua:337`, `wheels.lua:187`, `fire.lua:87` | TESTED: true for the ref node `v.data.refNodes[0].ref` under the WaterPlane |
| `damageTracker.getDamage('engine', 'engineHydrolocked')` (vehicle Lua, global) | `vehicle/damageTracker.lua:77-79`, set at `combustionEngine.lua:353` | TESTED: true on the sunk Porsche, rpm 0 |

## Latches, dents, material colours and sound (2026-10-03, night)

| API | Source | State |
|---|---|---|
| `controller.getController(name).toggleGroup()` on an `advancedCouplerControl` controller | `vehicle/controller/advancedCouplerControl.lua:209`; its `couplerNodes` header table read with `tableFromHeaderTable` (`:429`) | TESTED: the Cadillac CT5's doors open from Minecraft (`latches.lua`) |
| `beamstate.breakBreakGroup(g)` (vehicle Lua) | `vehicle/beamstate.lua:166`, called at `:446`, `:791` | CONFIRMED, used for latch breakgroups; no car with one met yet |
| `obj:getNodeMass(cid)` (vehicle Lua) | `vehicle/bdebugImpl.lua:777` | TESTED: dents push mass x speed change / 15 ms per node (pickup) |
| Material colour factors are picked as seen (gamma) | `editor/materialEditor.lua:1641-1656` (`colorEdit4`); paint goes linear in the shader via `toLinearColor`, `shaders/common/material/shadergen/shadergen.h.hlsl:173-175` | TESTED: the CT5's black trims match BeamNG with a 2.2 power applied |
| v1 materials' colour is `diffuseColor` | `editor/materialEditor.lua:1283` | TESTED (CT5 `black_007`, `black_008`) |
| Detail map blended over white where a layer has no base map | `defaultMat.hlsl:294-307` | CONFIRMED: the CT5's rims (no base map, anthracite detail) match BeamNG's black |
| Translucent materials are premultiplied by the material opacity (no base alpha) | `defaultMat.hlsl:563, 571-573` | CONFIRMED: the CT5's opacity-1 tinted windows are black in BeamNG and now in Minecraft |
| `core_settings_audio.switchOutputDevice(index)` | `ge/extensions/core/settings/audio.lua:317-327` -> `createAudioDevice` `:310` | REJECTED: `Engine.Audio.createAudioDevice` is nil in 0.39.4 ("attempt to call field 'createAudioDevice' (a nil value)", measured) |
| `Engine.Audio.getInfo().FMOD` | `core/settings/audio.lua:10, 319-325` | TESTED: list of `{name, channels, mode, sampleRateHz, ...}` |
| FMOD follows the Windows default output | `beamng.log`: "Successfully switched to new audio output: name 'System Virtual Line (MSI Sound Tune)'" | OBSERVED twice on 2026-10-03 when the default changed |
| `translucent = 1` with `translucentBlendOp = "None"` is drawn solid | `editor/materialEditor.lua:2338-2375` (`alphaBlendCombo`: shown as mode None) | TESTED: the Gallardo mod's paint, solid in BeamNG and now in Minecraft |
