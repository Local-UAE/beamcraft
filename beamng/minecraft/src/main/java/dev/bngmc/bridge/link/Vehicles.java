package dev.bngmc.bridge.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.coords.Quat;
import dev.bngmc.bridge.coords.V3;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Newest VEHICLES message, parsed once on the link thread and published as an immutable map
 * (latest wins). Server and client proxies both read it, so they agree with each other.
 */
public final class Vehicles {
	private Vehicles() {
	}

	/** One BeamNG vehicle, canonical units. {@code axes} are the world-space OBB half-axis vectors. */
	public record Info(long id, String model, V3 pos, Quat rot, V3 fwd, V3 up, V3 vel, V3 center, V3[] axes, boolean active,
					   boolean player, double rxMs) {
		/** Box centre extrapolated with the vehicle's velocity to time {@code nowMs} (at most 250 ms ahead). */
		public V3 centerAt(double nowMs) {
			double dt = Math.max(0, Math.min(0.25, (nowMs - rxMs) / 1000.0));
			V3 v = vel != null ? vel : V3.ZERO;
			// A spawned or reset vehicle reports a huge one-frame velocity (52 km/s measured on
			// gridmap_v2): don't extrapolate anything faster than a car can go.
			if (v.length() > MAX_PLAUSIBLE_SPEED) {
				return center;
			}
			return center.add(v.scale(dt));
		}
	}

	/** m/s; above this a reported velocity is a teleport artefact. */
	public static final double MAX_PLAUSIBLE_SPEED = 150.0;

	private static volatile Map<Long, Info> latest = Collections.emptyMap();
	private static volatile double latestRxMs;

	public static void install(BngLink link) {
		link.onMessage(Protocol.VEHICLES, Vehicles::accept);
	}

	public static Map<Long, Info> latest() {
		return latest;
	}

	/** Milliseconds since the last VEHICLES message (infinite if none). */
	public static double ageMs() {
		return latestRxMs == 0 ? Double.POSITIVE_INFINITY : BngLink.nowMs() - latestRxMs;
	}

	static void accept(Wire.Envelope env) {
		double now = BngLink.nowMs();
		Map<Long, Info> m = new HashMap<>();
		JsonElement list = env.body().get("list");
		if (list != null && list.isJsonArray()) {
			for (JsonElement e : list.getAsJsonArray()) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject o = e.getAsJsonObject();
				V3 center = BngState.vec(o, "center");
				V3[] axes = axes(o);
				if (center == null || axes == null) {
					continue;
				}
				long id = BngState.longOr(o, "id", -1);
				m.put(id, new Info(id, BngState.str(o, "model"), BngState.vec(o, "pos"), BngState.quat(o, "rot"), BngState.vec(o, "fwd"),
					BngState.vec(o, "up"), BngState.vec(o, "vel"), center, axes, BngState.bool(o, "active"), BngState.bool(o, "player"), now));
			}
		}
		latest = Collections.unmodifiableMap(m);
		latestRxMs = now;
	}

	private static V3[] axes(JsonObject o) {
		JsonElement e = o.get("axes");
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		V3[] out = new V3[3];
		for (int i = 0; i < 3; i++) {
			JsonArray v = a.get(i).getAsJsonArray();
			out[i] = new V3(v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble());
		}
		return out;
	}
}
