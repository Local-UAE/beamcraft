# Architecture

How the BeamNG crossover is put together, as built and tested on 2026-10-01 (BeamNG 0.39.4,
Minecraft 1.21.1 + Fabric, Windows 11). The research behind these choices is in `research.md`.

## The two halves

```
 Minecraft 1.21.1 (Fabric mod "bngbridge", JVM)            BeamNG.drive 0.39.4 (GE Lua extension "mccross_bridge")
 ──────────────────────────────────────────────             ─────────────────────────────────────────────────────
 camera pose every frame (eye, fwd, up, vertical FOV) ──▶   camera filter overrides BeamNG's camera each frame
 terrain column batches (raycols)                    ──▶    castRayStatic scans, 2 ms/frame budget
                                                    ◀──     surfaces + wall probes (rayhits)
                                                    ◀──     state 60 Hz: level, fps, player car, camera
                                                    ◀──     vehicles 20 Hz: every car's box and velocity
 explosions, hits on cars, entering a car            ──▶    planets / velocity changes / be:enterVehicle
 placed blocks (add/remove)                          ──▶    1 m collision cubes, vehicle collision rebuilt
                       UDP, 127.0.0.1:47020, JSON, protocol v1 (protocol.md)
```

There is no third process. Upstream needed a DLL inside the host game because Monster Hunter
and Elden Ring had no scripting; BeamNG's Lua already exposes the camera, raycasts, vehicles,
physics and sockets, so the whole gameplay path runs in a Lua extension loaded the supported way
(an unpacked mod in the user folder). Nothing in the game install is modified.

A separate bridge process would only add a hop. The one job that will need native code is
drawing Minecraft's frames into BeamNG's own frame; that will be a DLL inside BeamNG, talking to
Minecraft through shared memory, next to (not instead of) this link.

## BeamNG side (`beamng-mod/`)

| File | Job |
|---|---|
| `scripts/mccross/modScript.lua` | Registers the extension as "manual" so it loads at startup and survives level changes |
| `lua/ge/extensions/mccross/bridge.lua` | Sessions, message handlers (all under `pcall`), state and vehicle publishing, camera override source, blasts, impulses, entering cars, collision cubes, background-FPS hold, perf counters |
| `lua/ge/extensions/mccross/rays.lua` | Batched terrain columns: multi-surface vertical scans and two-height wall probes, time-budgeted |
| `lua/ge/extensions/core/cameraModes/mccrossCamera.lua` | Camera filter at runningOrder 0.95: replaces whatever camera mode is active with Minecraft's pose, right before BeamNG's output filter |

Everything runs on BeamNG's game-engine thread and never blocks: the socket is non-blocking,
raycasts stop after 2 ms per frame. Collision rebuilds can't be made cheap (BeamNG rebuilds the whole level,
~0.5 s on gridmap_v2), so `collsched.lua` batches them: at once when a car is about to reach changed
blocks, otherwise once building pauses for 2 s (at most 8 s). Measured
cost: 0.034 ms per frame for the extension's update.

## Minecraft side (`minecraft/`)

Server side (integrated server thread):

| Class | Job |
|---|---|
| `BngWorld` | Which BeamNG level is live, which Minecraft region it owns (persisted), putting Steve beside the car |
| `TerrainManager` + `ColumnPlan` | Requests terrain columns around the player, turns BeamNG's surfaces into invisible 1/16-height terrain voxels |
| `entity/VehicleBridge`, `BngVehicleEntity`, `VehicleHits`, `CrashDamage` | One solid invisible proxy per BeamNG car, placed straight from BeamNG data; cars hitting creatures; crash damage |
| `BlastBridge` + `mixin/ExplosionMixin` | Explosions to BeamNG; explosion rays through partial terrain voxels |
| `BlockSync` + `mixin/LevelChunkMixin` | Solid blocks mirrored as BeamNG collision cubes |

Client side (render/client thread):

| Class | Job |
|---|---|
| `CameraSync` + `mixin/CameraMixin`, `GameRendererMixin` | Camera pose to BeamNG every frame; FOV and view effects locked so both projections match |
| `Overlay` + window/render mixins, `Win32` | Transparent, borderless, always-on-top window glued to BeamNG's client area |
| `ControlSwitch`, `CarRide` | F4 handoff, getting in and out of cars, riding, roof carrying |
| `TerrainHold` | No falling through unsampled ground |
| `StatusHud`, `DevCommands`, `WorldBootstrap` | Diagnostics, terminal-driven tests, auto-opening the bridge world |

Shared: `link/` (the UDP link on its own I/O thread, message parsing, the vehicle store) and
`coords/CrossoverCoords` (the only place that converts coordinates).

## Threads and data flow

- The link's I/O thread receives everything and keeps the newest message of each type ("latest
  wins"). Game threads read immutable snapshots and send; nothing waits on the network.
- Vehicle data is parsed once per message into an immutable map that server proxies (collision)
  and client proxies (crosshair, local collision) both read, so the two copies agree.
- Camera poses are sent from the render thread right after `Camera.setup`, so the pose BeamNG
  applies is the one Minecraft is rendering.

## Rendering today and next

Today: overlay window mode. Minecraft draws its world over BeamNG's window with a transparent
background; both cameras are synced to within 2.6 px, but Minecraft is always in front (no
occlusion), and the two images can be a frame apart.

Next (roadmap stages 9-12): frames into shared memory, a D3D11 compositor inside BeamNG at
`Present`, BeamNG's depth buffer for occlusion. Upstream's MHW compositor is the template.

## Coordinates

Canonical frame = BeamNG world (metres, Z up). Minecraft region r maps BeamNG (x, y, z) to
Minecraft (x + 65536 r, z, -y). BeamNG quaternions are the conjugate of canonical ones. Details
and tests: `coordinates.md`.
