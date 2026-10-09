package dev.bngmc.bridge.nativecar;

import java.util.Map;

/**
 * Bends a BeamNG car's visual mesh with its physics nodes, the way BeamNG's flexbodies follow
 * their node groups (jbeam/sections/meshs.lua:247-266). Each vertex rides on three nodes of its
 * own part: at bind time it is written in the orthonormal frame those nodes span (origin n0,
 * axes from n1 - n0 and n2 - n0), and every frame it is rebuilt in the frame the moved nodes span.
 * Normals turn with the frame. A part with no known node group uses the nearest nodes of all.
 *
 * <p>Coordinates are whatever the caller uses consistently (here Minecraft axes relative to the
 * car position: BeamNG's world-aligned node offsets (x, y, z) become (x, z, -y), which is also the
 * glTF exporter's axis change). Pure Java, unit-tested.
 */
public final class FlexBinding {
	/**
	 * Shortest frame edge tried, longest first, in m. A short edge turns node noise into big swings
	 * far from it (the VMESH stream rounds nodes to whole cm: with 1 cm edges the Moonhawk's bent mesh
	 * shook up to 21 cm, measured with RealExportJitterTest), so a vertex rides on nodes at least
	 * 10 cm apart where its part has them; small parts fall back to shorter edges.
	 */
	private static final float[] EDGES = {0.10F, 0.03F, 0.01F};
	private static final float MIN_SIN = 0.3F;       // the frame's angle at n0: at least ~17 degrees
	private static final float MIN_AREA = 1e-4F;     // |e1 x e2| below this is a degenerate frame

	private final int[] frame;      // 3 node indices per vertex (n0, n1, n2); n1 = -1: translation only
	private final float[] local;    // vertex in its frame, 3 per vertex
	private final float[] localN;   // normal in its frame, 3 per vertex

	private FlexBinding(int[] frame, float[] local, float[] localN) {
		this.frame = frame;
		this.local = local;
		this.localN = localN;
	}

	public int vertexCount() {
		return local.length / 3;
	}

	/**
	 * @param verts      rest vertex positions, 3 per vertex
	 * @param normals    rest normals, 3 per vertex (may be null)
	 * @param vertexPart part (flexbody mesh) name of each vertex, or null
	 * @param groups     part name -> node indices it follows
	 * @param nodes      rest node positions, 3 per node, same space as verts
	 */
	public static FlexBinding bind(float[] verts, float[] normals, String[] vertexPart, Map<String, int[]> groups, float[] nodes) {
		int nv = verts.length / 3;
		int nn = nodes.length / 3;
		int[] all = new int[nn];
		for (int i = 0; i < nn; i++) {
			all[i] = i;
		}
		int[] frame = new int[nv * 3];
		float[] local = new float[nv * 3];
		float[] localN = new float[nv * 3];
		float[] f = new float[9];
		for (int v = 0; v < nv; v++) {
			int[] cand = vertexPart != null && vertexPart[v] != null ? groups.get(vertexPart[v]) : null;
			if (cand == null || cand.length == 0) {
				cand = all;
			}
			float vx = verts[v * 3], vy = verts[v * 3 + 1], vz = verts[v * 3 + 2];
			int n0 = nearest(nodes, cand, vx, vy, vz, -1, -1, nn);
			int n1 = -1, n2 = -1;
			for (float edge : EDGES) {
				n1 = nearestFar(nodes, cand, n0, edge, vx, vy, vz, nn);
				n2 = n1 < 0 ? -1 : nearestWide(nodes, cand, n0, n1, edge, vx, vy, vz, nn);
				if (n2 >= 0) {
					break;
				}
			}
			if (n2 < 0) {   // no well-shaped frame: any real triangle near the vertex
				n1 = nearestFar(nodes, cand, n0, EDGES[EDGES.length - 1], vx, vy, vz, nn);
				n2 = n1 < 0 ? -1 : nearestNonCollinear(nodes, cand, n0, n1, vx, vy, vz, nn);
			}
			if (n2 < 0) {
				n1 = -1;
			}
			frame[v * 3] = n0;
			frame[v * 3 + 1] = n1;
			frame[v * 3 + 2] = n2;
			float dx = vx - nodes[n0 * 3], dy = vy - nodes[n0 * 3 + 1], dz = vz - nodes[n0 * 3 + 2];
			if (n1 < 0) {
				local[v * 3] = dx;
				local[v * 3 + 1] = dy;
				local[v * 3 + 2] = dz;
				if (normals != null) {
					System.arraycopy(normals, v * 3, localN, v * 3, 3);
				}
				continue;
			}
			basis(nodes, n0, n1, n2, f);
			local[v * 3] = dx * f[0] + dy * f[1] + dz * f[2];
			local[v * 3 + 1] = dx * f[3] + dy * f[4] + dz * f[5];
			local[v * 3 + 2] = dx * f[6] + dy * f[7] + dz * f[8];
			if (normals != null) {
				float nx = normals[v * 3], ny = normals[v * 3 + 1], nz = normals[v * 3 + 2];
				localN[v * 3] = nx * f[0] + ny * f[1] + nz * f[2];
				localN[v * 3 + 1] = nx * f[3] + ny * f[4] + nz * f[5];
				localN[v * 3 + 2] = nx * f[6] + ny * f[7] + nz * f[8];
			}
		}
		return new FlexBinding(frame, local, localN);
	}

