# Minecraft × BeamNG.drive: developer notes

The player-facing README is the one in the repo's root (`../README.md`). This one is for working
on the crossover: what runs where, the scripts, the tests and the docs.

It began as a Windows port of
[minecraft-crossover-bridge](https://github.com/justbustin/minecraft-crossover-bridge) (Minecraft
inside Monster Hunter: World and Elden Ring on macOS + CrossOver) and now runs both ways:

- **BeamNG cars in Minecraft** (`host` and `terrain` modes, `docs/minecraft-host.md`). Minecraft
  is the game you see. BeamNG runs the car physics, damage and engine in the background, with its
  own world render switched off. Minecraft draws the real BeamNG car itself: BeamNG's glTF exporter
  writes the car's model into BeamNG's user folder, and Minecraft bends the mesh every frame from
  BeamNG's node positions, so dents and crumples show. Minecraft's ground goes to BeamNG as boxes
  (flat world) or as a BeamNG terrain built from the Minecraft surface (normal world).
- **Minecraft inside BeamNG** (`bridge` mode, the original direction). BeamNG's camera follows
  Minecraft's, BeamNG's collision becomes invisible Minecraft blocks, BeamNG cars are solid boxes
  in Minecraft, and Minecraft's window is laid over BeamNG's.

Tested with BeamNG.drive 0.39.4.0 (build 20972) and Minecraft 1.21.1 + Fabric on Windows 11.

## Run it by hand

`play.bat` in the root does all of this in order. Separately:

```powershell
cd beamng
scripts\run-beamng.ps1 -Level smallgrid -Wait   # installs the BeamNG mod into the test profile, starts BeamNG
scripts\run-minecraft.ps1 -Mode host            # "BeamNG Host": flat world, BeamNG cars in it
scripts\run-minecraft.ps1 -Mode terrain         # "BeamNG Terrain": normal generation
scripts\run-minecraft.ps1 -Mode terrain -World my-map   # a save in minecraft\run\saves named "... [BeamNG]"
scripts\run-minecraft.ps1                       # bridge mode: Minecraft inside BeamNG
scripts\diagnose.ps1                            # health check of both games
```

- **Test profile.** BeamNG runs with `-userpath bng-userfolder` (in the repo, git-ignored), so the
  crossover never touches your normal BeamNG mods, settings or BeamMP setup. `BNG_USERPATH`
  overrides it; `-Player` on the install and run scripts uses your normal profile instead.
- **Paths are detected**, never hardcoded (`scripts/common.ps1`): Steam libraries, the BeamNG
  install, a JDK 21. `BNG_INSTALL` and `BNG_JAVA_HOME` override them.
- **Minecraft** runs from Fabric's development launcher (`gradlew runClient`) as an offline player.
  `minecraft\run\bngbridge-local.properties` (git-ignored) can hold `username=YourName` and
  `skin=YourName` (whose public skin to wear; fetched from Mojang's public profile API).
- **Extra mods.** Gradle adds Sodium and Iris (shader packs). `run-minecraft.ps1` puts Controlify
  and YetAnotherConfigLib into `minecraft\run\mods` and a shader pack into
  `minecraft\run\shaderpacks`, pinned Modrinth versions checked against Modrinth's SHA-512.
  `-NoShaderMods` and `-NoControllerMods` leave them out.
- **Your own car mods.** `python beamng\scripts\mod_picker.py` opens a page on this PC
  (http://127.0.0.1:47088) listing the car mods in your normal BeamNG profile. "Add to Minecraft"
  copies one into the test profile; BeamNG loads it while running and the car picker (B) lists it.
  Your normal profile is only read.

## Host-mode driving controls

In `host` and `terrain` modes, Minecraft must have keyboard focus to receive keyboard driving
input; BeamNG remains in the background and provides vehicle physics. Walk up to the car and
right-click it with an empty hand to enter, then use W/A/S/D, Space and Shift. F3+P toggles
Minecraft's "Pause on lost focus" option if you want the world to keep ticking while switching to
BeamNG, but input is delivered only to the currently focused game.

Logitech G29/G920/G923 steering and pedal input is supported experimentally through GLFW's raw
joystick axes. Connect the wheel before launching Minecraft. For the G923, the Windows axis order
is steering, gas, brake, clutch. The clutch pedal is sent to BeamNG; the connected Logitech
Driving Force Shifter selects reverse and gears 1–6, entering realistic gearbox mode through
BeamNG's native gear-selection action. The right and left paddles and keyboard X/Z remain
sequential shift controls; M toggles arcade/realistic mode. F9 shows raw axes, normalized inputs
and the selected H-pattern gear. Force feedback and other wheel buttons are not implemented.

## Layout

```
beamng/
  beamng-mod/   BeamNG Lua extension (installed as mods/unpacked/mccrossover in the test profile)
  minecraft/    Fabric mod (Java 21, Loom), src/main = both sides, src/client = rendering and input
  bridge/       Python: diagnostics (bngdiag, diagnose, walktest), a mock BeamNG, Lua unit tests,
                demo recorders (demo.py, hostdemo.py)
  scripts/      PowerShell: play, run, install, logs, screenshots, the mod picker
  docs/         research, architecture, protocol, coordinates, BeamNG API notes, roadmap,
                debugging, and the cars-in-Minecraft build log (minecraft-host.md)
```

## Tests

```powershell
cd beamng\minecraft; .\gradlew.bat test           # Java: terrain, posts, coordinates, the link, car models and materials
pip install lupa; python beamng\bridge\test_lua.py # Lua modules under LuaJIT: collision schedule, ground, exports, latches, terrain
python beamng\bridge\mock_beamng.py               # a fake BeamNG on the bridge port, for Minecraft without BeamNG
python beamng\bridge\bngdiag.py bench 8           # the real BeamNG: state rate, RTT, drops
```

Don't run Gradle in a checkout whose Minecraft is running: build in a second worktree and switch
when Minecraft is closed.

## Docs

| File | What's in it |
|---|---|
| `docs/research.md` | How upstream works, and what BeamNG offers to build this on |
| `docs/architecture.md` | The processes, threads and who owns what |
| `docs/protocol.md` | Every message on the UDP link |
| `docs/coordinates.md` | BeamNG metres to Minecraft blocks and back (scale 1.4 in the host worlds) |
| `docs/beamng-api-notes.md` | Each BeamNG call used, with the game's own Lua file and line it was read from |
| `docs/minecraft-host.md` | Cars in Minecraft: design, then the build log with what was proven and how |
| `docs/roadmap.md` | The stages of the original direction |
| `docs/debugging.md` | Where the logs are, health checks, dev commands |

## Caveats

- Single player only. Never connect a modded game to BeamMP servers.
- While Minecraft is connected the extension holds BeamNG's background frame limit at 120 so
  BeamNG keeps running smoothly behind Minecraft (`-mccrossfps N` changes it) and restores your
  setting afterwards.
- Fan project, not affiliated with Mojang, Microsoft or BeamNG GmbH. No game files are included.

Licence: MIT (`LICENSE`); see `THIRD_PARTY_NOTICES.md`.
