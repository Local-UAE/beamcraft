package dev.bngmc.bridge.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import net.minecraft.world.phys.Vec3;

/**
 * What Minecraft items do to a BeamNG car, sent to BeamNG as its own damage calls (carfx.lua):
 * fire, water, dents where the blow lands, tires popped by a hit next to a wheel, and the car
 * remover. Positions and directions go from Minecraft to canonical here. Server thread.
 */
public final class CarEffects {
	private CarEffects() {
	}

	/** Deletes the car in BeamNG (the car remover stick). */
	public static void remove(long id) {
		JsonObject msg = new JsonObject();
		msg.addProperty("id", id);
		BngLink.get().send(Protocol.VEHICLE_REMOVE, msg);
	}

	public static void ignite(long id) {
		simple(id, "ignite");
	}

	public static void extinguish(long id) {
		simple(id, "extinguish");
	}

	private static void simple(long id, String kind) {
		JsonObject msg = new JsonObject();
		msg.addProperty("id", id);
		msg.addProperty("kind", kind);
		BngLink.get().send(Protocol.VEHICLE_FX, msg);
	}

	/**
	 * A blow at a point on the car (Minecraft coordinates), travelling along dir: a dent sized by the
	 * Minecraft damage, and with tire set, the tire there pops if the point is next to a wheel.
	 */
	public static void dent(long id, Vec3 at, Vec3 dir, float damage, boolean tire) {
		CrossoverCoords.Region region = BngWorld.region();
		if (region == null || dir.lengthSqr() < 1e-9) {
			return;
		}
		V3 p = CrossoverCoords.minecraftToCanonicalPosition(new V3(at.x, at.y, at.z), region);
		V3 d = CrossoverCoords.minecraftToCanonicalDirection(new V3(dir.x, dir.y, dir.z));
		JsonObject msg = new JsonObject();
		msg.addProperty("id", id);
		msg.addProperty("kind", "dent");
		msg.add("pos", vec(p));
		msg.add("dir", vec(d));
		msg.addProperty("damage", damage);
		msg.addProperty("tire", tire);
		BngLink.get().send(Protocol.VEHICLE_FX, msg);
	}

	private static JsonArray vec(V3 v) {
		JsonArray a = new JsonArray();
		a.add(v.x());
		a.add(v.y());
		a.add(v.z());
		return a;
	}
}
