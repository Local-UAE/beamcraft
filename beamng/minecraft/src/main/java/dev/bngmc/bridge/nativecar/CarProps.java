package dev.bngmc.bridge.nativecar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * A car's props as BeamNG's exporter writes them (the steering wheel, mirrors, pedals, gauge
 * needles): primitives with a vertex buffer of their own instead of the flexmeshes' shared one,
 * placed by their glTF node's transform and its parents'. Their normals are in BeamNG's axes like
 * the flexmeshes' (CarModel.bngNormalsToGltf) and turn with the node too: so they matched their
 * triangles at 0.85-1.0 on the M2's steering wheel, mirrors and pedals, against 0.03-0.62 any
 * other way (measured). They stay as exported and follow the nodes around them like any other part
 * without a node group. Pure Java, unit-tested.
 */
record CarProps(float[] pos, float[] nrm, float[] uv, float[] uv2, String[] part, List<CarModel.Part> parts) {
	int count() {
		return part.length;
	}

	/**
	 * @param floats     accessor, components -> values
	 * @param indices    accessor -> triangle indices
	 * @param shared     the flexmeshes' POSITION accessor (those primitives are not props)
	 * @param firstIndex where the props' vertices start (after the flexmeshes')
	 */
	static CarProps read(JsonObject gl, BiFunction<Integer, Integer, float[]> floats, IntFunction<int[]> indices, int shared, int firstIndex) {
		JsonArray nodes = gl.has("nodes") ? gl.getAsJsonArray("nodes") : new JsonArray();
		JsonArray meshes = gl.getAsJsonArray("meshes");
		double[][] world = new double[nodes.size()][];
		if (gl.has("scenes") && gl.getAsJsonArray("scenes").size() > 0) {
			int scene = gl.has("scene") ? gl.get("scene").getAsInt() : 0;
			for (JsonElement root : gl.getAsJsonArray("scenes").get(scene).getAsJsonObject().getAsJsonArray("nodes")) {
				place(nodes, root.getAsInt(), IDENTITY, world, 0);
			}
		}
		List<float[]> pos = new ArrayList<>(), nrm = new ArrayList<>(), uv = new ArrayList<>(), uv2 = new ArrayList<>();
		List<String> part = new ArrayList<>();
		List<CarModel.Part> parts = new ArrayList<>();
		int next = firstIndex;
		for (int ni = 0; ni < nodes.size(); ni++) {
			JsonObject node = nodes.get(ni).getAsJsonObject();
			if (!node.has("mesh") || world[ni] == null) {
				continue;
			}
			String name = node.has("name") ? node.get("name").getAsString() : null;
			for (JsonElement pe : meshes.get(node.get("mesh").getAsInt()).getAsJsonObject().getAsJsonArray("primitives")) {
				JsonObject po = pe.getAsJsonObject();
				JsonObject at = po.getAsJsonObject("attributes");
				if (!at.has("POSITION") || at.get("POSITION").getAsInt() == shared || !po.has("indices")) {
					continue;
				}
				float[] p = floats.apply(at.get("POSITION").getAsInt(), 3);
				int n = p.length / 3;
				float[] q = at.has("NORMAL") ? floats.apply(at.get("NORMAL").getAsInt(), 3) : null;
				if (q == null || q.length != p.length) {
					q = new float[p.length];
					for (int i = 1; i < q.length; i += 3) {
						q[i] = 1;
					}
				} else {
					CarModel.bngNormalsToGltf(q);
				}
				apply(world[ni], p, q);
				pos.add(p);
				nrm.add(q);
				uv.add(uvs(floats, at, "TEXCOORD_0", n));
				uv2.add(uvs(floats, at, "TEXCOORD_1", n));
				for (int i = 0; i < n; i++) {
					part.add(name);
				}
				int[] idx = CarModel.validTriangles(indices.apply(po.get("indices").getAsInt()), n);
				for (int i = 0; i < idx.length; i++) {
					idx[i] += next;
				}
				if (det3(world[ni]) < 0) {   // mirrored (glTF 3.7.4): the front face is the other winding now
					for (int i = 0; i + 2 < idx.length; i += 3) {
						int t = idx[i + 1];
						idx[i + 1] = idx[i + 2];
						idx[i + 2] = t;
					}
				}
				parts.add(new CarModel.Part(name, po.has("material") ? po.get("material").getAsInt() : -1, idx));
				next += n;
			}
		}
		return new CarProps(flat(pos), flat(nrm), flat(uv), flat(uv2), part.toArray(new String[0]), parts);
	}

