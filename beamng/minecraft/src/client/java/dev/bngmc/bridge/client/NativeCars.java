package dev.bngmc.bridge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.MeshTimeline;
import dev.bngmc.bridge.link.Protocol;
import dev.bngmc.bridge.link.VehicleMeshes;
import dev.bngmc.bridge.link.Vehicles;
import dev.bngmc.bridge.nativecar.BngMaterial;
import dev.bngmc.bridge.nativecar.CarModel;
import dev.bngmc.bridge.nativecar.FlexBinding;
import dev.bngmc.bridge.nativecar.NativeExport;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minecraft draws the BeamNG car itself (docs/minecraft-host.md, "native"): no overlay window.
 * BeamNG exports the car once with its own glTF exporter (VEXPORT -> VEXPORTED, files in the
 * BeamNG user folder, never shipped); here the mesh is loaded, each vertex is bound to its
 * flexbody's nodes (FlexBinding), and every frame the newest VMESH node positions bend it, so the
 * car moves, steers and crumples as BeamNG simulates it. The bending runs on the GPU (CarShader):
 * per frame only the car's node positions go up, a few kB, not its hundreds of thousands of
 * vertices (bending on the CPU took 20 ms a frame for the M3, measured). Drawn with a small shader
 * of our own after the entities, depth-tested like any Minecraft object.
 */
public final class NativeCars {
	private NativeCars() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static final double STALE_MS = 500;
	/**
	 * How far behind BeamNG's newest snapshot the car is drawn, ms: about three of BeamNG's
	 * 120 Hz frames, enough that there is almost always a snapshot on each side to blend between.
	 */
	static final double DRAW_DELAY_MS = 25;
	private static double frameSenderMs = Double.NaN;

	/**
	 * Once per frame, before the camera is placed (CameraSync): the BeamNG moment this frame shows.
	 * The camera and the car both use it, so they can't drift against each other.
	 */
	public static void beginFrame() {
		frameSenderMs = VehicleMeshes.clock().senderTimeAt(BngLink.nowMs()) - DRAW_DELAY_MS;
		frame++;
	}

	private static long frame;

	/** Counts frames (beginFrame): a car bent for the shader pack is bent once a frame (PackCarRenderer). */
	static long frameNo() {
		return frame;
	}

	private static boolean blend = true;

	/** Dev switch for comparisons: off draws the newest snapshot as it is (the old behaviour). */
	public static void setBlend(boolean on) {
		blend = on;
	}

	private static float[] eyeNodes = new float[0];

	/**
	 * Render thread: the driver's eye in this car this frame, Minecraft coordinates, with the car's
	 * yaw and pitch there (DriverEye), from the same blended pose the car is drawn in; null if the
	 * car isn't loaded or its export has no view.
	 */
	public static Eye driverEye(long id) {
		Car c = CARS.get(id);
		CrossoverCoords.Region region = BngWorld.region();
		MeshTimeline.Pose pose = pose(id);
		if (c == null || c.state != State.READY || c.view == null || region == null || pose == null || pose.nodeCount() != c.nodeCount) {
			return null;
		}
		if (eyeNodes.length != c.nodeCount * 3) {
			eyeNodes = new float[c.nodeCount * 3];
		}
		pose.nodesMinecraftAxes(eyeNodes);
		dev.bngmc.bridge.nativecar.DriverEye.Eye e = dev.bngmc.bridge.nativecar.DriverEye.of(c.view, eyeNodes);
		if (e == null) {
			return null;
		}
		V3 base = CrossoverCoords.canonicalToMinecraftPosition(pose.pos(), region);
		double s = region.scale();
		return new Eye(new Vec3(base.x() + e.pos()[0] * s, base.y() + e.pos()[1] * s, base.z() + e.pos()[2] * s), e.yaw(), e.pitch());
	}

	public record Eye(Vec3 pos, float yaw, float pitch) {
	}

	/** The car's blended pose for this frame, or null. */
	public static MeshTimeline.Pose pose(long id) {
		MeshTimeline t = VehicleMeshes.timeline(id);
		if (t == null || Double.isNaN(frameSenderMs)) {
			return null;
		}
		return blend ? t.sample(frameSenderMs) : t.sample(Double.MAX_VALUE);
	}

	// -- smoothness measurement (dev command "native stats N") -------------------------------------
	private static int statsLeft;
	private static java.util.function.Consumer<String> statsOut;
	private static final List<double[]> STATS = new ArrayList<>();   // {seconds, car - camera x, y, z}

	public static void recordStats(int frames, java.util.function.Consumer<String> out) {
		STATS.clear();
		statsLeft = frames;
		statsOut = out;
	}

	private static void statsFrame(double carX, double carY, double carZ, Vec3 cam) {
		if (statsLeft <= 0) {
			return;
		}
		STATS.add(new double[] {System.nanoTime() / 1e9, carX - cam.x, carY - cam.y, carZ - cam.z});
		if (--statsLeft == 0 && statsOut != null) {
			statsOut.accept(smoothness(STATS));
		}
	}

	/**
	 * How jerky the car moves on screen: per frame, the change in its velocity relative to the
	 * camera (m/s per frame). Smooth motion keeps it small; a pose held for one frame and then
	 * jumping two shows up as a spike.
	 */
	static String smoothness(List<double[]> s) {
		if (s.size() < 3) {
			return "too few frames";
		}
		double sum = 0, max = 0, dtSum = 0;
		int n = 0;
		for (int i = 2; i < s.size(); i++) {
			double[] a = s.get(i - 2), b = s.get(i - 1), c = s.get(i);
			double dt1 = b[0] - a[0], dt2 = c[0] - b[0];
			if (dt1 <= 0 || dt2 <= 0) {
				continue;
			}
			double j = 0;
			for (int k = 1; k <= 3; k++) {
				double v1 = (b[k] - a[k]) / dt1, v2 = (c[k] - b[k]) / dt2;
				j += (v2 - v1) * (v2 - v1);
			}
			j = Math.sqrt(j);
			sum += j * j;
			max = Math.max(max, j);
			dtSum += dt2;
			n++;
		}
		return String.format("smoothness over %d frames (%.0f fps): rms %.3f m/s per frame, max %.3f, blend %s", n, n / dtSum,
			Math.sqrt(sum / n), max, blend ? "on" : "off");
	}


