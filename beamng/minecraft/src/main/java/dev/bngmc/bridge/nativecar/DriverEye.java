package dev.bngmc.bridge.nativecar;

/**
 * Where the driver's eye is and where it looks, from the car's nodes, as BeamNG's onboard camera
 * works it out (lua/ge/extensions/core/cameraModes/onboard.lua:53-110): the eye sits on the driver
 * camera's node; it looks along ref - back; "left" is ref - left and up is -(forward x left), unless
 * the camera names its own up and back nodes. Nodes in any one consistent frame (here metres,
 * Minecraft axes, around the car's position). Pure Java, unit-tested.
 */
public final class DriverEye {
	private DriverEye() {
	}

	/** The eye's position (same frame as the nodes) and the car's forward and up there (unit). */
	public record Eye(float[] pos, float[] forward, float[] up) {
		/** Minecraft yaw of forward: 0 looking south (+z), 90 west (-x). */
		public float yaw() {
			return (float) Math.toDegrees(Math.atan2(-forward[0], forward[2]));
		}

		/** Minecraft pitch of forward: positive looking down. */
		public float pitch() {
			return (float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, forward[1]))));
		}
	}

	/** The eye for these node positions (3 per node), or null if the nodes it needs are missing or degenerate. */
	public static Eye of(NativeExport.DriverView v, float[] nodes) {
		int n = nodes.length / 3;
		int eye = v.eyeNode();
		if (!ok(eye, n)) {
			return null;
		}
		float[] dir, left, up;
		if (ok(v.idUp(), n) && ok(v.idBack(), n)) {
			float[] ref = node(nodes, ok(v.idRef(), n) ? v.idRef() : eye);
			dir = normalize(sub(ref, node(nodes, v.idBack())));
			float[] camUp = sub(node(nodes, v.idUp()), ref);
			left = normalize(cross(dir, camUp));
			up = normalize(cross(left, dir));
		} else {
			if (!ok(v.ref(), n) || !ok(v.left(), n) || !ok(v.back(), n)) {
				return null;
			}
			float[] ref = node(nodes, v.ref());
			dir = normalize(sub(ref, node(nodes, v.back())));
			left = normalize(sub(ref, node(nodes, v.left())));
			up = normalize(scale(cross(dir, left), -1));
		}
		if (dir == null || left == null || up == null) {
			return null;
		}
		float[] p = node(nodes, eye);
		float[] o = v.offset();
		for (int k = 0; k < 3; k++) {
			p[k] += dir[k] * o[0] - left[k] * o[1] + up[k] * o[2];
		}
		return new Eye(p, dir, up);
	}

	private static boolean ok(int i, int n) {
		return i >= 0 && i < n;
	}

	private static float[] node(float[] nodes, int i) {
		return new float[] {nodes[i * 3], nodes[i * 3 + 1], nodes[i * 3 + 2]};
	}

	private static float[] sub(float[] a, float[] b) {
		return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static float[] scale(float[] a, float s) {
		return a == null ? null : new float[] {a[0] * s, a[1] * s, a[2] * s};
	}

	private static float[] cross(float[] a, float[] b) {
		return a == null || b == null ? null : new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static float[] normalize(float[] a) {
		if (a == null) {
			return null;
		}
		float l = (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
		return l < 1e-6F ? null : new float[] {a[0] / l, a[1] / l, a[2] / l};
	}
}
