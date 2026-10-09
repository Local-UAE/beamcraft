# Coordinates

All conversions live in one file: `minecraft/src/main/java/dev/bngmc/bridge/coords/CrossoverCoords.java`,
with tests in `minecraft/src/test/java/dev/bngmc/bridge/coords/CrossoverCoordsTest.java`. The Lua
side never converts to Minecraft space; it speaks canonical coordinates only.

## Frames

| Frame | Handedness | Up | Axes | Units |
|---|---|---|---|---|
| BeamNG world | right | +Z | X east, Y north, Z up | metres |
| Canonical (CX) | right | +Z | identical to BeamNG world, same origin | metres |
| Minecraft | right | +Y | X east, Y up, Z south | blocks (1 block = 1 m) |

Canonical *is* BeamNG's world frame. That choice keeps every message from BeamNG in its native
units and puts the single axis change on the Minecraft side, where it is tested.

The BeamNG row was checked at runtime by the Lua extension's self-test and by screenshots
(smallgrid's painted +X arrow), not assumed. One difference is not about axes: BeamNG's `LuaQuat`
rotates vectors the inverse way from a Hamilton quaternion with the same components (measured:
`quatFromAxisAngle(Z, +90°) * X = -Y`), so `CrossoverCoords.beamngToCanonicalRotation` is a
conjugate. Positions and vectors need no conversion.

## Minecraft ↔ canonical

Rotation part, used for directions, velocities and angular velocities:

```
CX = R · MC        R = | 1  0  0 |        MC = Rᵀ · CX
                       | 0  0 -1 |
                       | 0  1  0 |
(x, y, z)_MC  ->  (x, -z, y)_CX
```

`det R = +1`, so it is a proper rotation (+90° about X), not a mirror. Handedness is kept, and
angular velocity, a pseudo-vector, maps with the same matrix as ordinary vectors.

Positions add a per-level offset. Each BeamNG level owns a "region" of the Minecraft world so
blocks placed on different maps never overlap:

```
MC = Rᵀ · CX + O_r          O_r = (r · 65536, 0, 0)
CX = R · (MC - O_r)
```

With region 0, `MC = (x, z, -y)` for a BeamNG point `(x, y, z)`. Minecraft's Y is BeamNG's
altitude, and block edges fall on whole BeamNG metres, which makes logs easy to read. The
Minecraft overworld is raised to Y -2032..2031 (datapack `dimension_type`, as in upstream's
Elden Ring port), enough for every shipped BeamNG level.

## Scale

1 block = 1 m. Steve is 1.8 m tall with eyes at 1.62 m, a BeamNG pickup is about 5.4 m long,
and a road lane is about 3.5 m. Those proportions look right next to each other, so no scale
factor is used. If that changes, the scale goes into `Region` and nowhere else.

## Orientation

Internally orientations are quaternions (x, y, z, w), Hamilton convention, `v' = q v q*`.
Euler angles appear only at the Minecraft boundary, because Minecraft cameras and entities
store yaw/pitch.

### Cameras

A canonical camera at rest looks along +Y with +Z up and +X to its right. Its orientation `q`
rotates that rest frame onto the real one:

```
forward = q · (0, 1, 0)     up = q · (0, 0, 1)     right = q · (1, 0, 0)
```

`cameraQuat(forward, up)` builds `q` from the basis
`[right | forward | up]`, `right = normalize(forward × up)`, `up' = right × forward`.

Minecraft's look vector for yaw ψ and pitch θ (degrees, θ > 0 looks down):

```
f_MC = (-sin ψ cos θ, -sin θ, cos ψ cos θ)
f_CX = (-sin ψ cos θ, -cos ψ cos θ, -sin θ)
```

| Minecraft yaw | Looks | Canonical forward |
|---|---|---|
| 0 | south | (0, -1, 0) |
| 90 | west | (-1, 0, 0) |
| 180 | north | (0, 1, 0) |
| -90 | east | (1, 0, 0) |

Compass heading (degrees clockwise from north) = Minecraft yaw + 180.

The camera message sends `forward` and `up` in canonical space rather than a quaternion. BeamNG
builds its own quaternion from them with its own math library, so a convention mismatch in
BeamNG's quaternion type can't creep in.

### Objects

For an object whose orientation maps its own local axes to world axes, the local axes stay the
same and only the world frame changes:

```
q_CX = q_R · q_MC        q_R = MC_TO_CX = rotation of +90° about X
```

(This is composition, not the similarity transform `q_R q q_R*`, which would re-express the
local axes too.) BeamNG vehicles face -Y in their local frame (JBeam convention), so a vehicle's
forward is `q_veh · (0, -1, 0)`; see `beamng-api-notes.md`.

## Field of view

Minecraft's FOV option and `GameRenderer.getFov` are vertical, in degrees. The protocol sends
vertical FOV (`fovV`). Conversion to horizontal for a given aspect ratio `a = width / height`:

```
h = 2 · atan(tan(v / 2) · a)
v = 2 · atan(tan(h / 2) / a)
```

70° vertical at 16:9 is 102.45° horizontal. Which convention BeamNG's camera FOV setter uses is
recorded in `beamng-api-notes.md` together with how it was measured.

## Velocity units

BeamNG velocities are m/s. Minecraft entity motion is blocks per tick (20 ticks/s), so multiply
by 20 before converting, and divide by 20 after converting back.

## Tests

`CrossoverCoordsTest` covers: origin, each axis (+X, +Y, +Z) both ways, proper rotation
(handedness), region offsets and containment, position round trips in two regions, velocity and
angular velocity conversion, the four compass yaws plus straight up/down, the rest camera,
90° turns against axis-angle quaternions, yaw/pitch → quaternion → yaw/pitch round trips with no
roll, heading = yaw + 180, object orientation conversion keeping local axes, and FOV conversion.
Run them with `gradlew test` in `minecraft/`.
