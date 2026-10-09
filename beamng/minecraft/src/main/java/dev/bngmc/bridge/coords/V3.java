package dev.bngmc.bridge.coords;

/** Immutable 3-vector of doubles. Which frame it lives in is up to the caller (see {@link CrossoverCoords}). */
public record V3(double x, double y, double z) {
	public static final V3 ZERO = new V3(0, 0, 0);

	public V3 add(V3 o) {
		return new V3(x + o.x, y + o.y, z + o.z);
	}

	public V3 sub(V3 o) {
		return new V3(x - o.x, y - o.y, z - o.z);
	}

	public V3 scale(double s) {
		return new V3(x * s, y * s, z * s);
	}

	public double dot(V3 o) {
		return x * o.x + y * o.y + z * o.z;
	}

	public V3 cross(V3 o) {
		return new V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
	}

	public double length() {
		return Math.sqrt(dot(this));
	}

	/** Unit vector in the same direction; ZERO stays ZERO. */
	public V3 normalize() {
		double l = length();
		return l < 1e-12 ? ZERO : scale(1.0 / l);
	}

	public boolean approx(V3 o, double eps) {
		return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps && Math.abs(z - o.z) <= eps;
	}
}
