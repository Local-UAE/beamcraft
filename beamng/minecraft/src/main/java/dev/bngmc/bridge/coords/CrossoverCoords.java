package dev.bngmc.bridge.coords;

/**
 * The one authoritative conversion between BeamNG, the canonical crossover frame and
 * Minecraft. Nothing else in the project may swap axes, flip signs or rescale positions;
 * every conversion goes through here. The math is documented in beamng/docs/coordinates.md.
 *
 * <h2>Frames</h2>
 * <ul>
 *   <li><b>Canonical (CX)</b>: right-handed, Z up, metres. X = east, Y = north, Z = up. It is
 *   BeamNG's world frame (origin included), so BeamNG &lt;-&gt; canonical is the identity for
 *   positions and vectors. A camera at rest looks along +Y with +Z up and +X to its right.
 *   Quaternions differ: canonical uses Hamilton's convention, BeamNG's LuaQuat the inverse one
 *   (see {@link #beamngToCanonicalRotation}).</li>
 *   <li><b>Minecraft (MC)</b>: right-handed, Y up, 1 block = 1 m. X = east, Y = up,
 *   Z = south. Each BeamNG level owns a {@link Region} of the Minecraft world.</li>
 * </ul>
 *
 * <p>MC -&gt; CX is the proper rotation (x, y, z) -&gt; (x, -z, y) (determinant +1, no mirror),
 * plus the region offset for positions. Directions, velocities and angular velocities (which
 * are pseudo-vectors, unchanged by a proper rotation) use the rotation only.
 */
public final class CrossoverCoords {
	private CrossoverCoords() {
	}

	/** Minecraft distance between the origins of two levels' regions, along MC +X. */
	public static final double REGION_SPACING = 65536.0;

	/**
	 * Where canonical (0, 0, 0) of one BeamNG level sits in the Minecraft world, and how many
	 * Minecraft blocks one BeamNG metre is (scale). With the default region 0 the mapping is
	 * MC (x, y, z) = CX (x, z, -y): Minecraft's Y is BeamNG's altitude and block edges fall on whole
	 * BeamNG metres. A Minecraft-hosted world uses a scale above 1 so the cars look their size next to
	 * Minecraft's characters (BngWorld.HOST_REGION).
	 */
	public record Region(int index, double mcOriginX, double mcOriginY, double mcOriginZ, double scale) {
		public Region(int index, double mcOriginX, double mcOriginY, double mcOriginZ) {
			this(index, mcOriginX, mcOriginY, mcOriginZ, 1.0);
		}

		public static Region of(int index) {
			return new Region(index, index * REGION_SPACING, 0.0, 0.0);
		}

		/** True if a Minecraft X coordinate lies inside this region's slice of the world. */
		public boolean containsMcX(double mcX) {
			return Math.abs(mcX - mcOriginX) < REGION_SPACING / 2;
		}
	}

	// -- BeamNG <-> canonical ----------------------------------------------------------------------
	// Positions and vectors: identity by definition. Measured on 0.39.4 by the extension's self-test:
	// BeamNG's world is right-handed Z-up and its cameras look along +Y with +Z up.

	public static V3 beamngToCanonicalPosition(V3 p) {
		return p;
	}

	public static V3 canonicalToBeamngPosition(V3 p) {
		return p;
	}

	public static V3 beamngToCanonicalDirection(V3 d) {
		return d;
	}

	public static V3 canonicalToBeamngDirection(V3 d) {
		return d;
	}

	/**
	 * BeamNG's LuaQuat (x, y, z, w) rotates a vector the other way round from a Hamilton
	 * quaternion with the same components: {@code q * v} is {@code q* v q}. Measured on 0.39.4:
	 * {@code quatFromAxisAngle(Z, +90 deg) * (1,0,0) = (0,-1,0)} and
	 * {@code quatFromDir(east, up) = (0, 0, 0.7071, 0.7071)}, whose Hamilton equivalent is
	 * (0, 0, -0.7071, 0.7071). mathlib.lua says as much ("T3d's quats use -w"). So the
	 * canonical quaternion is the conjugate.
	 */
	public static Quat beamngToCanonicalRotation(Quat q) {
		return q.conjugate();
	}

	public static Quat canonicalToBeamngRotation(Quat q) {
		return q.conjugate();
	}

	// -- Minecraft <-> canonical ----------------------------------------------------------------------

	/** Rotation part: MC vector -> CX vector. */
	public static V3 minecraftToCanonicalDirection(V3 d) {
		return new V3(d.x(), -d.z(), d.y());
	}

	/** Rotation part: CX vector -> MC vector. */
	public static V3 canonicalToMinecraftDirection(V3 d) {
		return new V3(d.x(), d.z(), -d.y());
	}

	public static V3 minecraftToCanonicalPosition(V3 mc, Region r) {
		double s = r.scale();
		return minecraftToCanonicalDirection(new V3((mc.x() - r.mcOriginX()) / s, (mc.y() - r.mcOriginY()) / s, (mc.z() - r.mcOriginZ()) / s));
	}

	public static V3 canonicalToMinecraftPosition(V3 cx, Region r) {
		V3 d = canonicalToMinecraftDirection(cx);
		double s = r.scale();
		return new V3(d.x() * s + r.mcOriginX(), d.y() * s + r.mcOriginY(), d.z() * s + r.mcOriginZ());
	}

