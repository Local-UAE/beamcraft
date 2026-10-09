package dev.bngmc.bridge.entity;

import dev.bngmc.bridge.BngBridgeMod;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.Vehicles;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Moving BeamNG cars hit Minecraft creatures: any living entity (Steve, mobs) touching a car's
 * box while the car is faster than {@link #MIN_SPEED} takes damage and is thrown along the car's
 * velocity. Server thread. BeamNG doesn't feel the hit (Steve has no body in BeamNG's physics yet).
 */
public final class VehicleHits {
	private VehicleHits() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	/** m/s; slower contact is a push, not a hit. */
	static final double MIN_SPEED = 3.0;
	/** Minecraft health per m/s above MIN_SPEED (20 = a full player): 15 m/s ~ 12, 25 m/s kills. */
	static final double DAMAGE_PER_MS = 1.0;
	/** Ticks before the same car can hit the same creature again. */
	private static final int COOLDOWN_TICKS = 10;
	public static final ResourceKey<DamageType> VEHICLE_DAMAGE =
		ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(BngBridgeMod.MOD_ID, "vehicle"));

	private static final Int2LongOpenHashMap LAST_HIT = new Int2LongOpenHashMap();

	public static void reset() {
		LAST_HIT.clear();
	}

	/** Feet this far below the roof still count as riding on it (the roof moves with body flex). */
	static final double RIDER_TOLERANCE = 0.3;

	/**
	 * True for a creature standing on the car's roof: it rides along (CarRide) and isn't hit.
	 * Without this, a car speeding up past MIN_SPEED threw its own rider off (measured).
	 */
	static boolean ridingOnRoof(double feetY, double roofY) {
		return feetY >= roofY - RIDER_TOLERANCE;
	}

	/**
	 * Creatures the car doesn't hit: whoever sits in it (Minecraft-hosted worlds seat the driver
	 * inside the proxy; measured: the pickup "hit" its own driver at 9-13 m/s) and riders on its roof.
	 */
	static boolean spared(boolean seatedInThisCar, double feetY, double roofY) {
		return seatedInThisCar || ridingOnRoof(feetY, roofY);
	}

	/** Damage (Minecraft health) for a hit at this relative speed (m/s); 0 below the threshold. */
	static float damageFor(double speed) {
		return speed <= MIN_SPEED ? 0f : (float) ((speed - MIN_SPEED) * DAMAGE_PER_MS);
	}

	static void tick(ServerLevel level, BngVehicleEntity proxy, Vehicles.Info v) {
		V3 vel = v.vel();
		if (!dev.bngmc.bridge.BridgeSettings.get().carsHurt() || vel == null || vel.length() <= MIN_SPEED
			|| vel.length() > Vehicles.MAX_PLAUSIBLE_SPEED) {
			return;
		}
		AABB box = proxy.getBoundingBox().inflate(0.2);
		double roof = proxy.getBoundingBox().maxY;
		List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, box,
			e -> e.isAlive() && !e.isSpectator() && !spared(e.getVehicle() == proxy, e.getBoundingBox().minY, roof));
		if (victims.isEmpty()) {
			return;
		}
		V3 mcVel = dev.bngmc.bridge.BngWorld.region() != null ? CrossoverCoords.canonicalToMinecraftVelocity(vel, dev.bngmc.bridge.BngWorld.region())
			: CrossoverCoords.canonicalToMinecraftVelocity(vel);   // blocks per second, Minecraft axes
		long now = level.getGameTime();
		for (LivingEntity e : victims) {
			int key = e.getId() * 31 + (int) proxy.bngId();
			if (now - LAST_HIT.getOrDefault(key, Long.MIN_VALUE / 2) < COOLDOWN_TICKS) {
				continue;
			}
			LAST_HIT.put(key, now);
			Vec3 ev = e.getDeltaMovement().scale(20);   // m/s
			double rel = new Vec3(mcVel.x() - ev.x, mcVel.y() - ev.y, mcVel.z() - ev.z).length();
			float dmg = damageFor(rel);
			if (dmg <= 0) {
				continue;
			}
			DamageSource src = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(VEHICLE_DAMAGE),
				proxy);
			e.hurt(src, dmg);
			// Thrown along the car's direction of travel, a bit upward: blocks per tick.
			double s = Math.min(rel, 30.0) / 20.0 * 0.9;
			V3 dir = mcVel.normalize();
			e.setDeltaMovement(e.getDeltaMovement().add(dir.x() * s, 0.25 + s * 0.3, dir.z() * s));
			e.hurtMarked = true;   // send the new motion to the client (players move client-side)
			LOG.info("BeamNG {} ({}) hit {} at {} m/s: {} damage", v.id(), v.model(), e.getName().getString(), String.format("%.1f", rel),
				String.format("%.1f", dmg));
		}
	}
}
