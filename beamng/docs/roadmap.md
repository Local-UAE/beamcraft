# Roadmap

Stages run in order; a stage is DONE only when its pass condition was met in the running games
(not just in tests). Status as of 2026-10-01.

| # | Stage | Status |
|---|---|---|
| 0 | Research | DONE |
| 1 | BeamNG mod loads | DONE |
| 2 | Telemetry and two-way IPC | DONE |
| 3 | Minecraft connects | DONE |
| 4 | Canonical coordinates | DONE |
| 5 | Minecraft drives BeamNG's camera | DONE |
| 6 | Terrain and collision | DONE for flat, bumps, ramps, walls; bridges/tunnels implemented, not yet walked live |
| 7 | Vehicle proxies | DONE |
| 8 | Visual alignment, overlay, input handoff | DONE |
| 9 | Frame sharing | NEXT |
| 10 | D3D11 compositor | TODO |
| 11 | BeamNG depth | TODO |
| 12 | Depth-aware compositing | TODO |
| 13 | Visual polish | TODO |
| 14 | Gameplay interactions | STARTED (pulled forward on request) |
| 15 | D3D12 backend | TODO |

**MVP: COMPLETE** (2026-10-01): every item of the MVP definition met in the live games, listed
at the end.

---

## 0. Research — DONE
- **Goal:** understand upstream and this PC before building.
- **Implementation:** `research.md`, `beamng-api-notes.md`.
- **Automated test:** n/a.
- **Pass:** every BeamNG API used has a cited call site and a state.
- **Fallback:** n/a.

## 1. BeamNG mod loads — DONE
- **Goal:** our extension loads in a clean user folder, no errors.
- **Implementation:** `modScript.lua` + `mccross_bridge` extension; `scripts/run-beamng.ps1`.
- **Automated test:** `run-beamng.ps1 -Wait` waits for `[MCCROSS] waiting for bridge...`; the mock suite runs the real Lua offline.
- **Manual test:** none needed.
- **Pass:** startup log lines present, no `|E|` lines from `mccross`. Met.
- **Fallback:** extension errors are pcall-contained per message; `install-beamng-mod.ps1 -Remove` uninstalls.

## 2. Telemetry and two-way IPC — DONE
- **Goal:** ≥ 30 Hz telemetry to an external program; ping, debug, camera test, disconnect; sessions, sequence numbers, timeouts, reconnect, drop counters.
- **Implementation:** UDP JSON v1 (`protocol.md`), `bngdiag.py`.
- **Automated test:** `bngdiag.py bench` (PASS ≥ 30 Hz + ping), `bngdiag.py reconnect`, `BngLinkTest` (4 cases), mock suite.
- **Pass:** 60.3 Hz, 0.66 ms RTT, 0 drops; reconnect and stale-session rejection PASS. Met.
- **Fallback:** if UDP ever proves lossy under load, the same envelope can run over the TCP template in `lua/common/tcpServer.lua`.

## 3. Minecraft connects — DONE
- **Goal:** Minecraft shows live BeamNG state.
- **Implementation:** `BngLink`, `StatusHud`, `WorldBootstrap`.
- **Automated test:** `mc.ps1 status`.
- **Pass:** `BEAMNG CONNECTED: YES`, map, vehicle, speed, position, RTT, protocol shown. Met.
- **Fallback:** `-Dbngbridge.noAutoWorld=true` to open worlds by hand.

## 4. Canonical coordinates — DONE
- **Goal:** one tested transform for positions, rotations, velocities, FOV.
- **Implementation:** `CrossoverCoords`; BeamNG's quaternion convention measured in-game.
- **Automated test:** `CrossoverCoordsTest` (32 cases incl. the measured BeamNG quaternions).
- **Pass:** all tests green, and the pixel alignment of stage 8. Met.
- **Fallback:** none needed.

## 5. Minecraft drives BeamNG's camera — DONE
- **Goal:** walking/turning/looking in Minecraft moves BeamNG's view identically, no snapping.
- **Implementation:** camera filter in BeamNG's camera pipeline; pose sent each Minecraft frame.
- **Automated test:** `bngdiag.py camfollow` (external driver), `diagnose.py` (camera driven ≥ 95% of frames).
- **Manual test:** none needed; screenshots taken by script.
- **Pass:** state reports `DRIVEN BY MINECRAFT` 100% of frames; walking 12.95 m moved BeamNG's camera 12.95 m. Met.
- **Fallback:** `scriptedFree` camera mode or `onCameraPreRender` hook (`beamng-api-notes.md`).

## 6. Terrain and collision — DONE (bridges/tunnels pending a live walk)
- **Goal:** Steve walks on BeamNG's ground: flat, slopes, ramps, road edges, walls, bridges, overhangs.
- **Implementation:** batched multi-surface columns (`rays.lua`), `ColumnPlan`, `TerrainManager`, `TerrainHold`.
- **Automated test:** `ColumnPlanTest` (9 cases from measured data); `walktest.py` compares Steve's feet to BeamNG's ground along a walk.
- **Manual test:** none required.
- **Pass:** smallgrid flat; gridmap_v2 berm (0.05 m error), wall stop, slab ramp 100 → 108 m and down (0.15 m mean). Met. A bridge and a tunnel still need a `walktest.py` run (west_coast_usa).
- **Fallback:** column resolution is 1 m; finer detail needs half-block columns (4x rays).

