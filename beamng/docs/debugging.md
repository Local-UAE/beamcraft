# Debugging

## First checks

```powershell
cd beamng                            # in your checkout
scripts\diagnose.ps1                 # both games: dashboard + REALTIME PASS/FAIL
python bridge\bngdiag.py bench 8     # BeamNG alone: state rate, RTT, drops
scripts\mc.ps1 status                # Minecraft's status lines (same as the F9 display)
scripts\logs.ps1                     # [MCCROSS] lines and errors from beamng.log
```

`diagnose.ps1` checks: BeamNG fps ≥ 30, Minecraft fps ≥ 30, state ≥ 30 Hz, worst state gap ≤
250 ms, RTT ≤ 10 ms, drops ≤ 1%, extension cost ≤ 1 ms per frame, camera driven ≥ 95% of frames.

## Where the logs are

| What | Where |
|---|---|
| BeamNG (test folder) | `bng-userfolder\current\beamng.log` in your checkout (`scripts\logs.ps1`) |
| BeamNG (your normal folder) | `%LOCALAPPDATA%\BeamNG\BeamNG.drive\current\beamng.log` (`scripts\logs.ps1 -Player`) |
| Minecraft | `beamng\minecraft\run\logs\latest.log`, lines tagged `(bngbridge)` |
| Dev command answers | `%TEMP%\bngmc\mc_out.txt` |

**beamng.log is buffered**: new lines can take minutes to appear. For live state, ask over the
socket (`bngdiag.py telemetry`, `raw`) instead of tailing the log. The extension writes a perf line
every 30 s: fps, update cost, clients, message counts, ray totals.

Logging is rate-limited on purpose: repeated warnings print at most once per 5-30 s.

## Status display (F9 in Minecraft)

Sections: BEAMNG (version, map, fps, vehicle, camera and whether Minecraft drives it), BRIDGE
(RTT, state rate, KiB/s each way, dropped/stale/bad/reconnects, terrain cache and batch cost,
mirrored blocks, vehicles and proxies), MINECRAFT (fps, camera sync mode, control mode, overlay
state, player position in both frames, the last camera pose sent).

## Driving Minecraft from a terminal

`scripts\mc.ps1 "<command>" ...` writes to `%TEMP%\bngmc\mc_cmd.txt`; Minecraft runs each line once
per tick: `status`, `run <command>`, `look <yaw> <pitch>`, `turn`, `key forward 40`,
`cam drive|follow|off`, `screenshot`, `terrain reset`, `recall`, `markers`, `column`, `enter`,
`switch beamng|mc`, `click attack|use` (one mouse click), `hitboxes on|off`, `clearchat`,
`probe` (collision check against the nearest car), `hud on|off`, `quit`.

## Driving BeamNG from a terminal

`python bridge\bngdiag.py <command>`: `telemetry`, `bench`, `ping`, `debug "text"`, `camtest`,
`camfollow`, `ground`, `vehicles`, `push <m/s>`, `place x y z [fx fy fz]` (teleport and repair the
player's car), `level <name>`, `reload`, `reconnect`, `raw`.
`reload` re-reads the extension from disk after `scripts\install-beamng-mod.ps1`, no restart.

## Without the game

`python bridge\mock_beamng.py --port 47021` runs the real extension Lua under LuaJIT with a
**MOCK** engine (flat ground, a ramp, a wall, two cars). It catches Lua errors and protocol bugs;
it says nothing about BeamNG's behaviour. Point the tools at it with `BNGBRIDGE_PORT=47021`.

## Tests

```powershell
cd minecraft; .\gradlew test          # 52 tests: coordinates, link, column plan, damage, riders
scripts\test-handoff.ps1               # F4 switch end to end (needs Minecraft in front)
python bridge\walktest.py X Y Z yaw ticks   # Steve's feet vs BeamNG ground along a walk
```

## Recording a demo

`python bridge\demo.py record` plays scripted scenes in both games (intro, ramp walk, brick wall
crash, punch, mobs, TNT, roof ride, get in and drive, F9 stats) on gridmap_v2 while ffmpeg
records BeamNG's client area only: no title bar, taskbar, cursor or audio. `demo.py edit` cuts
the scenes, adds a short title that fades out (`--captions` for per-scene captions instead), a
faint watermark across the middle (`--watermark TEXT`) and strips metadata (a 1080p60 file and a
720p one under 10 MB for Discord); `demo.py review` writes contact sheets to check for anything private, since Windows
notifications drawn over the game would be captured too. `record --dry` rehearses without
recording, `--only wall,roof` picks scenes. The capture uses Intel Quick Sync because the laptop
panel is driven by the iGPU, where ddagrab's frames live (NVENC can't open them).

## Known problems and their causes

| Symptom | Cause | Fix |
|---|---|---|
| BeamNG crashes at startup | `-windowed` (0.39.4 bug, `parseArgs.lua:58`) | never pass it; the scripts don't |
| BeamNG at 30 fps when Minecraft has focus | background FPS limit | the extension holds it at 120 while connected (`-mccrossfps N`) and restores it |
| Settings ignored in the test folder | UTF-8 BOM in a settings JSON | write without BOM (`Set-BngSettings`) |
| "Enable online features?" covers the game | fresh user folder | `run-beamng.ps1` seeds `onlineFeatures`/`telemetry` = `disable` |
| Steve stops at a ramp | a column sampled from below read the ground under the ramp | fixed: multi-surface columns (`ColumnPlan`) |
| Overlay window vanishes | BeamNG minimised (0x0 at -32000) | fixed: never glue to a minimised window |
| "Getting in" does nothing | BeamNG window not found | found by title now, even minimised |
| Proxy jumps kilometres right after a spawn | BeamNG reports a one-frame 52 km/s velocity on spawn | speeds above 150 m/s aren't extrapolated |
| BeamNG uncapped (700+ fps) in the background, Minecraft starved | the background limit switched off entirely | fixed: held at 120 instead |
| Steve falls through a parked car's roof | the proxy box is re-fitted every tick and its top rises with body flex past his feet; Minecraft then treats him as inside it | fixed: `CarRide` snaps his feet to the roof |
| Steve thrown off a car that speeds up | `VehicleHits` hit its own rider; then the server's copy of the box, placed at other moments, pulled him back | fixed: riders aren't hit; the server's box is 5 cm lower. Verified to 13 m/s |
| A scripted car creeps backwards after stopping | holding the brake at rest makes BeamNG's automatic gearbox select reverse | stop, then hold with the parking brake (`demo.py` `Bng.stop`) |

## Crashes

1. BeamNG: `beamng.log` (search `|E|` and `FATAL`), crash reports in `<user folder>\temp\crashReports`.
   `scripts\stop-beamng.ps1` also closes a leftover crash-report dialog.
2. Minecraft: `run\crash-reports\`, `run\logs\latest.log`.
3. Decide which side failed by the last `[MCCROSS]` / `(bngbridge)` lines before the failure.