	private static void place(JsonArray nodes, int i, double[] parent, double[][] world, int depth) {
		if (i < 0 || i >= nodes.size() || world[i] != null || depth > 64) {
			return;
		}
		JsonObject node = nodes.get(i).getAsJsonObject();
		world[i] = mul(parent, local(node));
		if (node.has("children")) {
			for (JsonElement c : node.getAsJsonArray("children")) {
				place(nodes, c.getAsInt(), world[i], world, depth + 1);
			}
		}
	}

	private static float[] uvs(BiFunction<Integer, Integer, float[]> floats, JsonObject at, String key, int n) {
		float[] v = at.has(key) ? floats.apply(at.get(key).getAsInt(), 2) : null;
		return v != null && v.length == n * 2 ? v : new float[n * 2];
	}

	private static float[] flat(List<float[]> chunks) {
		float[] out = new float[chunks.stream().mapToInt(c -> c.length).sum()];
		int at = 0;
		for (float[] c : chunks) {
			System.arraycopy(c, 0, out, at, c.length);
			at += c.length;
		}
		return out;
	}

	static float[] concat(float[] a, float[] b) {
		float[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	// -- glTF node transforms: 4x4, column-major, as the glTF "matrix" field ----------------------

	static final double[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

	/** A node's own transform: "matrix", or translation * rotation * scale. */
	static double[] local(JsonObject node) {
		if (node.has("matrix")) {
			double[] m = new double[16];
			JsonArray a = node.getAsJsonArray("matrix");
			for (int i = 0; i < 16; i++) {
				m[i] = a.get(i).getAsDouble();
			}
			return m;
		}
		double[] t = vec(node, "translation", new double[] {0, 0, 0});
		double[] q = vec(node, "rotation", new double[] {0, 0, 0, 1});
		double[] s = vec(node, "scale", new double[] {1, 1, 1});
		double x = q[0], y = q[1], z = q[2], w = q[3];
		// rotation matrix columns, scaled
		return new double[] {(1 - 2 * (y * y + z * z)) * s[0], 2 * (x * y + z * w) * s[0], 2 * (x * z - y * w) * s[0], 0,
			2 * (x * y - z * w) * s[1], (1 - 2 * (x * x + z * z)) * s[1], 2 * (y * z + x * w) * s[1], 0,
			2 * (x * z + y * w) * s[2], 2 * (y * z - x * w) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0,
			t[0], t[1], t[2], 1};
	}

	private static double[] vec(JsonObject node, String key, double[] def) {
		if (!node.has(key) || !node.get(key).isJsonArray()) {
			return def;
		}
		JsonArray a = node.getAsJsonArray(key);
		double[] v = def.clone();
		for (int i = 0; i < v.length && i < a.size(); i++) {
			v[i] = a.get(i).getAsDouble();
		}
		return v;
	}

	static double[] mul(double[] a, double[] b) {
		double[] m = new double[16];
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				double v = 0;
				for (int k = 0; k < 4; k++) {
					v += a[k * 4 + r] * b[c * 4 + k];
				}
				m[c * 4 + r] = v;
			}
		}
		return m;
	}

	/** The determinant of a column-major 4x4's 3x3 part: negative when it mirrors. */
	static double det3(double[] m) {
		return m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2]);
	}

	/** Points p through m; directions q through m's 3x3 part, normalised. In place. */
	static void apply(double[] m, float[] p, float[] q) {
		for (int i = 0; i + 2 < p.length; i += 3) {
			double x = p[i], y = p[i + 1], z = p[i + 2];
			p[i] = (float) (m[0] * x + m[4] * y + m[8] * z + m[12]);
			p[i + 1] = (float) (m[1] * x + m[5] * y + m[9] * z + m[13]);
			p[i + 2] = (float) (m[2] * x + m[6] * y + m[10] * z + m[14]);
			double a = q[i], b = q[i + 1], c = q[i + 2];
			double nx = m[0] * a + m[4] * b + m[8] * c, ny = m[1] * a + m[5] * b + m[9] * c, nz = m[2] * a + m[6] * b + m[10] * c;
			double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (l > 1e-12) {
				q[i] = (float) (nx / l);
				q[i + 1] = (float) (ny / l);
				q[i + 2] = (float) (nz / l);
			}
		}
	}
}
