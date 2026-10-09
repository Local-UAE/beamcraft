package dev.bngmc.bridge.link;

import dev.bngmc.bridge.coords.V3;

import java.util.Arrays;

/**
 * The last few VMESH snapshots of one car, for drawing it smoothly. BeamNG sends a snapshot every
 * frame it renders (~120 Hz, unevenly spaced) while Minecraft draws at its own rate; drawing the
 * newest snapshot as is makes the car judder. Instead each Minecraft frame is drawn at a moment a
 * little in the past (on BeamNG's clock) and the car's pose is blended between the two snapshots
 * around it, as networked games do. Written by the link thread, read by the render thread: the
 * snapshot array is replaced, never changed. Pure Java, unit-tested.
 */
public final class MeshTimeline {
	static final int KEEP = 8;

	private volatile VehicleMeshes.Mesh[] snaps = new VehicleMeshes.Mesh[0];

	public synchronized void add(VehicleMeshes.Mesh m) {
		VehicleMeshes.Mesh[] s = snaps;
		if (s.length > 0 && m.ts() <= s[s.length - 1].ts()) {
			return;   // late or repeated: what's already here is newer
		}
		VehicleMeshes.Mesh[] n = Arrays.copyOfRange(s, Math.max(0, s.length + 1 - KEEP), s.length + 1);
		n[n.length - 1] = m;
		snaps = n;
	}

	public int size() {
		return snaps.length;
	}

	public VehicleMeshes.Mesh newest() {
		VehicleMeshes.Mesh[] s = snaps;
		return s.length == 0 ? null : s[s.length - 1];
	}

	/** The pose at BeamNG time {@code senderMs}: blended, or the nearest end when outside the window. */
	public Pose sample(double senderMs) {
		VehicleMeshes.Mesh[] s = snaps;
		if (s.length == 0) {
			return null;
		}
		VehicleMeshes.Mesh last = s[s.length - 1];
		if (senderMs >= last.ts() || s.length == 1) {
			return new Pose(last, last, 0);
		}
		if (senderMs <= s[0].ts()) {
			return new Pose(s[0], s[0], 0);
		}
		for (int i = s.length - 2; i >= 0; i--) {
			VehicleMeshes.Mesh a = s[i], b = s[i + 1];
			if (a.ts() <= senderMs) {
				if (a.p().length != b.p().length) {
					return new Pose(b, b, 0);   // the car changed shape: nothing to blend
				}
				double span = b.ts() - a.ts();
				return new Pose(a, b, span <= 0 ? 1 : (senderMs - a.ts()) / span);
			}
		}
		return new Pose(s[0], s[0], 0);
	}

	/** A car pose between snapshots a and b (alpha 0 = a, 1 = b). */
	public record Pose(VehicleMeshes.Mesh a, VehicleMeshes.Mesh b, double alpha) {
		public int nodeCount() {
			return b.nodes();
		}

		public VehicleMeshes.Mesh shape() {
			return b;
		}

		public V3 pos() {
			return lerp(a.pos(), b.pos(), alpha);
		}

		public V3 fwd() {
			return direction(a.fwd(), b.fwd());
		}

		public V3 up() {
			return direction(a.up(), b.up());
		}

		private V3 direction(V3 da, V3 db) {
			if (da == null || db == null) {
				return db != null ? db : da;
			}
			V3 d = lerp(da, db, alpha);
			return d.length() > 1e-9 ? d.normalize() : db;
		}

		/** Node offsets from {@link #pos()}, metres, Minecraft axes (canonical (x, y, z) -> (x, z, -y)). */
		public void nodesMinecraftAxes(float[] out) {
			int[] ca = a.p(), cb = b.p();
			float t = (float) alpha, u = 1 - t;
			float ua = u / a.q(), tb = t / b.q();   // each snapshot in its own units
			int n = Math.min(Math.min(ca.length, cb.length), out.length);
			for (int i = 0; i + 2 < n; i += 3) {
				out[i] = ca[i] * ua + cb[i] * tb;
				out[i + 1] = ca[i + 2] * ua + cb[i + 2] * tb;
				out[i + 2] = -(ca[i + 1] * ua + cb[i + 1] * tb);
			}
		}

		private static V3 lerp(V3 p, V3 q, double t) {
			return new V3(p.x() + (q.x() - p.x()) * t, p.y() + (q.y() - p.y()) * t, p.z() + (q.z() - p.z()) * t);
		}
	}

	/**
	 * Maps Minecraft's clock to BeamNG's: the offset is the smallest (arrival - send) time seen,
	 * i.e. the fastest delivery, creeping up slowly so clock drift is followed.
	 */
	public static final class Clock {
		private static final double DRIFT = 0.002;
		private double offset = Double.NaN;

		public synchronized void observe(double localMs, double senderMs) {
			double d = localMs - senderMs;
			if (Double.isNaN(offset) || d < offset) {
				offset = d;
			} else {
				offset += (d - offset) * DRIFT;
			}
		}

		public synchronized double offsetMs() {
			return offset;
		}

		public synchronized double senderTimeAt(double localMs) {
			return Double.isNaN(offset) ? Double.NaN : localMs - offset;
		}
	}
}
