package dev.bngmc.bridge.nativecar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A BeamNG car as BeamNG's own glTF exporter writes it (util/export.lua, binary .glb): every
 * flexmesh shares one vertex buffer (positions, normals, UVs) and has its own index ranges and
 * materials; props (steering wheel, mirrors, pedals, gauge needles) have their own buffers and a
 * node transform, and are added after the flexmeshes, placed where they were exported (CarProps).
 * Positions are relative to the car position in glTF axes, which are Minecraft's: BeamNG
 * (x, y, z) -> (x, z, -y) (export.lua:455-458). Pure Java, unit-tested.
 */
public final class CarModel {
	/** Triangles of one flexmesh with one material, as indices into the shared vertices. */
	public record Part(String mesh, int material, int[] indices) {
	}

	/** image: index into {@link #images}, or -1; glass: drawn see-through; bng: BeamNG's layers. */
	public record Material(String name, int image, float[] color, boolean glass, BngMaterial bng) {
	}

	public final float[] positions;
	public final float[] normals;
	public final float[] uvs;
	/** Second UV set (TEXCOORD_1), zeros when the export has none. */
	public final float[] uvs2;
	/** Flexmesh (= BeamNG flexbody mesh) each vertex belongs to, for its node group. */
	public final String[] vertexPart;
	public final List<Part> parts;
	public final List<Material> materials;
	public final List<byte[]> images;

	/**
	 * BeamNG's exporter writes the vertices in glTF's axes (y up) but the normals still in BeamNG's
	 * (z up): util/export.lua:864-879 stores both as the engine hands them over, and the normals of
	 * every car exported so far (18 exports, 10 models) matched their triangles at 0.33-0.43 as
	 * stored and at 0.91-0.97 turned like the vertices, (x, y, z) -> (x, z, -y) (measured, mean
	 * |cos| between vertex and face normal). Turned in place.
	 */
	static void bngNormalsToGltf(float[] n) {
		for (int i = 0; i + 2 < n.length; i += 3) {
			float y = n[i + 1];
			n[i + 1] = n[i + 2];
			n[i + 2] = -y;
		}
	}

	private CarModel(float[] positions, float[] normals, float[] uvs, float[] uvs2, String[] vertexPart, List<Part> parts,
					 List<Material> materials, List<byte[]> images) {
		this.positions = positions;
		this.normals = normals;
		this.uvs = uvs;
		this.uvs2 = uvs2;
		this.vertexPart = vertexPart;
		this.parts = parts;
		this.materials = materials;
		this.images = images;
	}

	public int vertexCount() {
		return positions.length / 3;
	}

	public int triangleCount() {
		int n = 0;
		for (Part p : parts) {
			n += p.indices().length / 3;
		}
		return n;
	}