	/** A BeamNG velocity (m/s) as Minecraft blocks per second in this region. */
	public static V3 canonicalToMinecraftVelocity(V3 v, Region r) {
		return canonicalToMinecraftDirection(v).scale(r.scale());
	}

	/** Linear velocity, m/s both sides. (Minecraft entity motion is per tick: multiply by 20 first.) */
	public static V3 minecraftToCanonicalVelocity(V3 v) {
		return minecraftToCanonicalDirection(v);
	}

	public static V3 canonicalToMinecraftVelocity(V3 v) {
		return canonicalToMinecraftDirection(v);
	}

	/** Angular velocity, rad/s: a pseudo-vector, so a proper rotation maps it like any vector. */
	public static V3 minecraftToCanonicalAngularVelocity(V3 w) {
		return minecraftToCanonicalDirection(w);
	}

	public static V3 canonicalToMinecraftAngularVelocity(V3 w) {
		return canonicalToMinecraftDirection(w);
	}

	/** The fixed rotation taking MC axes to CX axes, as a quaternion (+90 degrees about X). */
	public static final Quat MC_TO_CX = Quat.fromAxes(
		minecraftToCanonicalDirection(new V3(1, 0, 0)),
		minecraftToCanonicalDirection(new V3(0, 1, 0)),
		minecraftToCanonicalDirection(new V3(0, 0, 1)));

	/**
	 * An orientation expressed in MC space (maps an object's local axes to MC world axes)
	 * re-expressed in CX space, keeping the object's own local axes: q' = R q.
	 */
	public static Quat minecraftToCanonicalRotation(Quat qMc) {
		return MC_TO_CX.mul(qMc).normalize();
	}

	public static Quat canonicalToMinecraftRotation(Quat qCx) {
		return MC_TO_CX.conjugate().mul(qCx).normalize();
	}

	// -- cameras --------------------------------------------------------------------------------------

	/**
	 * Minecraft's view direction for yaw/pitch in degrees, in MC space. Same formula as
	 * Entity.calculateViewVector: yaw 0 looks south (+Z), yaw 90 west (-X), pitch +90 down.
	 */
	public static V3 minecraftLookVector(double yawDeg, double pitchDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		double c = Math.cos(pitch);
		return new V3(-Math.sin(yaw) * c, -Math.sin(pitch), Math.cos(yaw) * c);
	}

	/**
	 * Canonical camera orientation: rotates the rest camera (right +X, forward +Y, up +Z) onto
	 * the given forward/up. Up is re-orthogonalised against forward.
	 */
	public static Quat cameraQuat(V3 forward, V3 up) {
		V3 f = forward.normalize();
		V3 r = f.cross(up).normalize();
		if (r.length() < 0.5) {
			// Looking straight up or down: any right vector perpendicular to forward works.
			r = f.cross(Math.abs(f.z()) < 0.9 ? new V3(0, 0, 1) : new V3(0, 1, 0)).normalize();
		}
		V3 u = r.cross(f);
		return Quat.fromAxes(r, f, u);
	}

	public static V3 cameraForward(Quat q) {
		return q.rotate(new V3(0, 1, 0));
	}

	public static V3 cameraUp(Quat q) {
		return q.rotate(new V3(0, 0, 1));
	}

	public static V3 cameraRight(Quat q) {
		return q.rotate(new V3(1, 0, 0));
	}

	/** Canonical camera quaternion for a Minecraft camera with yaw/pitch (degrees) and no roll. */
	public static Quat minecraftCameraToCanonical(double yawDeg, double pitchDeg) {
		V3 fwd = minecraftToCanonicalDirection(minecraftLookVector(yawDeg, pitchDeg));
		V3 up = minecraftToCanonicalDirection(minecraftLookVector(yawDeg, pitchDeg - 90.0));
		return cameraQuat(fwd, up);
	}

	/** Minecraft yaw/pitch (degrees) of a canonical camera, as {yaw, pitch}. Roll is dropped. */
	public static double[] canonicalCameraToMinecraft(Quat q) {
		V3 f = canonicalToMinecraftDirection(cameraForward(q)).normalize();
		double pitch = Math.toDegrees(-Math.asin(Math.max(-1.0, Math.min(1.0, f.y()))));
		double yaw = Math.toDegrees(Math.atan2(-f.x(), f.z()));
		return new double[] {yaw, pitch};
	}

	/**
	 * Compass heading of a canonical direction: degrees clockwise from north (+Y) seen from
	 * above, in [0, 360). Minecraft yaw = heading + 180 (mod 360).
	 */
	public static double heading(V3 d) {
		double h = Math.toDegrees(Math.atan2(d.x(), d.y()));
		return h < 0 ? h + 360.0 : h;
	}

	// -- field of view --------------------------------------------------------------------------------

	public static double horizontalFovDeg(double verticalFovDeg, double aspect) {
		return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(verticalFovDeg) / 2) * aspect));
	}

	public static double verticalFovDeg(double horizontalFovDeg, double aspect) {
		return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(horizontalFovDeg) / 2) / aspect));
	}
}
