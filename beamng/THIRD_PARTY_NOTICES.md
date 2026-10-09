# Third-party notices

## minecraft-crossover-bridge (upstream)

Parts of this BeamNG port are adapted from https://github.com/justbustin/minecraft-crossover-bridge
(MIT License, Copyright (c) 2026 justbustin). The licence text and copyright notices are in
`LICENSE`. Files that started as copies of upstream code say so in their header comment.

## Fabric, Minecraft

The Minecraft mod builds against Fabric Loader, Fabric API and Loom (Apache-2.0) and Minecraft
(Mojang's EULA; not redistributed). Nothing from Minecraft or BeamNG.drive is included in this
repository.

## Mods downloaded at run time

`scripts/run-minecraft.ps1` and Gradle download these on your PC from Modrinth (pinned versions,
checked against Modrinth's SHA-512 where the script fetches them). None of them is in this
repository; each keeps its own licence:

- Sodium and Iris (shader support, through Gradle)
- Controlify and YetAnotherConfigLib (controller on foot)
- Complementary Reimagined (a shader pack to start with)

## BeamNG.drive

No BeamNG files are copied into this repository. The Lua mod calls BeamNG's public Lua API at
runtime; API usage was learned by reading the installed game's Lua source, and the call sites
are cited in `docs/beamng-api-notes.md` without copying the code.