	public static CarModel read(byte[] glb) {
		ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
		if (glb.length < 20 || b.getInt(0) != 0x46546C67) {
			throw new IllegalArgumentException("not a binary glTF file");
		}
		int jsonLen = b.getInt(12);
		JsonObject gl = JsonParser.parseString(new String(glb, 20, jsonLen, StandardCharsets.UTF_8)).getAsJsonObject();
		int binStart = 20 + jsonLen + 8;
		Reader r = new Reader(gl, b, binStart);

		JsonArray meshes = gl.getAsJsonArray("meshes");
		// The shared vertex buffer is the POSITION accessor most primitives use.
		Map<Integer, Integer> uses = new HashMap<>();
		for (JsonElement m : meshes) {
			for (JsonElement p : m.getAsJsonObject().getAsJsonArray("primitives")) {
				JsonObject at = p.getAsJsonObject().getAsJsonObject("attributes");
				if (at.has("POSITION")) {
					uses.merge(at.get("POSITION").getAsInt(), 1, Integer::sum);
				}
			}
		}
		int shared = uses.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
		JsonObject sharedAttrs = null;
		Map<Integer, String> meshName = new HashMap<>();
		for (JsonElement n : gl.getAsJsonArray("nodes")) {
			JsonObject o = n.getAsJsonObject();
			if (o.has("mesh")) {
				meshName.putIfAbsent(o.get("mesh").getAsInt(), o.has("name") ? o.get("name").getAsString() : null);
			}
		}
		List<Part> parts = new ArrayList<>();
		for (int mi = 0; mi < meshes.size(); mi++) {
			for (JsonElement p : meshes.get(mi).getAsJsonObject().getAsJsonArray("primitives")) {
				JsonObject po = p.getAsJsonObject();
				JsonObject at = po.getAsJsonObject("attributes");
				if (!at.has("POSITION") || at.get("POSITION").getAsInt() != shared || !po.has("indices")) {
					continue;
				}
				sharedAttrs = at;
				parts.add(new Part(meshName.get(mi), po.has("material") ? po.get("material").getAsInt() : -1, r.indices(po.get("indices").getAsInt())));
			}
		}
		float[] pos = r.floats(shared, 3);
		int vertexCount = pos.length / 3;
		// A triangle naming a vertex that doesn't exist would make the GPU read past the vertex buffer.
		for (int i = 0; i < parts.size(); i++) {
			Part p = parts.get(i);
			parts.set(i, new Part(p.mesh(), p.material(), validTriangles(p.indices(), vertexCount)));
		}
		float[] nrm = sharedAttrs != null && sharedAttrs.has("NORMAL") ? r.floats(sharedAttrs.get("NORMAL").getAsInt(), 3) : null;
		if (nrm != null && nrm.length != pos.length) {
			nrm = null;
		}
		if (nrm != null) {
			bngNormalsToGltf(nrm);
		}
		float[] uv = sharedAttrs != null && sharedAttrs.has("TEXCOORD_0") ? r.floats(sharedAttrs.get("TEXCOORD_0").getAsInt(), 2) : null;
		if (uv == null || uv.length != vertexCount * 2) {
			uv = new float[vertexCount * 2];
		}
		float[] uv2 = sharedAttrs != null && sharedAttrs.has("TEXCOORD_1") ? r.floats(sharedAttrs.get("TEXCOORD_1").getAsInt(), 2) : null;
		if (uv2 == null || uv2.length != vertexCount * 2) {
			uv2 = new float[vertexCount * 2];
		}
		String[] vertexPart = new String[pos.length / 3];
		for (Part p : parts) {
			for (int i : p.indices()) {
				if (i >= 0 && i < vertexPart.length && vertexPart[i] == null) {
					vertexPart[i] = p.mesh();
				}
			}
		}
		if (nrm == null) {
			nrm = new float[pos.length];
		}
		CarProps props = CarProps.read(gl, r::floats, r::indices, shared, vertexCount);
		if (props.count() > 0) {
			pos = CarProps.concat(pos, props.pos());
			nrm = CarProps.concat(nrm, props.nrm());
			uv = CarProps.concat(uv, props.uv());
			uv2 = CarProps.concat(uv2, props.uv2());
			String[] all = java.util.Arrays.copyOf(vertexPart, vertexPart.length + props.part().length);
			System.arraycopy(props.part(), 0, all, vertexPart.length, props.part().length);
			vertexPart = all;
			parts.addAll(props.parts());
		}

		List<Material> materials = new ArrayList<>();
		JsonArray textures = gl.has("textures") ? gl.getAsJsonArray("textures") : new JsonArray();
		if (gl.has("materials")) {
			for (JsonElement e : gl.getAsJsonArray("materials")) {
				JsonObject m = e.getAsJsonObject();
				String name = m.has("name") && !m.get("name").isJsonNull() ? m.get("name").getAsString() : "";
				int image = -1;
				float[] color = {1, 1, 1, 1};
				if (m.has("pbrMetallicRoughness")) {
					JsonObject pbr = m.getAsJsonObject("pbrMetallicRoughness");
					if (pbr.has("baseColorTexture")) {
						int t = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
						JsonObject tex = textures.get(t).getAsJsonObject();
						image = tex.has("source") ? tex.get("source").getAsInt() : -1;
					}
					if (pbr.has("baseColorFactor")) {
						JsonArray c = pbr.getAsJsonArray("baseColorFactor");
						for (int i = 0; i < 4 && i < c.size(); i++) {
							color[i] = c.get(i).getAsFloat();
						}
					}
				}
				JsonObject ex = m.has("extras") && m.getAsJsonObject("extras").has("bngMaterial")
					&& m.getAsJsonObject("extras").get("bngMaterial").isJsonObject() ? m.getAsJsonObject("extras").getAsJsonObject("bngMaterial") : null;
				BngMaterial bng = BngMaterial.parse(name, ex, image, t -> t >= 0 && t < textures.size() && textures.get(t).getAsJsonObject().has("source")
					? textures.get(t).getAsJsonObject().get("source").getAsInt() : -1);
				boolean glass = bng.translucent() || name.toLowerCase().contains("glass")
					|| (m.has("alphaMode") && "BLEND".equals(m.get("alphaMode").getAsString()));
				materials.add(new Material(name, image, color, glass, bng));
			}
		}
		List<byte[]> images = new ArrayList<>();
		if (gl.has("images")) {
			for (JsonElement e : gl.getAsJsonArray("images")) {
				JsonObject im = e.getAsJsonObject();
				images.add(im.has("bufferView") ? r.view(im.get("bufferView").getAsInt()) : new byte[0]);
			}
		}
		return new CarModel(pos, nrm, uv, uv2, vertexPart, parts, materials, images);
	}

