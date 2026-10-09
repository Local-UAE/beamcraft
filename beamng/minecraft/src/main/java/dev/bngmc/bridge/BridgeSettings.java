package dev.bngmc.bridge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The crossover's settings (client SettingsScreen: O, or "BeamNG settings" in the pause menu),
 * kept in config/bngbridge.json. One immutable record swapped whole, so the server thread
 * (terrain, hits) and the render thread (camera, HUD) never see half a change. A missing or broken
 * file, or a value out of range, falls back to the default for that value. Pure Java apart from
 * the file, unit-tested.
 */
public record BridgeSettings(
	Ground ground,
	int slopeBlocks,
	boolean seaWater,
	boolean openBuildings,
	int dentPercent,
	int shovePercent,
	boolean crashHurts,
	boolean carsHurt,
	double chaseDistance,
	double chaseHeight,
	boolean chaseFollows,
	boolean dashboard,
	boolean statusHud) {

	/** How a real-terrain world's ground reaches BeamNG (TerrainSync). */
	public enum Ground {
		/** Steps blurred into slopes a car drives up: the arcade feel. */
		SMOOTH("smooth", 1),
		/** Every block a step, as in Minecraft: a one-block step stops a car. */
		BLOCKS("blocks", 2);

		public final String key;
		/** Terrain samples per block each way: two make a step 63 degrees steep over a quarter metre. */
		public final int samplesPerBlock;

		Ground(String key, int samplesPerBlock) {
			this.key = key;
			this.samplesPerBlock = samplesPerBlock;
		}

		public static Ground of(String key, Ground fallback) {
			for (Ground g : values()) {
				if (g.key.equals(key)) {
					return g;
				}
			}
			return fallback;
		}
	}

	public static final int MIN_SLOPE_BLOCKS = 1, MAX_SLOPE_BLOCKS = 4;
	public static final int MAX_PERCENT = 300;
	public static final double MIN_CHASE_DISTANCE = 3.0, MAX_CHASE_DISTANCE = 12.0;
	public static final double MIN_CHASE_HEIGHT = 0.0, MAX_CHASE_HEIGHT = 3.0;

	/**
	 * What the build did before there were settings (the chase camera's 5.5 m and 1.2 m were
	 * measured), except the debug view: it starts off, F9 or the screen turn it on.
	 */
	public static final BridgeSettings DEFAULTS = new BridgeSettings(Ground.SMOOTH, 2, true, true, 100, 100, true, true, 5.5, 1.2, true, true, false);

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static volatile BridgeSettings current = DEFAULTS;
	private static Path file;

	public BridgeSettings {
		ground = ground != null ? ground : Ground.SMOOTH;
		slopeBlocks = Math.max(MIN_SLOPE_BLOCKS, Math.min(MAX_SLOPE_BLOCKS, slopeBlocks));
		dentPercent = Math.max(0, Math.min(MAX_PERCENT, dentPercent));
		shovePercent = Math.max(0, Math.min(MAX_PERCENT, shovePercent));
		chaseDistance = clamp(chaseDistance, MIN_CHASE_DISTANCE, MAX_CHASE_DISTANCE, 5.5);
		chaseHeight = clamp(chaseHeight, MIN_CHASE_HEIGHT, MAX_CHASE_HEIGHT, 1.2);
	}

	private static double clamp(double v, double lo, double hi, double fallback) {
		return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : fallback;
	}

	/** Blocks each way the terrain blur reaches: none for real blocks. */
	public int slopeRadius() {
		return ground == Ground.BLOCKS ? 0 : slopeBlocks;
	}

	/** One value changed by its JSON name, as in the file (the "set" dev command); unknown names change nothing. */
	public BridgeSettings with(String name, JsonElement value) {
		JsonObject o = toJson();
		if (o.has(name)) {
			o.add(name, value);
		}
		return fromJson(o);
	}

	/** A percent setting as a factor. */
	public static double factor(int percent) {
		return percent / 100.0;
	}

	public BridgeSettings withGround(Ground v) {
		return new BridgeSettings(v, slopeBlocks, seaWater, openBuildings, dentPercent, shovePercent, crashHurts, carsHurt, chaseDistance, chaseHeight,
			chaseFollows, dashboard, statusHud);
	}

	public BridgeSettings withStatusHud(boolean v) {
		return new BridgeSettings(ground, slopeBlocks, seaWater, openBuildings, dentPercent, shovePercent, crashHurts, carsHurt, chaseDistance,
			chaseHeight, chaseFollows, dashboard, v);
	}

	// -- JSON ---------------------------------------------------------------------------------------

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("ground", ground.key);
		o.addProperty("slopeBlocks", slopeBlocks);
		o.addProperty("seaWater", seaWater);
		o.addProperty("openBuildings", openBuildings);
		o.addProperty("dentPercent", dentPercent);
		o.addProperty("shovePercent", shovePercent);
		o.addProperty("crashHurts", crashHurts);
		o.addProperty("carsHurt", carsHurt);
		o.addProperty("chaseDistance", chaseDistance);
		o.addProperty("chaseHeight", chaseHeight);
		o.addProperty("chaseFollows", chaseFollows);
		o.addProperty("dashboard", dashboard);
		o.addProperty("statusHud", statusHud);
		return o;
	}

	/** Every value that is there and the right type is read (then clamped); the rest are the defaults. */
	public static BridgeSettings fromJson(JsonObject o) {
		BridgeSettings d = DEFAULTS;
		return new BridgeSettings(
			Ground.of(str(o, "ground", d.ground.key), d.ground),
			(int) num(o, "slopeBlocks", d.slopeBlocks),
			bool(o, "seaWater", d.seaWater),
			bool(o, "openBuildings", d.openBuildings),
			(int) num(o, "dentPercent", d.dentPercent),
			(int) num(o, "shovePercent", d.shovePercent),
			bool(o, "crashHurts", d.crashHurts),
			bool(o, "carsHurt", d.carsHurt),
			num(o, "chaseDistance", d.chaseDistance),
			num(o, "chaseHeight", d.chaseHeight),
			bool(o, "chaseFollows", d.chaseFollows),
			bool(o, "dashboard", d.dashboard),
			bool(o, "statusHud", d.statusHud));
	}

	private static JsonElement prim(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e : null;
	}

	private static String str(JsonObject o, String k, String d) {
		JsonElement e = prim(o, k);
		return e != null && e.getAsJsonPrimitive().isString() ? e.getAsString() : d;
	}

	private static double num(JsonObject o, String k, double d) {
		JsonElement e = prim(o, k);
		return e != null && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : d;
	}

	private static boolean bool(JsonObject o, String k, boolean d) {
		JsonElement e = prim(o, k);
		return e != null && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : d;
	}

	// -- the current settings -------------------------------------------------------------------------

	public static BridgeSettings get() {
		return current;
	}

	/** From the file; the defaults (and a new file) when there is none or it can't be read. */
	public static void load(Path path) {
		file = path;
		if (!Files.exists(path)) {
			save(DEFAULTS);
			return;
		}
		try {
			JsonElement e = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
			current = e.isJsonObject() ? fromJson(e.getAsJsonObject()) : DEFAULTS;
			LOG.info("Crossover settings from {}: {}", path, current.toJson());
		} catch (IOException | RuntimeException e) {
			LOG.warn("Couldn't read the crossover settings {} ({}): using the defaults", path, e.toString());
			current = DEFAULTS;
		}
	}

	/** Takes effect at once (the terrain rebuilds by itself when its look changed) and is written to the file. */
	public static void set(BridgeSettings s) {
		if (s.equals(current) && file != null && Files.exists(file)) {
			return;
		}
		save(s);
	}

	private static void save(BridgeSettings s) {
		current = s;
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".part");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(s.toJson()), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOG.warn("Couldn't save the crossover settings {}: {}", file, e.toString());
		}
	}
}