	enum State { LOADING, UPLOADING, READY, FAILED }

	static final class Car {
		final long id;
		volatile State state = State.LOADING;
		String model = "";
		String error = "";
		CarModel mesh;
		FlexBinding binding;
		int nodeCount;
		/** The car's three paint colours (BeamNG's paint and its two palette colours). */
		float[][] paint = {{0.7F, 0.7F, 0.7F}, {0.5F, 0.5F, 0.5F}, {0.15F, 0.15F, 0.15F}};
		/** Their metallic, roughness, clear coat and clear coat roughness. */
		float[][] paintData = NativeExport.paintData(new JsonObject());
		/** NormalFormat per glTF image (only meaningful for normal maps). */
		int[] normalFormat = new int[0];
		/** The nodes the driver's view hangs on (side file "camera"), or null. */
		NativeExport.DriverView view;
		NativeImage[] images;
		int[] textures;
		int vao, vboStatic, vboFrame, ebo;
		/** The node positions the shader bends the mesh with: a buffer texture, 4 floats per node. */
		int nodeBuffer, nodeTexture;
		final List<int[]> draws = new ArrayList<>();   // {firstIndex, count, material}
		float[] nodes;
		/** Sky each vertex sees, 0-1 (SkyOcclusion), baked on load. */
		float[] sky;
		FloatBuffer nodeData;
		NativeExport.MaterialExtra[] extras = new NativeExport.MaterialExtra[0];
		/** Decoded on the loader thread and published with state UPLOADING; render thread after that. */
		Map<String, NativeImage> detailImages = new java.util.HashMap<>();
		final Map<String, Integer> detailTextures = new java.util.HashMap<>();
		long lastSeenMs = System.currentTimeMillis();
		/** The VMESH shape (tv) this export was made for; -1 once BeamNG replaced the car. */
		volatile int tv = -1;
		/** Indices drawn (all draws); under a shader pack, its bent vertices (PackCarRenderer). */
		int indexCount;
		int packVbo, packVao, packLight;
		long packFrame = -1;
		/** The frame the pack's main pass drew it as an entity; the overlay draws it otherwise. */
		long packDrawnFrame = -1;

		Car(long id) {
			this.id = id;
		}
	}

