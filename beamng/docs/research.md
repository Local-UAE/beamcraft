# Research: upstream architecture and this PC

Date of research: 2026-10-02. Upstream commit: `d172387` of
`github.com/justbustin/minecraft-crossover-bridge` (4 commits, MIT licence).

## 1. How the upstream project works

Upstream runs Minecraft 1.21.1 (Fabric) natively on an Apple Silicon Mac and a Windows game
(Monster Hunter: World or Elden Ring) inside CrossOver/Wine on the same Mac. There are two
halves per game:

| Half | Language | Where it runs | What it does |
|---|---|---|---|
| `mc-bridge/` | Java, Fabric mod | macOS JVM | Owns the player. Publishes camera pose, reads back rendered frames, turns host terrain rays into invisible blocks, turns host monsters into invisible proxy entities, forwards Minecraft hits. |
| `mhw-bridge/` / `er-bridge/` | C++17, cross-compiled with mingw-w64 | Inside the game process under Wine | Loaded as a `dinput8.dll` proxy, which loads a hot-swappable `*_core.dll`. Hooks the game, overrides its camera, casts rays against its collision, enumerates characters, applies damage, and composites Minecraft frames at `Present`. |

There is no third process. The two halves talk only through shared memory.

### 1.1 Transport

- `bridge.shm` (8 MB) and `frames.shm` (~80-110 MB) are plain files in `/tmp/mhwmc` (or
  `/tmp/ermc`). The Windows side opens them as `Z:\tmp\...`. Wine implements file mappings with
  `mmap(MAP_SHARED)`, so the JVM's `MappedByteBuffer` and the DLL's `MapViewOfFile` see the same
  pages. This trick exists only because the two programs run on different OS layers of one Mac.
- Every block written by one side and read by the other is a **seqlock**: the writer bumps `seq`
  to odd, writes, bumps to even; readers retry if `seq` was odd or changed. Java uses
  `VarHandle` acquire/release because the JVM runs on ARM; the C++ side relies on x86 TSO plus
  compiler barriers.
- Liveness is a heartbeat counter per side (`mhwHeartbeat` once per `Present`,
  `mcHeartbeat` once per Minecraft frame). Minecraft treats the host as alive if the counter
  moved within 2 s, and ignores a stale counter left from an earlier session.
- Request/response blocks (debug mailbox, ray batches) use `reqSeq`/`respSeq` pairs.
- Minecraft→host damage uses a single-producer ring (`write`/`read` counters, 256 entries).

`include/bridge_protocol.h` is the authoritative layout, mirrored by hand in
`link/Protocol.java`, with `static_assert`s on every struct size.

### 1.2 Camera

Minecraft owns the viewpoint. In `CameraMixin` (tail of `Camera.setup`) the mod reads the
camera position, look vector and up vector, converts them to host units, and writes
`camPos/camTarget/camUp/fovYDeg` into the control block. The DLL overwrites the game's camera
after the game computed its own (MHW: detour on the camera update; ER: a task in the game's
scheduler). The DLL reports `STATE_CAM_OVERRIDDEN` when a frame really used Minecraft's pose.

A second mode (F7, `FOLLOW_MHW`) does the reverse: Minecraft renders from the host's camera.
It exists to check alignment.

`GameRendererMixin` forces Minecraft's FOV to a fixed value and disables view bobbing and hurt
tilt, because any per-frame projection difference shows up as the two worlds sliding.

### 1.3 Rendering

Two modes:

1. **Overlay window.** Minecraft's GLFW window is created with
   `GLFW_TRANSPARENT_FRAMEBUFFER`, made borderless and always-on-top, and glued to the host's
   client rectangle (the DLL publishes `winX/winY/winW/winH`). Sky, clouds, weather, fog, menu
   blur and vignette are suppressed so only Minecraft geometry is opaque, and the final blit
   writes alpha. No occlusion; the two images can drift by a frame.
2. **Passthrough (in-frame compositing).** After `LevelRenderer.renderLevel`, Minecraft
   reads back world colour (BGRA8) and depth (float32) into PBOs, clears colour to transparent,
   lets the hand and HUD draw, and reads that layer back too. One frame later (so the async
   readback is done) it copies the three layers into a triple-buffered `frames.shm` slot under
   a seqlock and only then publishes that frame's camera pose. The DLL keeps a short history of
   applied pose IDs, picks the slot whose `poseId` matches the pose the presented image was
   rendered with (`poseLag`, default 1 game frame), uploads it to DYNAMIC textures with
   `Map(WRITE_DISCARD)`, and draws a full-screen triangle into the back buffer before the real
   `Present`.

