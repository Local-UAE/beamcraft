package dev.bngmc.bridge.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Car shapes for the cutouts in Minecraft-hosted worlds (docs/minecraft-host.md): the newest
 * VMESH per car (node offsets, every frame) and its VTRIS (skin triangles, once per shape),
 * parsed on the link thread. Offsets are world-aligned, in 1/q metre from the car position (q = 1000:
 * millimetres; 100, centimetres, when a sender gives no q), so a node is at pos + p / q in
 * canonical coordinates.
 */
public final class VehicleMeshes {
	private VehicleMeshes() {
	}

	/**
	 * One car's node offsets; {@code tv} names its skin (0 = BeamNG is still reading it). rxMs: our
	 * clock when it arrived; ts: BeamNG's clock when it was read (the envelope's ts); fwd, up: the
	 * car's facing, when sent.
	 */
	public record Mesh(long id, int tv, V3 pos, int[] p, double rxMs, double ts, V3 fwd, V3 up, int q) {
		public Mesh(long id, int tv, V3 pos, int[] p, double rxMs, double ts, V3 fwd, V3 up) {
			this(id, tv, pos, p, rxMs, ts, fwd, up, 100);
		}

		public int nodes() {
			return p.length / 3;
		}
	}

	/** Skin triangles: node index triples into a {@link Mesh} with {@code nodes} nodes. */
	public record Tris(long id, int tv, int nodes, int[] idx) {
	}

	private static final Map<Long, Mesh> MESHES = new ConcurrentHashMap<>();
	private static final Map<Long, Tris> TRIS = new ConcurrentHashMap<>();
	private static final Map<Long, MeshTimeline> TIMELINES = new ConcurrentHashMap<>();
	private static final MeshTimeline.Clock CLOCK = new MeshTimeline.Clock();

	public static void install(BngLink link) {
		link.onMessage(Protocol.VMESH, env -> {
			double now = BngLink.nowMs();
			Mesh m = parseMesh(env.body(), now);
			if (m != null) {
				MESHES.put(m.id(), m);
				TIMELINES.computeIfAbsent(m.id(), k -> new MeshTimeline()).add(m);
				CLOCK.observe(now, m.ts());
			}
		});
		link.onMessage(Protocol.VTRIS, env -> {
			Tris t = parseTris(env.body());
			if (t != null) {
				TRIS.put(t.id(), t);
			}
		});
	}

	public static Map<Long, Mesh> meshes() {
		return MESHES;
	}

	public static Tris tris(long id) {
		return TRIS.get(id);
	}

	/** The car's recent snapshots, for blended drawing (MeshTimeline). */
	public static MeshTimeline timeline(long id) {
		return TIMELINES.get(id);
	}

	/** Minecraft clock -> BeamNG clock, from the snapshots' send times. */
	public static MeshTimeline.Clock clock() {
		return CLOCK;
	}

	/** Forgets cars that stopped arriving (out of range, removed, BeamNG gone). */
	public static void prune(double nowMs, double maxAgeMs) {
		MESHES.values().removeIf(m -> nowMs - m.rxMs() > maxAgeMs);
		TIMELINES.keySet().removeIf(id -> !MESHES.containsKey(id));
	}

	public static void clear() {
		MESHES.clear();
		TRIS.clear();
		TIMELINES.clear();
	}

	/** True if BeamNG has named this car's skin and we don't hold those triangles (lost VTRIS). */
	public static boolean needsTris(Mesh m, Tris t) {
		return m.tv() != 0 && (t == null || t.tv() != m.tv());
	}

	public static boolean drawable(Mesh m, Tris t) {
		return m != null && t != null && m.tv() != 0 && t.tv() == m.tv() && t.nodes() == m.nodes();
	}

	static Mesh parseMesh(JsonObject o, double nowMs) {
		V3 pos = BngState.vec(o, "pos");
		int[] p = ints(o.get("p"));
		if (pos == null || p == null || p.length % 3 != 0 || !o.has("id")) {
			return null;
		}
		double ts = o.has("ts") ? o.get("ts").getAsDouble() : nowMs;
		int q = o.has("q") && o.get("q").isJsonPrimitive() ? Math.max(1, o.get("q").getAsInt()) : 100;
		return new Mesh(o.get("id").getAsLong(), o.has("tv") ? o.get("tv").getAsInt() : 0, pos, p, nowMs, ts, BngState.vec(o, "fwd"),
			BngState.vec(o, "up"), q);
	}

	static Tris parseTris(JsonObject o) {
		int[] idx = ints(o.get("tris"));
		if (idx == null || idx.length % 3 != 0 || !o.has("id") || !o.has("tv") || !o.has("n")) {
			return null;
		}
		return new Tris(o.get("id").getAsLong(), o.get("tv").getAsInt(), o.get("n").getAsInt(), idx);
	}

	private static int[] ints(JsonElement e) {
		if (e == null || !e.isJsonArray()) {
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = (int) Math.round(a.get(i).getAsDouble());
		}
		return out;
	}

	/**
	 * The skin as triangles in Minecraft coordinates relative to the camera (x, y, z per vertex,
	 * three vertices per triangle). Triangles naming a node the mesh doesn't have are skipped.
	 */
	public static float[] cameraRelativeTriangles(Mesh m, Tris t, CrossoverCoords.Region r, double camX, double camY, double camZ) {
		V3 base = CrossoverCoords.canonicalToMinecraftPosition(m.pos(), r);
		double bx = base.x() - camX, by = base.y() - camY, bz = base.z() - camZ;
		int nodes = m.nodes();
		int[] p = m.p();
		double unit = r.scale() / m.q();   // node units -> Minecraft blocks
		int[] idx = t.idx();
		float[] out = new float[idx.length * 3];
		int k = 0;
		for (int i = 0; i + 2 < idx.length; i += 3) {
			if (!valid(idx[i], nodes) || !valid(idx[i + 1], nodes) || !valid(idx[i + 2], nodes)) {
				continue;
			}
			for (int j = 0; j < 3; j++) {
				int n = idx[i + j] * 3;
				// canonical offset (dx, dy, dz) -> Minecraft (dx, dz, -dy), see coordinates.md
				out[k++] = (float) (bx + p[n] * unit);
				out[k++] = (float) (by + p[n + 2] * unit);
				out[k++] = (float) (bz - p[n + 1] * unit);
			}
		}
		return k == out.length ? out : java.util.Arrays.copyOf(out, k);
	}

	private static boolean valid(int i, int nodes) {
		return i >= 0 && i < nodes;
	}
}
