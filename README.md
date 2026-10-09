# BeamCraft: BeamNG cars in Minecraft

BeamCraft now includes a real, two-game crossover: BeamNG.drive runs vehicle physics and damage,
while a Fabric client renders the exported BeamNG car in Minecraft and deforms its model from live
physics-node updates. The BeamNG side also receives Minecraft ground data. The implementation is in
[`beamng/`](beamng/), based on the MIT-licensed
[JASGENIUS/minecraft-beamng](https://github.com/JASGENIUS/minecraft-beamng) project.

## Run it on this PC

1. Close any running copy of BeamNG.drive.
2. Double-click [`play.bat`](play.bat) (or run `play.bat host`
   in PowerShell) and choose **1** for BeamNG cars in a flat Minecraft world.
3. If prompted, allow the launcher to install Eclipse Temurin JDK 21 through `winget`.
4. Wait for BeamNG and Minecraft to finish starting. The first run downloads Minecraft/Fabric and
   the pinned optional mods; later starts reuse them.
5. In Minecraft, walk up to the car and right-click with an empty hand to drive. Use **W/A/S/D**,
   **Space** for handbrake, **B** for the car picker and **Shift** to get out.

Only Minecraft needs keyboard focus to drive; BeamNG stays running in the background as the
physics engine. For a Logitech G29/G920/G923, the mod reads wheel and pedal axes through GLFW.
Connect the wheel before starting Minecraft. The G923 Driving Force Shifter selects reverse and
gears 1–6 in realistic gearbox mode, and its clutch pedal is passed through to BeamNG; the right
and left paddles and keyboard **X/Z** remain shift-up/down controls. Press **M** to toggle
arcade/realistic gearbox mode. The G923 H-shifter and clutch have been confirmed working in-game;
wheel controls remain experimental. Force feedback is not implemented. The rendered steering
wheel remains static. Press **F9** while seated to check wheel detection, normalized inputs and
the selected H-pattern gear.

The launcher runs BeamNG with a separate root-level `bng-userfolder` and Minecraft from
`beamng/minecraft/run`; it does not install files into the normal BeamNG or Minecraft profiles.
Use a new world in this Minecraft 1.21.1 instance. Worlds from newer Minecraft versions cannot be
opened safely in older versions.

For a normal Minecraft map, choose mode **2** or run `play.bat terrain`. For detailed controls,
custom maps, car mods, troubleshooting and the alternate mode, see
[`beamng/README.md`](beamng/README.md).

## Build and tests

With a JDK 21 installed:

```powershell
Push-Location .\beamng\minecraft
.\gradlew.bat test
Pop-Location
```

The Java test suite covers the bridge, terrain and native car model/material code. The Lua-side
tests and mock bridge are documented in [`beamng/README.md`](beamng/README.md).

## Status and limitations

The mod builds and its Java tests pass. Car rendering, driving, crash deformation and G923
H-shifter/clutch operation have been tested with BeamNG.drive 0.39.4 and Minecraft 1.21.1.
BeamCraft is a Windows-only experience for one player on one PC.

No BeamNG or Minecraft game assets are included. The game exporter reads car data from the local
BeamNG installation at runtime. You must own and install both BeamNG.drive and Minecraft to play.
See [`beamng/THIRD_PARTY_NOTICES.md`](beamng/THIRD_PARTY_NOTICES.md)
and [`beamng/LICENSE`](beamng/LICENSE) for the imported code's
MIT license and attribution. The original BeamCraft prototype's root [`LICENSE`](LICENSE)
is CC0; that does not replace or remove the separate notices and license for the imported implementation.

This is a fan project and is not affiliated with BeamNG GmbH, Mojang or Microsoft. Releases are
distributed through GitHub.