The compositor shader (`compositor.cpp`):
- linearises Minecraft depth with the OpenGL formula and the host depth with a standard or
  reversed-Z formula chosen from the clear value, both in metres;
- discards a Minecraft pixel if it is farther than the host surface plus a small bias;
- relights Minecraft pixels from a heavily mip-blurred copy of the host frame and fades them
  into the host scenery with distance;
- composites the GUI layer on top with premultiplied "over".

The host's scene depth is found by detouring `ID3D11DeviceContext::ClearDepthStencilView`
(vtable slot 53) and keeping back-buffer-sized, single-sample, shader-readable depth textures
that get cleared each frame. MHW clears two such buffers; which one is the scene depth is a
runtime knob (`depthIndex`). All pipeline state touched by the compositor is saved and restored
by hand. The `Present` hook uses MinHook on vtable slots 8 (`Present`), 22 (`Present1`) and 13
(`ResizeBuffers`), with addresses taken from the game's own swapchain or a throwaway one.

### 1.4 Terrain

Server side (`TerrainManager`), on the integrated server thread. Per player it walks rings of
columns outward to radius 24 and, for each column not sampled within ±4 blocks of the player's
current height, queues four rays: down from 4 above the feet to 40 below, down from 40 above
to 4 above (hills ahead), and two horizontal probes across the column at +1 block (walls).
Batches of up to 1020 rays go into the shared ray block; the DLL casts a few hundred per frame
on the game thread and answers with hit position, normal and surface attributes.

Each hit becomes a column of `TerrainBlock`s, an invisible block with a `HEIGHT` property
1..16 so the top surface lands within 1/16 block of the host ground. Steep horizontal hits that
stand above the ground fill the column to head height (walls). The previous contents of a
column are cleared when it is resampled. If rays never answer, a flat floor at the host
player's feet is used. Placed blocks may replace terrain voxels whose top is in the lower half.

### 1.5 Entities

`EntityBridge` keeps one invisible `LivingEntity` proxy per host entity ID in a
`Long2ObjectOpenHashMap`, created once, updated every tick, discarded when the ID disappears.
The host's oriented hitbox is turned into an axis-aligned box. Hits on a proxy go into the damage
ring with a host-side scale factor. The proxy entity type is `noSave`, `noSummon`,
`clientTrackingRange(16)`, `updateInterval(1)`.

### 1.6 Coordinates

