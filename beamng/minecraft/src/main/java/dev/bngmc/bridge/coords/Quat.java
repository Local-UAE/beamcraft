package dev.bngmc.bridge.coords;

/**
 * Immutable unit quaternion (x, y, z, w), Hamilton convention: {@code rotate(v) = q v q*},
 * composition {@code a.mul(b)} applies b first, then a. Right-handed: a positive angle about
 * an axis turns counter-clockwise when the axis points at the viewer.
 */
public record Quat(double x, double y, double z, double w) {
	public static final Quat IDENTITY = new Quat(0, 0, 0, 1);

	public static Quat axisAngle(V3 axis, double radians) {
		V3 a = axis.normalize();
		double s = Math.sin(radians / 2);
		return new Quat(a.x() * s, a.y() * s, a.z() * s, Math.cos(radians / 2));
	}

	/**
	 * Rotation whose matrix columns are the images of the frame's X, Y and Z axes. The three
	 * vectors must be orthonormal and right-handed (xAxis x yAxis = zAxis).
	 */
	public static Quat fromAxes(V3 xAxis, V3 yAxis, V3 zAxis) {
		double m00 = xAxis.x(), m10 = xAxis.y(), m20 = xAxis.z();
		double m01 = yAxis.x(), m11 = yAxis.y(), m21 = yAxis.z();
		double m02 = zAxis.x(), m12 = zAxis.y(), m22 = zAxis.z();
		double trace = m00 + m11 + m22;
		double qx, qy, qz, qw;
		if (trace > 0) {
			double s = Math.sqrt(trace + 1.0) * 2;
			qw = 0.25 * s;
			qx = (m21 - m12) / s;
			qy = (m02 - m20) / s;
			qz = (m10 - m01) / s;
		} else if (m00 > m11 && m00 > m22) {
			double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2;
			qw = (m21 - m12) / s;
			qx = 0.25 * s;
			qy = (m01 + m10) / s;
			qz = (m02 + m20) / s;
		} else if (m11 > m22) {
			double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2;
			qw = (m02 - m20) / s;
			qx = (m01 + m10) / s;
			qy = 0.25 * s;
			qz = (m12 + m21) / s;
		} else {
			double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2;
			qw = (m10 - m01) / s;
			qx = (m02 + m20) / s;
			qy = (m12 + m21) / s;
			qz = 0.25 * s;
		}
		return new Quat(qx, qy, qz, qw).normalize();
	}

	public Quat normalize() {
		double n = Math.sqrt(x * x + y * y + z * z + w * w);
		return n < 1e-12 ? IDENTITY : new Quat(x / n, y / n, z / n, w / n);
	}

	public Quat conjugate() {
		return new Quat(-x, -y, -z, w);
	}

	public Quat mul(Quat o) {
		return new Quat(
			w * o.x + x * o.w + y * o.z - z * o.y,
			w * o.y - x * o.z + y * o.w + z * o.x,
			w * o.z + x * o.y - y * o.x + z * o.w,
			w * o.w - x * o.x - y * o.y - z * o.z);
	}

	public V3 rotate(V3 v) {
		// v' = v + 2w (u x v) + 2 u x (u x v), u = (x, y, z)
		V3 u = new V3(x, y, z);
		V3 t = u.cross(v).scale(2);
		return v.add(t.scale(w)).add(u.cross(t));
	}

	/** Same rotation (q and -q are equal rotations). */
	public boolean sameRotation(Quat o, double eps) {
		double d = Math.abs(x * o.x + y * o.y + z * o.z + w * o.w);
		return d >= 1 - eps;
	}
}