	private static boolean enabled = !Boolean.getBoolean("bngbridge.noNative");
	private static final Map<Long, Car> CARS = new ConcurrentHashMap<>();
	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "bngbridge-carloader");
		t.setDaemon(true);
		return t;
	});
	private static volatile double lastBendMs;
	private static volatile int lastDrawn;

	public static void install(BngLink link) {
		link.onMessage(Protocol.VEXPORTED, env -> onExported(env.body()));
		link.onMessage(Protocol.ERROR, env -> onError(env.body()));
	}

	public static boolean enabled() {
		return enabled;
	}

	public static boolean toggle() {
		enabled = !enabled;
		return enabled;
	}

	/** Native drawing is wanted: on, in a Minecraft-hosted world. Every car in range gets exported. */
	public static boolean wanted() {
		return enabled && BngWorld.isHostWorld();
	}

	/**
	 * Wanted and every car in range can be drawn natively: then the overlay, the cutouts and
	 * BeamNG's own render are off. While any car is still exporting or loading (a car just picked,
	 * or one BeamNG replaced), or failed, the cutout mode shows them all, so a car is never
	 * invisible because its model isn't there yet.
	 */
	public static boolean active() {
		if (!wanted()) {
			return false;
		}
		// No car, or a shader pack on: BeamNG's own picture stays off (a car still loading is
		// missing for those seconds). Its world render switched back on with the card's memory
		// full (a car removed, the pack and two mod cars loaded) froze BeamNG for 2 minutes and
		// the NVIDIA driver reset (2026-10-03, 16:18)
		Map<Long, VehicleMeshes.Mesh> present = VehicleMeshes.meshes();
		if (present.isEmpty() || ShaderCompat.packInUse()) {
			return true;
		}
		for (Map.Entry<Long, VehicleMeshes.Mesh> e : present.entrySet()) {
			if (!drawable(e.getKey(), e.getValue())) {
				return false;
			}
		}
		return true;
	}

	/** This car's export matches the car BeamNG has now (same shape) and is loaded. */
	private static boolean drawable(long id, VehicleMeshes.Mesh m) {
		Car c = CARS.get(id);
		return c != null && c.state == State.READY && c.tv == m.tv() && c.nodeCount * 3 == m.p().length;
	}

	// -- export queue: one car at a time, every car in range ---------------------------------------
	private static final long EXPORT_WAIT_MS = 150_000;   // the first export of a model took 23-29 s (measured)
	private static final long SETTLE_MS = 1500;           // a car's shape must hold this long before export
	private static final int MAX_TRIES = 2;
	private static final long EXPORT_REASK_MS = 5000;
	private static long exportAskedMs;
	private static volatile long exportingId = -1;
	private static volatile int exportingTv = -1;
	private static String exportingKey = "";
	private static long exportSentMs;
	private static long exportSid = -1;   // BeamNG session the export was asked in
	private static final Map<String, Integer> TRIES = new ConcurrentHashMap<>();
	private static final Map<Long, long[]> SETTLE = new ConcurrentHashMap<>();   // id -> {tv, since ms}

	/**
	 * Client tick: asks BeamNG to export the next car in range that can't be drawn yet. A car is
	 * exported once its shape has held still for SETTLE_MS (a car being spawned or replaced changes
	 * shape while it loads). Two tries per car and shape, then it stays in cutout mode.
	 */
	private static void exportNext(BngLink link) {
		if (!wanted()) {
			return;
		}
		long now = System.currentTimeMillis();
		// Neither of these is a failed try, so it is given back: a big car's export holds BeamNG still
		// long enough (the SF90: 17 s) for its shape to lapse here and the link to reconnect, and
		// counting those used up both tries while BeamNG finished the export (seen 2026-10-03)
		if (exportingId != -1 && link.sessionId() != exportSid) {
			giveBackTry();   // BeamNG restarted: that answer won't come
		}
		if (exportingId != -1 && !VehicleMeshes.meshes().containsKey(exportingId) && now - exportSentMs > SETTLE_MS) {
			giveBackTry();   // the car went away (removed, out of range, or BeamNG stalled): don't keep asking about it
		}
		if (exportingId != -1) {
			if (now - exportSentMs < EXPORT_WAIT_MS) {
				// The answer is one UDP datagram: if it got lost, ask again now and then. BeamNG
				// answers a repeat at once from the export it finished, or when the running one is done.
				if (now - exportAskedMs >= EXPORT_REASK_MS) {
					JsonObject again = new JsonObject();
					again.addProperty("id", exportingId);
					if (link.send(Protocol.VEXPORT, again)) {
						exportAskedMs = now;   // no new hold: a BeamNG that died meanwhile must still be noticed
					}
				}
				return;
			}
			LOG.warn("No export of car {} after {} s", exportingId, EXPORT_WAIT_MS / 1000);
			exportingId = -1;
		}
		Map<Long, Vehicles.Info> infos = Vehicles.latest();
		for (Map.Entry<Long, VehicleMeshes.Mesh> e : VehicleMeshes.meshes().entrySet()) {
			long id = e.getKey();
			VehicleMeshes.Mesh m = e.getValue();
			long[] settle = SETTLE.compute(id, (k, v) -> v == null || v[0] != m.tv() ? new long[] {m.tv(), now} : v);
			if (drawable(id, m) || now - settle[1] < SETTLE_MS) {
				continue;
			}
			Car c = CARS.get(id);
			if (c != null && (c.state == State.LOADING || c.state == State.UPLOADING)) {
				continue;
			}
			Vehicles.Info info = infos.get(id);
			String model = info != null ? info.model() : "?";
			String key = id + ":" + model + ":" + m.tv() + ":" + settle[1];
			if (TRIES.getOrDefault(key, 0) >= MAX_TRIES) {
				continue;
			}
			JsonObject msg = new JsonObject();
			msg.addProperty("id", id);
			// set before asking: a cached answer can come back on BeamNG's next frame, on the link thread
			exportingTv = m.tv();
			exportingKey = key;
			exportSentMs = now;
			exportAskedMs = now;
			exportSid = link.sessionId();
			exportingId = id;
			// a model's export holds BeamNG still for up to half a minute: keep the link through it
			link.expectStall("exporting the " + model + " for Minecraft", Protocol.EXPORT_STALL_MS);
			if (!link.send(Protocol.VEXPORT, msg)) {
				exportingId = -1;
				return;
			}
			TRIES.merge(key, 1, Integer::sum);
			LOG.info("Asked BeamNG to export car {} ({}) for native drawing", id, model);
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
					"[BeamNG] Building the " + model + " for Minecraft (the first time for a model takes about 20 s)"), true);
			}
			return;
		}
	}

	private static void giveBackTry() {
		TRIES.merge(exportingKey, -1, Integer::sum);
		exportingId = -1;
	}

	/** BeamNG's answer to an export that went wrong: free the queue (busy: try again, uncounted). */
	private static void onError(JsonObject body) {
		String code = body.has("code") ? body.get("code").getAsString() : "";
		long id = body.has("id") && body.get("id").isJsonPrimitive() ? body.get("id").getAsLong() : -1;
		if (exportingId == -1 || id != exportingId || !("export_failed".equals(code) || "busy".equals(code) || "bad_request".equals(code))) {
			return;   // not about the export in flight (carexport.lua names the car)
		}
		if ("busy".equals(code)) {
			TRIES.merge(exportingKey, -1, Integer::sum);
		}
		LOG.warn("BeamNG couldn't export car {}: {} {}", exportingId, code, body.has("msg") ? body.get("msg").getAsString() : "");
		exportingId = -1;
	}

	/** BeamNG replaced this car (same id, another model): export it again once it has settled. */
	public static void invalidate(long id) {
		Car c = CARS.get(id);
		if (c != null) {
			c.tv = -1;
		}
		SETTLE.remove(id);
	}

	/** Dev command "native reload": drop every loaded car, so all are exported and loaded again. */
	public static void reloadAll() {
		CARS.entrySet().removeIf(e -> {
			Car c = e.getValue();
			if (c.state == State.LOADING || c.state == State.UPLOADING) {
				return false;
			}
			release(c);
			return true;
		});
		retryExports();
	}

	/** Dev command "native export": forget failed tries, so every car not drawn is exported again. */
	public static void retryExports() {
		TRIES.clear();
		exportingId = -1;
	}

	private static final long FORGET_AFTER_MS = 30_000;   // a car missing from VMESH this long is released

	private static long renderOffSid = -1;

	/**
	 * Client tick: while Minecraft draws the cars, BeamNG's own picture is unused, so its world
	 * render is switched off (measured: BeamNG GPU load 83% -> 18%, physics unaffected). BeamNG
	 * switches it back on by itself when this client goes away.
	 */
	public static void tick() {
		forgetGoneCars();
		BngLink link = BngLink.get();
		if (!link.connected()) {
			renderOffSid = -1;
			return;
		}
		exportNext(link);
		boolean want = active();
		if (want && renderOffSid != link.sessionId()) {
			JsonObject off = new JsonObject();
			off.addProperty("on", false);
			if (link.send(Protocol.RENDER_MAIN, off)) {
				renderOffSid = link.sessionId();
			}
		} else if (!want && renderOffSid != -1) {
			JsonObject on = new JsonObject();
			on.addProperty("on", true);
			if (link.send(Protocol.RENDER_MAIN, on)) {
				renderOffSid = -1;
			}
		}
	}

	/** Render thread: frees cars that left (out of range, removed, other world) for 30 s. */
	private static void forgetGoneCars() {
		long now = System.currentTimeMillis();
		boolean host = BngWorld.isHostWorld();
		CARS.entrySet().removeIf(e -> {
			Car c = e.getValue();
			if (c.state == State.LOADING || c.state == State.UPLOADING) {
				return false;
			}
			if (host && VehicleMeshes.meshes().containsKey(c.id)) {
				c.lastSeenMs = now;
				return false;
			}
			if (host && now - c.lastSeenMs < FORGET_AFTER_MS) {
				return false;
			}
			release(c);
			return true;
		});
	}

	public static boolean ready(long id) {
		Car c = CARS.get(id);
		return c != null && c.state == State.READY;
	}

	/** Render thread: the car if it can be drawn now (loaded, and its data not stale), else null. */
	static Car readyCar(long id) {
		Car car = CARS.get(id);
		VehicleMeshes.Mesh vm = VehicleMeshes.meshes().get(id);
		if (car == null || car.state != State.READY || vm == null) {
			return null;
		}
		double newest = 0;
		for (VehicleMeshes.Mesh m : VehicleMeshes.meshes().values()) {
			newest = Math.max(newest, m.rxMs());
		}
		return newest - vm.rxMs() > STALE_MS ? null : car;
	}

	public static String describe() {
		StringBuilder b = new StringBuilder(enabled ? "on" : "off");
		for (Car c : CARS.values()) {
			b.append(String.format("   %d %s %s", c.id, c.model, c.state));
			if (c.mesh != null) {
				b.append(String.format(" %dk tris", c.mesh.triangleCount() / 1000));
			}
			if (c.state == State.FAILED) {
				b.append(" (").append(c.error).append(')');
			}
		}
		b.append(String.format("   drawn %d, %.1f ms on the CPU", lastDrawn, lastBendMs));
		return b.toString();
	}

	private static void onExported(JsonObject body) {
		long id = body.get("id").getAsLong();
		if (id != exportingId) {
			// a second answer to a question asked twice (EXPORT_REASK_MS): the car is already here;
			// or an answer about a car that has gone since (removed while it was exported)
			Car have = CARS.get(id);
			VehicleMeshes.Mesh m = VehicleMeshes.meshes().get(id);
			if (m == null || have != null && have.state != State.FAILED && have.tv == m.tv()) {
				return;
			}
		}
		Car car = new Car(id);
		car.model = body.has("model") ? body.get("model").getAsString() : "";
		if (id == exportingId) {
			car.tv = exportingTv;
			exportingId = -1;
		} else {
			VehicleMeshes.Mesh m = VehicleMeshes.meshes().get(id);
			car.tv = m != null ? m.tv() : -1;
		}
		Car old = CARS.put(id, car);
		if (old != null) {
			Minecraft.getInstance().execute(() -> release(old));
		}
		String file = body.get("file").getAsString();
		String side = body.get("side").getAsString();
		LOG.info("BeamNG exported car {} ({}): {} bytes, loading", id, car.model, body.has("bytes") ? body.get("bytes").getAsLong() : -1);
		LOADER.execute(() -> load(car, Path.of(file), Path.of(side)));
	}

	/** Loader thread: read, bind, decode textures; then the render thread uploads. */
	private static void load(Car car, Path file, Path side) {
		long t0 = System.nanoTime();
		try {
			CarModel mesh = CarModel.read(Files.readAllBytes(file));
			JsonObject s = JsonParser.parseString(Files.readString(side)).getAsJsonObject();
			float[] rest = NativeExport.nodesMinecraftAxes(s.getAsJsonArray("p"), NativeExport.unitsPerMetre(s));
			car.nodeCount = s.get("n").getAsInt();
			if (car.nodeCount * 3 != rest.length) {
				throw new IllegalArgumentException("side file has " + rest.length / 3 + " node positions for " + car.nodeCount + " nodes");
			}
			car.binding = FlexBinding.bind(mesh.positions, mesh.normals, mesh.vertexPart, NativeExport.groups(s.getAsJsonObject("groups")), rest);
			car.extras = new NativeExport.MaterialExtra[mesh.materials.size()];
			Map<String, NativeImage> details = new java.util.HashMap<>();
			if (s.has("materials") && s.get("materials").isJsonObject()) {
				Map<String, NativeExport.MaterialExtra> extras = NativeExport.materialExtras(s.getAsJsonObject("materials"));
				for (int i = 0; i < mesh.materials.size(); i++) {
					CarModel.Material mt = mesh.materials.get(i);
					NativeExport.MaterialExtra ex = extras.get(mt.name());
					car.extras[i] = ex;
					if (ex != null && ex.layers() != null) {
						// every layer as BeamNG renders it
						mt = new CarModel.Material(mt.name(), mt.image(), mt.color(), mt.glass(), mt.bng().withSetup(ex.layers()));
					} else {
						if (ex != null && ex.baseColors() != null) {
							mt = new CarModel.Material(mt.name(), mt.image(), mt.color(), mt.glass(), mt.bng().withBaseColors(ex.baseColors()));
						}
						if (ex != null && ex.paletteBaseColor() != null) {
							mt = new CarModel.Material(mt.name(), mt.image(), mt.color(), mt.glass(), mt.bng().withPaletteBaseColor(ex.paletteBaseColor()));
						}
					}
					mesh.materials.set(i, mt);
					if (ex != null && ex.detailFile() != null && !details.containsKey(ex.detailFile())) {
						NativeImage d = readImage(Path.of(ex.detailFile()));
						if (d != null) {
							details.put(ex.detailFile(), d);
						}
					}
				}
			}
			if (s.has("paint") && s.get("paint").isJsonArray()) {
				JsonArray paints = s.getAsJsonArray("paint");
				for (int i = 0; i < 3 && i < paints.size(); i++) {
					if (paints.get(i).isJsonArray() && paints.get(i).getAsJsonArray().size() >= 3) {
						JsonArray p = paints.get(i).getAsJsonArray();
						car.paint[i] = new float[] {p.get(0).getAsFloat(), p.get(1).getAsFloat(), p.get(2).getAsFloat()};
					}
				}
			}
			car.paintData = NativeExport.paintData(s);
			car.sky = bakeSky(mesh);
			car.view = NativeExport.driverView(s);
			NativeImage[] images = new NativeImage[mesh.images.size()];
			for (int i = 0; i < images.length; i++) {
				byte[] bytes = mesh.images.get(i);
				try {
					images[i] = bytes.length > 0 ? fit(decode(bytes)) : null;
				} catch (Exception e) {
					images[i] = null;   // drawn untextured
					LOG.warn("Car {}: image {} of {} unreadable ({} bytes): {}", car.id, i, images.length, bytes.length, e.getMessage());
				}
			}
			long texBytes = 0;
			for (NativeImage im : images) {
				texBytes += im != null ? (long) im.getWidth() * im.getHeight() * 16 / 3 : 0;   // RGBA8 and its mipmaps
			}
			car.normalFormat = normalFormats(mesh, images);
			if (CARS.get(car.id) != car) {   // replaced by a newer export while loading
				closeAll(images);
				details.values().forEach(NativeImage::close);
				return;
			}
			car.mesh = mesh;
			car.images = images;
			car.detailImages = details;
			car.nodes = new float[car.nodeCount * 3];
			car.state = State.UPLOADING;
			LOG.info("Car {} ({}): {} vertices, {} triangles, {} images ({} MB on the GPU) read and bound in {} ms", car.id, car.model,
				mesh.vertexCount(), mesh.triangleCount(), images.length, texBytes >> 20, String.format("%.0f", (System.nanoTime() - t0) / 1e6));
			Minecraft.getInstance().execute(() -> upload(car));
		} catch (Throwable e) {
			car.error = e.toString();
			car.state = State.FAILED;
			LOG.warn("Could not load the exported car {}", car.id, e);
		}
	}

	/** Render thread: textures, vertex arrays. */
	private static void upload(Car car) {
		if (car.state != State.UPLOADING || CARS.get(car.id) != car) {
			closeAll(car.images);
			car.images = null;
			car.detailImages.values().forEach(NativeImage::close);
			car.detailImages = new java.util.HashMap<>();
			return;
		}
		int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		int prevBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
		int prevActive = GlStateManager._getActiveTexture();
		IntBuffer idx = null;
		try {
			CarModel m = car.mesh;
			car.textures = new int[car.images.length];
			for (int i = 0; i < car.images.length; i++) {
				NativeImage img = car.images[i];
				if (img == null) {
					continue;
				}
				int id = TextureUtil.generateTextureId();
				int levels = 1 + (int) Math.floor(Math.log(Math.max(img.getWidth(), img.getHeight())) / Math.log(2));
				TextureUtil.prepareImage(id, levels - 1, img.getWidth(), img.getHeight());
				img.upload(0, 0, 0, false);
				GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
				GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
				GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
				GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
				GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
				img.close();
				car.images[i] = null;
				car.textures[i] = id;
			}
			car.images = null;
			for (Map.Entry<String, NativeImage> e : car.detailImages.entrySet()) {
				car.detailTextures.put(e.getKey(), uploadTexture(e.getValue()));
			}
			car.detailImages = new java.util.HashMap<>();

			// Index buffer grouped by material, see-through glass last; BeamNG's invisible helper
			// material ("invis") isn't drawn at all.
			List<CarModel.Part> parts = new ArrayList<>(m.parts);
			parts.removeIf(p -> p.material() >= 0 && p.material() < m.materials.size() && m.materials.get(p.material()).bng().invisible());
			parts.sort(Comparator.comparingInt((CarModel.Part p) -> isGlass(m, p.material()) ? 1 : 0).thenComparingInt(CarModel.Part::material));
			int total = parts.stream().mapToInt(p -> p.indices().length).sum();
			car.indexCount = total;
			idx = MemoryUtil.memAllocInt(total);
			int first = 0;
			for (CarModel.Part p : parts) {
				idx.put(p.indices());
				int[] last = car.draws.isEmpty() ? null : car.draws.get(car.draws.size() - 1);
				if (last != null && last[2] == p.material() && last[0] + last[1] == first) {
					last[1] += p.indices().length;
				} else {
					car.draws.add(new int[] {first, p.indices().length, p.material()});
				}
				first += p.indices().length;
			}
			idx.flip();

			car.vao = GL30.glGenVertexArrays();
			GL30.glBindVertexArray(car.vao);
			uploadVertices(car, m);
			car.ebo = GL15.glGenBuffers();
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, car.ebo);
			GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idx, GL15.GL_STATIC_DRAW);
			car.nodeBuffer = GL15.glGenBuffers();
			GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, car.nodeBuffer);
			GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (long) car.nodeCount * 16, GL15.GL_STREAM_DRAW);
			GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
			car.nodeTexture = GL11.glGenTextures();
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + CarShader.NODE_UNIT);   // plain GL: past GlStateManager's units
			GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, car.nodeTexture);
			GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, car.nodeBuffer);
			GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
			GL13.glActiveTexture(prevActive);
			car.nodeData = MemoryUtil.memAllocFloat(car.nodeCount * 4);
			car.state = State.READY;
			LOG.info("Car {} ({}) ready to draw: {} draw calls", car.id, car.model, car.draws.size());
		} catch (Throwable e) {
			car.error = e.toString();
			release(car);
			LOG.warn("Could not upload car {}", car.id, e);
		} finally {
			if (idx != null) {
				MemoryUtil.memFree(idx);
			}
			GL30.glBindVertexArray(prevVao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuf);
			// plain GL: if the node texture failed mid-way GL is on unit 17 while GlStateManager still
			// believes prevActive, and its own call would then do nothing
			GL13.glActiveTexture(prevActive);
			BufferUploader.invalidate();
		}
	}

	/**
	 * Render thread, the car's VAO bound: the static vertex data. Per vertex its position and
	 * normal in its node frame (the shader rebuilds them from the moved nodes, as
	 * FlexBinding.deform does), UV0, UV1 and the rest position (the shader hides triangles torn far
	 * beyond their rest shape, as BeamNG hides a broken flexmesh); and the frame's three node indices.
	 */
	private static void uploadVertices(Car car, CarModel m) {
		int nv = m.vertexCount();
		FloatBuffer local = MemoryUtil.memAllocFloat(nv * 3), localN = MemoryUtil.memAllocFloat(nv * 3);
		IntBuffer frames = MemoryUtil.memAllocInt(nv * 3);
		FloatBuffer data = MemoryUtil.memAllocFloat(nv * 14);
		try {
			car.binding.write(frames, local, localN);
			frames.flip();
			for (int i = 0; i < nv; i++) {
				data.put(local.get(i * 3)).put(local.get(i * 3 + 1)).put(local.get(i * 3 + 2))
					.put(localN.get(i * 3)).put(localN.get(i * 3 + 1)).put(localN.get(i * 3 + 2))
					.put(m.uvs[i * 2]).put(m.uvs[i * 2 + 1]).put(m.uvs2[i * 2]).put(m.uvs2[i * 2 + 1])
					.put(m.positions[i * 3]).put(m.positions[i * 3 + 1]).put(m.positions[i * 3 + 2])
					.put(car.sky != null && i < car.sky.length ? car.sky[i] : 1F);
			}
			data.flip();
			car.vboStatic = GL15.glGenBuffers();
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, car.vboStatic);
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
			int[] sizes = {3, 3, 2, 2, 3, 1};   // Local, LocalN, UV0, UV1, Rest, Sky (CarShader's attributes 0-4, 6)
			int offset = 0;
			for (int a = 0; a < sizes.length; a++) {
				int location = a < 5 ? a : 6;   // 5 is the frame's node indices (integer, below)
				GL20.glEnableVertexAttribArray(location);
				GL20.glVertexAttribPointer(location, sizes[a], GL11.GL_FLOAT, false, 56, offset);
				offset += sizes[a] * 4;
			}
			car.vboFrame = GL15.glGenBuffers();
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, car.vboFrame);
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, frames, GL15.GL_STATIC_DRAW);
			GL20.glEnableVertexAttribArray(5);
			GL30.glVertexAttribIPointer(5, 3, GL11.GL_INT, 12, 0);
		} finally {
			MemoryUtil.memFree(local);
			MemoryUtil.memFree(localN);
			MemoryUtil.memFree(frames);
			MemoryUtil.memFree(data);
		}
	}

	/** Loader thread: the sky each vertex sees, with everything but glass and BeamNG's hidden helper blocking it. */
	private static float[] bakeSky(CarModel mesh) {
		long t0 = System.nanoTime();
		List<int[]> occluders = new ArrayList<>();
		for (CarModel.Part p : mesh.parts) {
			CarModel.Material mt = p.material() >= 0 && p.material() < mesh.materials.size() ? mesh.materials.get(p.material()) : null;
			if (mt == null || !mt.glass() && !mt.bng().invisible()) {
				occluders.add(p.indices());
			}
		}
		float[] sky = dev.bngmc.bridge.nativecar.SkyOcclusion.bake(mesh.positions, mesh.normals, occluders);
		LOG.info("Sky occlusion for {} vertices baked in {} ms", sky.length, String.format("%.0f", (System.nanoTime() - t0) / 1e6));
		return sky;
	}

	/** Loader thread: how each image used as a normal map stores its normals (NormalFormat). */
	private static int[] normalFormats(CarModel mesh, NativeImage[] images) {
		int[] out = new int[images.length];
		boolean[] done = new boolean[images.length];
		for (CarModel.Material mt : mesh.materials) {
			for (int l = 0; l < mt.bng().layers(); l++) {
				int image = mt.bng().layer(l).maps()[BngMaterial.NORMAL];
				if (image >= 0 && image < images.length && images[image] != null && !done[image]) {
					NativeImage img = images[image];
					out[image] = dev.bngmc.bridge.nativecar.NormalFormat.of(img::getPixelRGBA, img.getWidth(), img.getHeight());
					done[image] = true;
				}
			}
		}
		return out;
	}

	/** A PNG file into a NativeImage (off-heap copy: see the note in load), or null. */
	/**
	 * Largest texture side kept on the GPU. Car mods ship 4K and 8K maps: the SF90's 123 images
	 * took 1.8 GB of video memory at full size (326 MB at 1024). Two such cars, a shader pack and
	 * BeamNG filled the 8 GB card and the NVIDIA driver reset (2026-10-03, 16:18).
	 */
	private static final int MAX_TEXTURE = 1024;

	/** Loader thread: the image scaled down to fit MAX_TEXTURE (the original closed), or as it is. */
	private static NativeImage fit(NativeImage img) {
		int w = img.getWidth(), h = img.getHeight();
		if (Math.max(w, h) <= MAX_TEXTURE) {
			return img;
		}
		double s = (double) MAX_TEXTURE / Math.max(w, h);
		NativeImage out = new NativeImage(img.format(), Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s)), false);
		try {
			img.resizeSubRectTo(0, 0, w, h, out);
		} finally {
			img.close();
		}
		return out;
	}

	/**
	 * A texture file as BeamNG ships it: PNG through Minecraft's reader, anything else (JPEG) through
	 * stb, which Minecraft's reader is built on but only lets PNGs into (CarModel.isPng).
	 */
	static NativeImage decode(byte[] file) throws IOException {
		// read(byte[]) copies the file onto LWJGL's small thread stack: "Out of stack space" for
		// BeamNG's 2-3 MB textures (measured). Hand it an off-heap copy instead.
		ByteBuffer buf = MemoryUtil.memAlloc(Math.max(1, file.length));
		try {
			buf.put(file).flip();
			if (CarModel.isPng(file)) {
				return NativeImage.read(buf);
			}
			int[] w = new int[1], h = new int[1], channels = new int[1];
			ByteBuffer px = STBImage.stbi_load_from_memory(buf, w, h, channels, 4);
			if (px == null) {
				throw new IOException("not an image stb reads: " + STBImage.stbi_failure_reason());
			}
			try {
				// stb's RGBA bytes read little-endian are the ABGR ints setPixelRGBA writes back as they are
				IntBuffer src = px.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
				NativeImage img = new NativeImage(NativeImage.Format.RGBA, w[0], h[0], false);
				for (int y = 0; y < h[0]; y++) {
					for (int x = 0; x < w[0]; x++) {
						img.setPixelRGBA(x, y, src.get(y * w[0] + x));
					}
				}
				return img;
			} finally {
				STBImage.stbi_image_free(px);
			}
		} finally {
			MemoryUtil.memFree(buf);
		}
	}

	private static NativeImage readImage(Path file) {
		try {
			return fit(decode(Files.readAllBytes(file)));
		} catch (Exception e) {
			LOG.warn("Could not read {}: {}", file.getFileName(), e.toString());
			return null;
		}
	}

	/** Render thread: a mipmapped, repeating GL texture; the image is closed. */
	private static int uploadTexture(NativeImage img) {
		int id = TextureUtil.generateTextureId();
		int levels = 1 + (int) Math.floor(Math.log(Math.max(img.getWidth(), img.getHeight())) / Math.log(2));
		TextureUtil.prepareImage(id, levels - 1, img.getWidth(), img.getHeight());
		img.upload(0, 0, 0, false);
		GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
		img.close();
		return id;
	}

	private static void closeAll(NativeImage[] images) {
		if (images == null) {
			return;
		}
		for (int i = 0; i < images.length; i++) {
			if (images[i] != null) {
				images[i].close();
				images[i] = null;
			}
		}
	}

	private static boolean isGlass(CarModel m, int material) {
		return material >= 0 && material < m.materials.size() && m.materials.get(material).glass();
	}

	/** Render thread. Frees textures, buffers and decoded images; the car won't draw again. */
	private static void release(Car c) {
		closeAll(c.images);
		c.images = null;
		c.detailImages.values().forEach(NativeImage::close);
		c.detailImages = new java.util.HashMap<>();
		c.detailTextures.values().forEach(TextureUtil::releaseTextureId);
		c.detailTextures.clear();
		if (c.textures != null) {
			for (int t : c.textures) {
				if (t != 0) {
					TextureUtil.releaseTextureId(t);
				}
			}
		}
		if (c.vao != 0) {
			GL30.glDeleteVertexArrays(c.vao);
			GL15.glDeleteBuffers(c.vboStatic);
			GL15.glDeleteBuffers(c.vboFrame);
			GL15.glDeleteBuffers(c.ebo);
			c.vao = 0;
		}
		if (c.nodeTexture != 0) {
			GL11.glDeleteTextures(c.nodeTexture);
			c.nodeTexture = 0;
		}
		if (c.nodeBuffer != 0) {
			GL15.glDeleteBuffers(c.nodeBuffer);
			c.nodeBuffer = 0;
		}
		PackCarRenderer.release(c);
		if (c.nodeData != null) {
			MemoryUtil.memFree(c.nodeData);
			c.nodeData = null;
		}
		c.state = State.FAILED;
	}

	/** One frame's view of the world. */
	private record View(Matrix4f projection, Matrix4f modelView, Vec3 cam, Frustum frustum, float partialTick) {
	}

	/** WorldRenderEvents.AFTER_ENTITIES, render thread. */
	public static void render(WorldRenderContext ctx) {
		if (!active() || CARS.isEmpty()) {
			lastDrawn = 0;
			return;
		}
		// the context's projection: a shader pack leaves another one in RenderSystem here (seen: the car a speck)
		View view = new View(new Matrix4f(ctx.projectionMatrix()), new Matrix4f(ctx.positionMatrix()), ctx.camera().getPosition(),
			ctx.frustum(), ctx.tickCounter().getGameTimeDeltaPartialTick(true));
		if (!ShaderCompat.packInUse()) {
			draw(view, false);
			return;
		}
		// Under a pack the cars are entities (VehicleRenderer, PackCarRenderer), lit by it; a car
		// Minecraft didn't draw this frame (no entity on this client: beyond its tracking range) is
		// put over the frame as before
		boolean entities = PackCarRenderer.active();
		if (entities && CARS.values().stream().allMatch(c -> c.state != State.READY || c.packDrawnFrame == frame)) {
			return;
		}
		if (ShaderCarTarget.debug == 3 && passLogs < 8) {
			passLogs++;
			LOG.info("Pack pass {}: shadow {}, fbo {}, proj m00 {} m11 {} m22 {} m32 {}, view m11 {} m21 {}, cam {}", passLogs, ShaderCompat.shadowPass(),
				GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), view.projection().m00(), view.projection().m11(), view.projection().m22(),
				view.projection().m32(), view.modelView().m11(), view.modelView().m21(), view.cam());
		}
		if (!packLogged) {
			packLogged = true;
			LOG.info("Shader pack on: projection {} (RenderSystem {}), model-view {}, GL error {}", view.projection(),
				RenderSystem.getProjectionMatrix(), view.modelView(), GL11.glGetError());
		}
		// a shader pack: into a target of our own, put over the finished frame by renderAfterShaders
		if (ShaderCompat.shadowPass() || !ShaderCarTarget.begin()) {
			return;
		}
		boolean drew = false;
		try {
			drew = draw(view, entities);
		} finally {
			ShaderCarTarget.end(drew);
		}
	}

	private static boolean packLogged;
	private static int passLogs;

	/** GameRenderer.renderLevel, right after the level (and a shader pack's last pass): the cars over the frame. */
	public static void renderAfterShaders() {
		if (!ShaderCompat.packInUse()) {
			ShaderCarTarget.release();
			return;
		}
		var target = Minecraft.getInstance().getMainRenderTarget();
		ShaderCarTarget.composite(target.frameBufferId, target.width, target.height);
	}

	/** False if nothing could be drawn (no world, the shader not built). */
	private static boolean draw(View view, boolean skipEntityDrawn) {
		CrossoverCoords.Region region = BngWorld.region();
		Minecraft mc = Minecraft.getInstance();
		if (region == null || mc.level == null || !CarShader.ready()) {
			return false;
		}
		Vec3 cam = view.cam();
		Frustum frustum = view.frustum();
		long t0 = System.nanoTime();
		int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		int prevBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
		// The car shader uses units 0-17, past the 12 GlStateManager tracks, so all of it is plain GL:
		// what was bound is read here and put back after, and GlStateManager's record stays true.
		int prevActive = GlStateManager._getActiveTexture();
		int[] prevTex = new int[CarShader.UNITS];
		for (int i = 0; i < prevTex.length; i++) {
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
			prevTex[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		}
		int drawn = 0;
		// Plain GL for the program and the depth, blend, cull and mask state, put back exactly after: a
		// shader pack (Iris) keeps its own record of these, skips binds it thinks redundant and holds
		// back depth and colour masks during its passes (seen: the car drawn with another program's
		// samplers, a speck), and GlStateManager's record stays true as nothing it knows of changes
		GlSave saved = GlSave.take();
		try {
			GL20.glUseProgram(CarShader.program());
			CarShader.begin(view.projection(), view.modelView(), mc.level, cam, view.partialTick(), (float) region.scale());
			GL11.glEnable(GL11.GL_DEPTH_TEST);
			GL11.glDepthFunc(GL11.GL_LEQUAL);
			GL11.glDisable(GL11.GL_CULL_FACE);
			GL11.glColorMask(true, true, true, true);
			GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
			// A car is stale when its data lags the newest of any car: when BeamNG stalls (a swap, a
			// first export) every car keeps its last pose instead of vanishing
			double newest = 0;
			for (VehicleMeshes.Mesh m : VehicleMeshes.meshes().values()) {
				newest = Math.max(newest, m.rxMs());
			}
			for (Car car : CARS.values()) {
				if (skipEntityDrawn && car.packDrawnFrame == frame) {
					continue;
				}
				VehicleMeshes.Mesh vm = VehicleMeshes.meshes().get(car.id);
				MeshTimeline.Pose pose = pose(car.id);
				if (car.state != State.READY || vm == null || pose == null || pose.nodeCount() != car.nodeCount || newest - vm.rxMs() > STALE_MS) {
					continue;
				}
				try {
					if (draw(car, pose, region, cam, frustum)) {
						drawn++;
					}
				} catch (RuntimeException e) {
					car.error = e.toString();
					car.state = State.FAILED;   // drop it rather than throw every frame
					LOG.warn("Car {} can't be drawn", car.id, e);
				}
			}
		} finally {
			saved.restore();
			GL30.glBindVertexArray(prevVao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuf);
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + CarShader.NODE_UNIT);
			GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
			for (int i = prevTex.length - 1; i >= 0; i--) {
				GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex[i]);
			}
			GL13.glActiveTexture(prevActive);
			GL20.glUseProgram(prevProgram);
			BufferUploader.invalidate();
		}
		lastDrawn = drawn;
		lastBendMs = (System.nanoTime() - t0) / 1e6;
		return drawn > 0;
	}

	/** The GL state the cars change, as it was: put back with plain GL so no one's record of it goes stale. */
	private record GlSave(boolean depthTest, boolean depthMask, int depthFunc, boolean cull, boolean blend, int srcRgb, int dstRgb, int srcA,
		int dstA, boolean[] colorMask) {
		static GlSave take() {
			try (MemoryStack st = MemoryStack.stackPush()) {
				java.nio.ByteBuffer m = st.malloc(4);
				GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, m);
				return new GlSave(GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
					GL11.glIsEnabled(GL11.GL_CULL_FACE), GL11.glIsEnabled(GL11.GL_BLEND), GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
					GL11.glGetInteger(GL14.GL_BLEND_DST_RGB), GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
					new boolean[] {m.get(0) != 0, m.get(1) != 0, m.get(2) != 0, m.get(3) != 0});
			}
		}

		void restore() {
			set(GL11.GL_DEPTH_TEST, depthTest);
			GL11.glDepthMask(depthMask);
			GL11.glDepthFunc(depthFunc);
			set(GL11.GL_CULL_FACE, cull);
			set(GL11.GL_BLEND, blend);
			GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
			GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
		}

		private static void set(int cap, boolean on) {
			if (on) {
				GL11.glEnable(cap);
			} else {
				GL11.glDisable(cap);
			}
		}
	}

	/** Render thread: car.nodes (this frame's pose, Minecraft axes) into the node buffer the shaders bend with. */
	static void uploadNodes(Car car) {
		FloatBuffer nd = car.nodeData;
		nd.clear();
		float[] n = car.nodes;
		for (int i = 0; i < n.length; i += 3) {
			nd.put(n[i]).put(n[i + 1]).put(n[i + 2]).put(0F);
		}
		nd.flip();
		GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, car.nodeBuffer);
		// a fresh buffer each frame, so the driver needn't wait for the GPU to finish the last one
		GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (long) car.nodeCount * 16, GL15.GL_STREAM_DRAW);
		GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0, nd);
		GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
	}

	/** Room around a car's nodes that its mesh may reach (a bumper, a mirror), in blocks. */
	private static final double CULL_MARGIN = 1.5;

	/** Draws the car unless it is off screen; true if drawn. */
	private static boolean draw(Car car, MeshTimeline.Pose pose, CrossoverCoords.Region region, Vec3 cam, Frustum frustum) {
		pose.nodesMinecraftAxes(car.nodes);
		V3 base = CrossoverCoords.canonicalToMinecraftPosition(pose.pos(), region);
		statsFrame(base.x(), base.y(), base.z(), cam);
		if (frustum != null && !frustum.isVisible(bounds(car.nodes, base, (float) region.scale()))) {
			return false;
		}
		uploadNodes(car);
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + CarShader.NODE_UNIT);
		GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, car.nodeTexture);
		CarShader.car((float) (base.x() - cam.x), (float) (base.y() - cam.y), (float) (base.z() - cam.z));
		CarShader.paints(car.paint, car.paintData);
		GL30.glBindVertexArray(car.vao);
		CarModel m = car.mesh;
		for (int[] d : car.draws) {
			int mat = d[2];
			CarModel.Material material = mat >= 0 && mat < m.materials.size() ? m.materials.get(mat) : null;
			BngMaterial bng = material != null ? material.bng() : BngMaterial.parse("", null, -1, t -> -1);
			boolean glass = material != null && material.glass();
			NativeExport.MaterialExtra extra = mat >= 0 && mat < car.extras.length ? car.extras[mat] : null;
			int detail = extra != null && extra.detailFile() != null ? car.detailTextures.getOrDefault(extra.detailFile(), 0) : 0;
			CarShader.material(bng, car.textures, car.normalFormat, glass, extra, detail);
			// back faces culled where BeamNG culls them (BngMaterial.doubleSided; the export's triangles
			// are counter-clockwise from the front, 99-100% measured on three cars)
			if (bng.doubleSided()) {
				GL11.glDisable(GL11.GL_CULL_FACE);
			} else {
				GL11.glEnable(GL11.GL_CULL_FACE);
			}
			if (glass) {
				GL11.glEnable(GL11.GL_BLEND);   // alpha blended too: a shader pack's car target holds premultiplied colour
				GL11.glDepthMask(false);
			} else {
				GL11.glDisable(GL11.GL_BLEND);
				GL11.glDepthMask(true);
			}
			GL11.glDrawElements(GL11.GL_TRIANGLES, d[1], GL11.GL_UNSIGNED_INT, (long) d[0] * 4);
		}
		return true;
	}

	/** The world box around the car's nodes (metres, Minecraft axes, relative to base), plus CULL_MARGIN. */
	private static AABB bounds(float[] nodes, V3 base, float scale) {
		float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE;
		float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
		for (int i = 0; i < nodes.length; i += 3) {
			x0 = Math.min(x0, nodes[i]);
			x1 = Math.max(x1, nodes[i]);
			y0 = Math.min(y0, nodes[i + 1]);
			y1 = Math.max(y1, nodes[i + 1]);
			z0 = Math.min(z0, nodes[i + 2]);
			z1 = Math.max(z1, nodes[i + 2]);
		}
		return new AABB(base.x() + x0 * scale, base.y() + y0 * scale, base.z() + z0 * scale,
			base.x() + x1 * scale, base.y() + y1 * scale, base.z() + z1 * scale).inflate(CULL_MARGIN);
	}
}