`CoordMap.Mapping` pins one host point (the host player's feet when the bridge first saw a zone)
to Minecraft `(0.5 + region*spacing, 100, 0.5)`, with a `unitsPerMeter` scale (MHW: 100, ER: 1)
and an optional Z mirror (ER is left-handed). Each host zone gets its own region of the
Minecraft world, persisted in `*-anchor.properties` in the world folder. Elden Ring raises the
overworld to `min_y -2032`, height 4064, via a datapack `dimension_type` override.

### 1.7 Input switching

F8 in Minecraft hides and pauses Minecraft, releases the mouse and bumps `hostFocusReq`; the DLL
calls `SetForegroundWindow` on the game window. F8 in the game is seen by the DLL through
`GetAsyncKeyState` while the game window is focused, and it bumps `mcSwitchReq`; Minecraft
shows and focuses its window again.

### 1.8 Debug tooling

- A command mailbox in `bridge.shm` (ping, memory read/write/scan, raycast, module list).
  Python clients `mhwctl.py`/`erctl.py` use it.
- `DevCommands`: Minecraft polls `/tmp/<game>/mc_cmd.txt` once per tick and runs each line
  (`status`, `cam third`, `pt debug`, `terrain reset`, `run <command>`, `quit`...).
- Rate-limited logs: perf every 300 frames, sync statistics every 300 frames, depth candidates
  logged with a budget.
- Hot reload: the loader DLL watches `coreReloadReq` and swaps the core while the game runs.

## 2. What we can reuse, and what we can't

| Component | Verdict for BeamNG |
|---|---|
| Seqlock shared-memory blocks, heartbeats, latest-wins slots | Reuse the design. Windows named file mappings replace `/tmp` files. |
| `frames.shm` triple buffer, PBO readback, pose-after-pixels ordering | Reuse almost verbatim. Only the path and naming change. |
| D3D11 compositor shader and state save/restore | Reuse the structure. BeamNG's depth convention must be measured, not assumed. |
| `ClearDepthStencilView` depth discovery | Reuse as an instrumentation tool; BeamNG may clear several buffers. |
| `TerrainBlock` (1/16 height voxels) and `TerrainManager` column sampler | Reuse with BeamNG raycasts as the ray source. |
| Proxy entity map (`id -> entity`, no per-frame respawn) | Reuse for vehicles. |
| Overlay window: transparent GLFW framebuffer, sky/fog/cloud suppression, alpha blit fixes | Reuse. The macOS NSWindow calls are replaced by Win32 extended styles. |
| `CameraMixin`, FOV lock, no bobbing | Reuse. |
| `CoordMap` with `unitsPerMeter`/`flipZ` | Replaced by one canonical transform (`coords/CrossoverCoords.java`). BeamNG is metres, Z-up and needs an axis rotation, not a mirror. |
| Game-specific memory hooks (camera detours, character lists, damage functions, signatures) | Not needed. BeamNG exposes camera, raycasts and vehicles through Lua, so the gameplay path needs no reverse engineering. |
| `dinput8.dll` proxy loader | Not needed for gameplay. Needed later only if the native compositor can't be loaded another way (see section 4). |
| Debug memory mailbox | Not ported. BeamNG's Lua console and our IPC cover this without arbitrary memory access. |

## 3. macOS / CrossOver assumptions that don't carry over

- `/tmp/...` paths and the `Z:\` drive mapping: replaced by Windows named shared memory
  (`CreateFileMappingW` on the page file, `Local\` namespace) or a file under `%TEMP%`.
- Wine's `mmap(MAP_SHARED)` behaviour: on native Windows, two processes simply open the same
  named mapping. The JVM can't open a *named* mapping directly, so the Java side maps a real
  file (`FileChannel.map`) and the native side maps the same file. Both views are coherent on
  Windows because they go through the same section object.
- ARM vs x86 memory ordering: both sides are x86-64 here. The Java acquire/release fences stay
  (they are correct everywhere and cost nothing on x86).
- `GLFWNativeCocoa`/`NSWindow` calls (`setIgnoresMouseEvents:`, `setHasShadow:`): replaced by
  `WS_EX_LAYERED`/`WS_EX_TRANSPARENT` handling through LWJGL's `GLFWNativeWin32` + User32.
- `GLFW_COCOA_RETINA_FRAMEBUFFER`: irrelevant. Windows DPI scaling has to be checked instead,
  since the overlay must match BeamNG's client rectangle in physical pixels.
- DXMT quirks (no `UpdateSubresource` into staging, no `Map` on DEFAULT resources): real D3D11
  doesn't have them, but the conservative code still works.
- D3DMetal's swapchain function table patch (Elden Ring): not applicable.
- Rosetta and the macOS 14 D3DMetal shim: not applicable.
- `zsh`/`.command` launchers and `make` + mingw: replaced by PowerShell and CMake + MSVC.

## 4. Windows port issues and decisions

1. **No game-specific DLL for gameplay.** BeamNG's Game Engine Lua already offers the camera,
   raycasts, vehicles and sockets (see `beamng-api-notes.md`). Gameplay sync runs in a Lua
   extension in the user folder, which is the supported modding route. Nothing in the game
   install is modified.
2. **Control IPC is UDP on 127.0.0.1**, not shared memory, for the gameplay path. BeamNG Lua
   can't map shared memory without FFI tricks, while LuaSocket UDP with `settimeout(0)` never
   blocks the game thread, and datagrams give "latest state wins" for free. Details in
   `protocol.md`.
3. **Frames still go through shared memory.** That needs native code inside BeamNG. Two
   candidate loaders, to be decided by experiment in the D3D11 stage: a proxy DLL next to
   `BeamNG.drive.x64.exe`, or LuaJIT `ffi.load` from the Lua extension if BeamNG allows it.
4. **D3D11 first.** BeamNG ships a DX11 renderer option; upstream's MHW compositor is D3D11.
5. **Minecraft 1.21.1 + Fabric**, like upstream, so the verified mixin targets carry over.
   Loom 1.11.8 on Gradle 8.14.3 because this PC has JDK 21 and 24 but not 25.

## 5. Risks

- BeamNG updates often and its Lua API changes between versions. Every API we use is recorded
  with its call site so breakage can be diagnosed quickly.
- BeamNG's camera FOV convention (horizontal or vertical) must be measured; a wrong guess
  makes the overlay drift toward the screen edges.
- BeamNG's renderer may not keep a single scene depth buffer at back-buffer size (TAA,
  dynamic resolution, multiple passes). Depth acquisition is a research task, not a port.
- Two games at once on one GPU: frame pacing will be uneven, so the pose/frame matching from
  upstream matters more than on a single game.
- The `E:` drive is exFAT with a known corrupt allocation bitmap. The project and BeamNG's user
  folder both live there; keep backups and avoid huge writes during tests.
- Anti-cheat: none in single-player BeamNG, but BeamMP servers must never see a modded client.
