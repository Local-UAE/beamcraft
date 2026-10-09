package dev.bngmc.bridge.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.coords.Quat;
import dev.bngmc.bridge.coords.V3;

/**
 * One STATE message from BeamNG, parsed. All vectors are canonical (= BeamNG world) metres.
 * Fields BeamNG didn't send are null (objects) or NaN (numbers).
 */
public record BngState(
	long seq,
	double bngTimeMs,
	double rxTimeMs,
	String level,
	Boolean paused,
	double fps,
	Vehicle vehicle,
	Camera camera) {

	/** The player's vehicle. {@code rot} maps vehicle-local axes to world; BeamNG vehicles face local -Y. */
	public record Vehicle(long id, String model, V3 pos, Quat rot, V3 fwd, V3 up, V3 vel, double speed) {
	}

	/**
	 * BeamNG's camera as rendered. {@code overridden} is true when the frame used our pose;
	 * {@code appliedSeq} is the newest Minecraft camera sequence BeamNG applied.
	 */
	public record Camera(V3 pos, Quat rot, double fovDeg, String mode, boolean overridden, long appliedSeq) {
	}

	public static BngState parse(Wire.Envelope env, double rxTimeMs) {
		JsonObject o = env.body();
		Vehicle veh = null;
		if (o.has("veh") && o.get("veh").isJsonObject()) {
			JsonObject v = o.getAsJsonObject("veh");
			veh = new Vehicle(longOr(v, "id", -1), str(v, "model"), vec(v, "pos"), quat(v, "rot"), vec(v, "fwd"), vec(v, "up"),
				vec(v, "vel"), num(v, "speed"));
		}
		Camera cam = null;
		if (o.has("cam") && o.get("cam").isJsonObject()) {
			JsonObject c = o.getAsJsonObject("cam");
			cam = new Camera(vec(c, "pos"), quat(c, "rot"), num(c, "fov"), str(c, "mode"), bool(c, "ovr"), longOr(c, "cseq", 0));
		}
		Boolean paused = o.has("paused") && o.get("paused").isJsonPrimitive() ? o.get("paused").getAsBoolean() : null;
		return new BngState(env.seq(), env.ts(), rxTimeMs, str(o, "level"), paused, num(o, "fps"), veh, cam);
	}

	static double num(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.NaN;
	}

	static long longOr(JsonObject o, String k, long def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : def;
	}

	static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	static boolean bool(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsBoolean();
	}

	static V3 vec(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 3) {
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		return new V3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
	}

	static Quat quat(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 4) {
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		return new Quat(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble());
	}
}
