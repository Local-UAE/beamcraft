package dev.bngmc.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft explosions reach BeamNG: each one is sent as an "explosion" message (canonical
 * centre + Minecraft power), and BeamNG pushes every car in reach with a short repulsive force
 * at that point (bridge.lua, handlers.explosion). Server thread, called from ExplosionMixin.
 */
public final class BlastBridge {
	private BlastBridge() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");

	public static void onExplosion(Level level, Vec3 center, float power) {
		CrossoverCoords.Region region = BngWorld.region();
		if (!BngWorld.isLinkedWorld() || region == null || !BngWorld.isActive(level)) {
			return;
		}
		V3 c = CrossoverCoords.minecraftToCanonicalPosition(new V3(center.x, center.y, center.z), region);
		JsonObject msg = new JsonObject();
		JsonArray pos = new JsonArray();
		pos.add(c.x());
		pos.add(c.y());
		pos.add(c.z());
		msg.add("pos", pos);
		msg.addProperty("power", power);
		boolean sent = BngLink.get().send(Protocol.EXPLOSION, msg);
		LOG.info("Explosion power {} at BeamNG ({}, {}, {}): {}", power, String.format("%.1f", c.x()), String.format("%.1f", c.y()),
			String.format("%.1f", c.z()), sent ? "sent to BeamNG" : "BeamNG not connected");
	}
}