## 7. Vehicle proxies — DONE
- **Goal:** every BeamNG car exists in Minecraft space with a solid box, stable id mapping.
- **Implementation:** `Vehicles`, `BngVehicleEntity`, `VehicleBridge`.
- **Automated test:** proxy count in `status`; `diagnose.py`.
- **Pass:** proxy outline encloses the car from the synced camera; moving car tracked. Met.
- **Fallback:** n/a.

## 8. Visual alignment, overlay, input handoff — DONE
- **Goal:** both views show the world from the same pose; Minecraft drawn over BeamNG; easy control switching.
- **Implementation:** `markers` alignment test, `Overlay`, `ControlSwitch` (F4).
- **Automated test:** marker pixel test (`docs/img/alignment-smallgrid.png`), `test-handoff.ps1` (8 checks), `diagnose.ps1`.
- **Pass:** spheres within 2.6 px of the camera model; overlay glued to BeamNG's client area; handoff 8/8; REALTIME PASS. Met.
- **Fallback:** side-by-side windows (`arrange-windows.ps1`) if a driver lacks transparent framebuffers.

## 9. Frame sharing — NEXT
- **Goal:** Minecraft's colour + depth per frame in shared memory, newest-complete-frame semantics.
- **Implementation:** upstream's PBO readback + triple-buffered slots, ported to a named file mapping (`Local\bngmc-frames`) instead of `/tmp`; frame id, pose id, timestamps, drop/duplicate counters.
- **Automated test:** a Python reader that validates slot seqlocks, frame age and checksum stability while Minecraft runs; frame age < 2 frames, no torn frames over 10 000 frames.
- **Manual test:** none.
- **Pass:** reader sees ≥ 55 fps, 0 torn frames, Minecraft fps drop < 15%.
- **Fallback:** lower resolution slots or colour-only frames.

## 10. D3D11 compositor — TODO
- **Goal:** Minecraft's frame drawn inside BeamNG's own frame at `Present`.
- **Implementation:** DLL loaded into BeamNG (proxy DLL or LuaJIT `ffi.load`, decided by experiment), BeamNG started with `-gfx dx11` (to verify), MinHook on `Present`/`ResizeBuffers`, upstream's shader and state save/restore. Fails closed: if anything is unexpected, the compositor turns itself off and logs why.
- **Automated test:** compositor self-test pattern + screenshot comparison; BeamNG frame time with/without the compositor.
- **Pass:** Minecraft visible in BeamNG's frame with the overlay window hidden, < 1 ms added per frame, no crashes over 30 minutes, resize survives.
- **Fallback:** overlay window mode stays available.

## 11. BeamNG depth — TODO
- **Goal:** identify BeamNG's scene depth buffer and its convention.
- **Implementation:** instrument `ClearDepthStencilView`/`OMSetRenderTargets`, list candidates (size, format, samples, clears, binds), debug views: BEAMNG DEPTH, MINECRAFT DEPTH, DIFFERENCE, OCCLUSION MASK, MINECRAFT ONLY, BEAMNG ONLY, FINAL.
- **Automated test:** place markers at known distances; the linearised BeamNG depth at their pixels must match the camera distance within 2%.
- **Pass:** a verified buffer and formula, documented in `depth.md` with tests.
- **Fallback:** depth from BeamNG raycasts per pixel block (coarse occlusion).

## 12. Depth-aware compositing — TODO
- **Goal:** Minecraft geometry hidden behind BeamNG scenery, visible in front.
- **Implementation:** both depths linearised to metres (near/far, reversed-Z, projection), per-pixel test, bias.
- **Automated test:** blocks behind and in front of a BeamNG wall at known distances: occluded/visible pixel counts.
- **Manual test:** walk behind a building and back out.
- **Pass:** correct occlusion in all marker cases, no shimmering at edges.

## 13. Visual polish — TODO
Optional relighting, fog matching and exposure, following upstream's shader; all switchable.

## 14. Gameplay interactions — STARTED
Done and verified live: TNT → BeamNG blast physics; melee hits push cars; cars hit creatures; getting into/out of cars with riding and crash damage; standing on moving roofs; Minecraft blocks as BeamNG obstacles. Open: arrows (one test missed), Steve as a physical body in BeamNG (cars don't feel him), mobs riding cars.

## 15. D3D12 backend — TODO
BeamNG's default renderer on this PC is D3D12; once D3D11 works, port the compositor behind the same backend interface.

---

## MVP definition (all met in the live games on 2026-10-01)

- [x] Minecraft and BeamNG run simultaneously.
- [x] BeamNG mod loads reliably (about a dozen hot reloads and two clean cold starts, no mod errors).
- [x] Two-way communication works.
- [x] Reconnection works.
- [x] Minecraft receives live BeamNG telemetry.
- [x] Coordinate conversion has been tested.
- [x] Minecraft controls BeamNG's camera.
- [x] Steve can stand/walk on terrain derived from BeamNG.
- [x] A BeamNG vehicle exists correctly in Minecraft coordinate space.
- [x] The two render views can be visually synchronized.
- [x] Basic overlay mode works.
- [x] Diagnostics confirm realtime operation (`diagnose.ps1`: REALTIME PASS).
