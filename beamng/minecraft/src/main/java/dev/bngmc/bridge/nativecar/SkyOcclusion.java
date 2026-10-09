package dev.bngmc.bridge.nativecar;

import java.util.BitSet;
import java.util.List;
import java.util.stream.IntStream;

/**
 * How much open sky each vertex of a car sees, baked once when the car loads: the inside of the
 * cabin, the wheel wells and the gaps between panels get little, the roof all of it. BeamNG darkens
 * those places with shadows and screen-space ambient occlusion every frame; this is the cheap
 * stand-in (Jas: the interior was "way too bright"). The car's surfaces are drawn into a voxel grid
 * (glass left out: light comes in through the windows), and from each vertex rays go out over the
 * half of the sky its normal faces, cosine-weighted; the share that gets out of the car within
 * MAX_DISTANCE is its visibility, 0-1. Pure Java, unit-tested.
 */
public final class SkyOcclusion {
	private SkyOcclusion() {
	}

	static final float CELL = 0.035F;          // m
	static final float MAX_DISTANCE = 1.2F;     // m: further away than this, a surface doesn't shade
	static final int RAYS = 16;

	/**
	 * @param positions vertices, 3 per vertex (metres)
	 * @param normals   their normals
	 * @param occluders triangles (indices) that block light: everything but glass
	 * @return visibility per vertex, 0 (enclosed) to 1 (open sky)
	 */
	public static float[] bake(float[] positions, float[] normals, List<int[]> occluders) {
		int nv = positions.length / 3;
		float[] vis = new float[nv];
		if (nv == 0) {
			return vis;
		}
		Grid g = Grid.around(positions);
		for (int[] tris : occluders) {
			for (int i = 0; i + 2 < tris.length; i += 3) {
				g.triangle(positions, tris[i], tris[i + 1], tris[i + 2]);
			}
		}
		float[][] dirs = hemisphere();
		IntStream.range(0, nv).parallel().forEach(v -> vis[v] = visibility(g, positions, normals, v, dirs));
		return vis;
	}

	private static float visibility(Grid g, float[] p, float[] n, int v, float[][] dirs) {
		float nx = n[v * 3], ny = n[v * 3 + 1], nz = n[v * 3 + 2];
		float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (l < 1e-6F) {
			return 1F;
		}
		nx /= l;
		ny /= l;
		nz /= l;
		// a frame around the normal: t, b, n
		float tx, ty, tz;
		if (Math.abs(nx) < 0.9F) {
			tx = 0;
			ty = nz;
			tz = -ny;
		} else {
			tx = -nz;
			ty = 0;
			tz = nx;
		}
		float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		tx /= tl;
		ty /= tl;
		tz /= tl;
		float bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
		// start a little off the surface, so the ray doesn't hit the cells of its own triangle
		float ox = p[v * 3] + nx * CELL * 1.8F, oy = p[v * 3 + 1] + ny * CELL * 1.8F, oz = p[v * 3 + 2] + nz * CELL * 1.8F;
		int open = 0;
		for (float[] d : dirs) {
			float dx = tx * d[0] + bx * d[1] + nx * d[2], dy = ty * d[0] + by * d[1] + ny * d[2], dz = tz * d[0] + bz * d[1] + nz * d[2];
			if (!g.blocked(ox, oy, oz, dx, dy, dz, MAX_DISTANCE)) {
				open++;
			}
		}
		return open / (float) dirs.length;
	}

	/** RAYS directions over the +z half of the sphere, cosine-weighted (more of them near the normal). */
	static float[][] hemisphere() {
		float[][] out = new float[RAYS][];
		double golden = Math.PI * (3 - Math.sqrt(5));
		for (int i = 0; i < RAYS; i++) {
			double u = (i + 0.5) / RAYS;          // area-uniform on the disc, lifted: cosine-weighted
			double r = Math.sqrt(u), phi = i * golden;
			double x = r * Math.cos(phi), y = r * Math.sin(phi), z = Math.sqrt(Math.max(0, 1 - u));
			out[i] = new float[] {(float) x, (float) y, (float) z};
		}
		return out;
	}

	/** Occupied cells of CELL size over the car's bounds. */
	static final class Grid {
		final float x0, y0, z0;
		final int nx, ny, nz;
		final BitSet cells;

		private Grid(float x0, float y0, float z0, int nx, int ny, int nz) {
			this.x0 = x0;
			this.y0 = y0;
			this.z0 = z0;
			this.nx = nx;
			this.ny = ny;
			this.nz = nz;
			this.cells = new BitSet(nx * ny * nz);
		}

		static Grid around(float[] p) {
			float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (int i = 0; i + 2 < p.length; i += 3) {
				for (int k = 0; k < 3; k++) {
					lo[k] = Math.min(lo[k], p[i + k]);
					hi[k] = Math.max(hi[k], p[i + k]);
				}
			}
			int[] n = new int[3];
			for (int k = 0; k < 3; k++) {
				lo[k] -= CELL * 2;
				n[k] = Math.max(1, Math.min(1024, (int) Math.ceil((hi[k] + CELL * 2 - lo[k]) / CELL)));
			}
			return new Grid(lo[0], lo[1], lo[2], n[0], n[1], n[2]);
		}

		private int index(float x, float y, float z) {
			int i = (int) Math.floor((x - x0) / CELL), j = (int) Math.floor((y - y0) / CELL), k = (int) Math.floor((z - z0) / CELL);
			if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) {
				return -1;
			}
			return (k * ny + j) * nx + i;
		}

		void mark(float x, float y, float z) {
			int i = index(x, y, z);
			if (i >= 0) {
				cells.set(i);
			}
		}

		/** Marks the cells a triangle passes through (sampled at half a cell). */
		void triangle(float[] p, int a, int b, int c) {
			int np = p.length / 3;
			if (a < 0 || b < 0 || c < 0 || a >= np || b >= np || c >= np) {
				return;
			}
			float ax = p[a * 3], ay = p[a * 3 + 1], az = p[a * 3 + 2];
			float ux = p[b * 3] - ax, uy = p[b * 3 + 1] - ay, uz = p[b * 3 + 2] - az;
			float vx = p[c * 3] - ax, vy = p[c * 3 + 1] - ay, vz = p[c * 3 + 2] - az;
			float lu = (float) Math.sqrt(ux * ux + uy * uy + uz * uz), lv = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
			int su = Math.min(256, Math.max(1, (int) Math.ceil(lu / (CELL * 0.5F)))), sv = Math.min(256, Math.max(1, (int) Math.ceil(lv / (CELL * 0.5F))));
			for (int i = 0; i <= su; i++) {
				float s = i / (float) su;
				for (int j = 0; j <= sv - (int) Math.floor(s * sv); j++) {
					float t = j / (float) sv;
					if (s + t > 1.0001F) {
						break;
					}
					mark(ax + ux * s + vx * t, ay + uy * s + vy * t, az + uz * s + vz * t);
				}
			}
		}

		/** Whether a ray from o along unit d meets an occupied cell within dist. */
		boolean blocked(float ox, float oy, float oz, float dx, float dy, float dz, float dist) {
			float step = CELL * 0.75F;
			for (float t = 0; t < dist; t += step) {
				float x = ox + dx * t, y = oy + dy * t, z = oz + dz * t;
				int i = index(x, y, z);
				if (i < 0) {
					return false;   // left the car's box: open sky
				}
				if (cells.get(i)) {
					return true;
				}
			}
			return false;
		}
	}
}