	/**
	 * The binding as vertex data, so the GPU can bend the mesh (CarShader does what deform does):
	 * per vertex its three frame nodes (n1 = -1: translation only), its position and its normal in
	 * that frame.
	 */
	public void write(java.nio.IntBuffer frames, java.nio.FloatBuffer localPos, java.nio.FloatBuffer localNormals) {
		frames.put(frame);
		localPos.put(local);
		localNormals.put(localN);
	}

	/** Rebuilds every vertex (and normal) from the current node positions into pos / nrm. */
	public void deform(float[] nodes, float[] pos, float[] nrm) {
		float[] f = new float[9];
		int nv = vertexCount();
		for (int v = 0; v < nv; v++) {
			int n0 = frame[v * 3], n1 = frame[v * 3 + 1], n2 = frame[v * 3 + 2];
			float ox = nodes[n0 * 3], oy = nodes[n0 * 3 + 1], oz = nodes[n0 * 3 + 2];
			float lx = local[v * 3], ly = local[v * 3 + 1], lz = local[v * 3 + 2];
			if (n1 < 0) {
				pos[v * 3] = ox + lx;
				pos[v * 3 + 1] = oy + ly;
				pos[v * 3 + 2] = oz + lz;
				System.arraycopy(localN, v * 3, nrm, v * 3, 3);
				continue;
			}
			basis(nodes, n0, n1, n2, f);
			pos[v * 3] = ox + lx * f[0] + ly * f[3] + lz * f[6];
			pos[v * 3 + 1] = oy + lx * f[1] + ly * f[4] + lz * f[7];
			pos[v * 3 + 2] = oz + lx * f[2] + ly * f[5] + lz * f[8];
			float ax = localN[v * 3], ay = localN[v * 3 + 1], az = localN[v * 3 + 2];
			nrm[v * 3] = ax * f[0] + ay * f[3] + az * f[6];
			nrm[v * 3 + 1] = ax * f[1] + ay * f[4] + az * f[7];
			nrm[v * 3 + 2] = ax * f[2] + ay * f[5] + az * f[8];
		}
	}

	/** Orthonormal frame rows t1, t2, t3 into f[0..8]. */
	private static void basis(float[] nodes, int n0, int n1, int n2, float[] f) {
		float ax = nodes[n1 * 3] - nodes[n0 * 3], ay = nodes[n1 * 3 + 1] - nodes[n0 * 3 + 1], az = nodes[n1 * 3 + 2] - nodes[n0 * 3 + 2];
		float bx = nodes[n2 * 3] - nodes[n0 * 3], by = nodes[n2 * 3 + 1] - nodes[n0 * 3 + 1], bz = nodes[n2 * 3 + 2] - nodes[n0 * 3 + 2];
		float la = (float) Math.sqrt(ax * ax + ay * ay + az * az);
		if (la < 1e-9F) {
			la = 1e-9F;
		}
		float t1x = ax / la, t1y = ay / la, t1z = az / la;
		float cx = t1y * bz - t1z * by, cy = t1z * bx - t1x * bz, cz = t1x * by - t1y * bx;
		float lc = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
		if (lc < 1e-9F) {
			lc = 1e-9F;
		}
		float t3x = cx / lc, t3y = cy / lc, t3z = cz / lc;
		f[0] = t1x;
		f[1] = t1y;
		f[2] = t1z;
		f[3] = t3y * t1z - t3z * t1y;   // t2 = t3 x t1
		f[4] = t3z * t1x - t3x * t1z;
		f[5] = t3x * t1y - t3y * t1x;
		f[6] = t3x;
		f[7] = t3y;
		f[8] = t3z;
	}