	/**
	 * The image starts with PNG's signature. BeamNG's exporter embeds each texture file as it is, and
	 * mods ship JPEGs (the Cadillac CT5: 32 of its 90, the tyres and the carbon among them), which
	 * Minecraft's own reader refuses (NativeImage.read checks the PNG header first).
	 */
	public static boolean isPng(byte[] image) {
		byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
		if (image == null || image.length < sig.length) {
			return false;
		}
		for (int i = 0; i < sig.length; i++) {
			if (image[i] != sig[i]) {
				return false;
			}
		}
		return true;
	}

	static int[] validTriangles(int[] idx, int vertexCount) {
		int[] out = new int[idx.length - idx.length % 3];
		int k = 0;
		for (int i = 0; i + 2 < idx.length; i += 3) {
			int a = idx[i], b = idx[i + 1], c = idx[i + 2];
			if (a >= 0 && a < vertexCount && b >= 0 && b < vertexCount && c >= 0 && c < vertexCount) {
				out[k++] = a;
				out[k++] = b;
				out[k++] = c;
			}
		}
		return k == out.length ? out : java.util.Arrays.copyOf(out, k);
	}

	/** Reads accessors and buffer views out of the GLB's binary chunk. */
	private static final class Reader {
		private final JsonObject gl;
		private final ByteBuffer b;
		private final int binStart;

		Reader(JsonObject gl, ByteBuffer b, int binStart) {
			this.gl = gl;
			this.b = b;
			this.binStart = binStart;
		}

		private JsonObject accessor(int i) {
			return gl.getAsJsonArray("accessors").get(i).getAsJsonObject();
		}

		private JsonObject bufferView(int i) {
			return gl.getAsJsonArray("bufferViews").get(i).getAsJsonObject();
		}

		private static int intOr(JsonObject o, String k, int def) {
			return o.has(k) ? o.get(k).getAsInt() : def;
		}

		float[] floats(int acc, int comps) {
			JsonObject a = accessor(acc);
			JsonObject bv = bufferView(a.get("bufferView").getAsInt());
			int count = a.get("count").getAsInt();
			int stride = intOr(bv, "byteStride", comps * 4);
			int base = binStart + intOr(bv, "byteOffset", 0) + intOr(a, "byteOffset", 0);
			float[] out = new float[count * comps];
			for (int i = 0; i < count; i++) {
				for (int c = 0; c < comps; c++) {
					out[i * comps + c] = b.getFloat(base + i * stride + c * 4);
				}
			}
			return out;
		}

		int[] indices(int acc) {
			JsonObject a = accessor(acc);
			JsonObject bv = bufferView(a.get("bufferView").getAsInt());
			int count = a.get("count").getAsInt();
			int type = a.get("componentType").getAsInt();
			int base = binStart + intOr(bv, "byteOffset", 0) + intOr(a, "byteOffset", 0);
			int[] out = new int[count];
			for (int i = 0; i < count; i++) {
				out[i] = switch (type) {
					case 5125 -> b.getInt(base + i * 4);
					case 5123 -> b.getShort(base + i * 2) & 0xFFFF;
					case 5121 -> b.get(base + i) & 0xFF;
					default -> throw new IllegalArgumentException("index type " + type);
				};
			}
			return out;
		}

		byte[] view(int i) {
			JsonObject bv = bufferView(i);
			byte[] out = new byte[bv.get("byteLength").getAsInt()];
			b.get(binStart + intOr(bv, "byteOffset", 0), out);
			return out;
		}
	}
}