	private static float d2(float[] nodes, int n, float x, float y, float z) {
		float dx = nodes[n * 3] - x, dy = nodes[n * 3 + 1] - y, dz = nodes[n * 3 + 2] - z;
		return dx * dx + dy * dy + dz * dz;
	}

	private static int nearest(float[] nodes, int[] cand, float x, float y, float z, int skipA, int skipB, int nn) {
		int best = -1;
		float bestD = Float.MAX_VALUE;
		for (int n : cand) {
			if (n < 0 || n >= nn || n == skipA || n == skipB) {
				continue;
			}
			float d = d2(nodes, n, x, y, z);
			if (d < bestD) {
				bestD = d;
				best = n;
			}
		}
		return best < 0 ? 0 : best;
	}

	/** Nearest node to (x, y, z) at least edge away from n0. */
	private static int nearestFar(float[] nodes, int[] cand, int n0, float edge, float x, float y, float z, int nn) {
		int best = -1;
		float bestD = Float.MAX_VALUE;
		float x0 = nodes[n0 * 3], y0 = nodes[n0 * 3 + 1], z0 = nodes[n0 * 3 + 2];
		for (int n : cand) {
			if (n < 0 || n >= nn || n == n0 || d2(nodes, n, x0, y0, z0) < edge * edge) {
				continue;
			}
			float d = d2(nodes, n, x, y, z);
			if (d < bestD) {
				bestD = d;
				best = n;
			}
		}
		return best;
	}

	/** Nearest node to (x, y, z) at least edge from n0 whose angle with n1 at n0 has a sine of MIN_SIN or more. */
	private static int nearestWide(float[] nodes, int[] cand, int n0, int n1, float edge, float x, float y, float z, int nn) {
		float ax = nodes[n1 * 3] - nodes[n0 * 3], ay = nodes[n1 * 3 + 1] - nodes[n0 * 3 + 1], az = nodes[n1 * 3 + 2] - nodes[n0 * 3 + 2];
		float la2 = ax * ax + ay * ay + az * az;
		int best = -1;
		float bestD = Float.MAX_VALUE;
		for (int n : cand) {
			if (n < 0 || n >= nn || n == n0 || n == n1) {
				continue;
			}
			float bx = nodes[n * 3] - nodes[n0 * 3], by = nodes[n * 3 + 1] - nodes[n0 * 3 + 1], bz = nodes[n * 3 + 2] - nodes[n0 * 3 + 2];
			float lb2 = bx * bx + by * by + bz * bz;
			if (lb2 < edge * edge) {
				continue;
			}
			float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
			if (cx * cx + cy * cy + cz * cz < MIN_SIN * MIN_SIN * la2 * lb2) {
				continue;
			}
			float d = d2(nodes, n, x, y, z);
			if (d < bestD) {
				bestD = d;
				best = n;
			}
		}
		return best;
	}

	/** Nearest node to (x, y, z) that spans a real triangle with n0 and n1. */
	private static int nearestNonCollinear(float[] nodes, int[] cand, int n0, int n1, float x, float y, float z, int nn) {
		float ax = nodes[n1 * 3] - nodes[n0 * 3], ay = nodes[n1 * 3 + 1] - nodes[n0 * 3 + 1], az = nodes[n1 * 3 + 2] - nodes[n0 * 3 + 2];
		int best = -1;
		float bestD = Float.MAX_VALUE;
		for (int n : cand) {
			if (n < 0 || n >= nn || n == n0 || n == n1) {
				continue;
			}
			float bx = nodes[n * 3] - nodes[n0 * 3], by = nodes[n * 3 + 1] - nodes[n0 * 3 + 1], bz = nodes[n * 3 + 2] - nodes[n0 * 3 + 2];
			float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
			if (cx * cx + cy * cy + cz * cz < MIN_AREA * MIN_AREA) {
				continue;
			}
			float d = d2(nodes, n, x, y, z);
			if (d < bestD) {
				bestD = d;
				best = n;
			}
		}
		return best;
	}
}
