package dev.bngmc.bridge.client;

import dev.bngmc.bridge.entity.BngVehicleEntity;
import dev.bngmc.bridge.entity.CrashDamage;
import dev.bngmc.bridge.link.Vehicles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Steve and moving cars (client tick: the local player's movement is client-side in Minecraft).
 * <ul>
 *   <li>Carried: standing on a car's roof, Steve moves with it.</li>
 *   <li>Riding: while the player drives a car in BeamNG (got in with a right-click), Steve rides
 *   on it instead of Minecraft pausing, and a hard crash hurts him: speed lost faster than
 *   {@link CrashDamage#SAFE_DELTA_V} m/s between two vehicle updates (20 Hz) costs health.</li>
 * </ul>
 */
public final class CarRide {
	private CarRide() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static long ridingId = -1;
	private static double lastSpeed = -1;
	private static double lastRx;

	public static void startRiding(long bngId) {
		ridingId = bngId;
		lastSpeed = -1;
	}

	public static void stopRiding() {
		ridingId = -1;
	}

	public static boolean riding() {
		return ridingId >= 0;
	}

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			return;
		}
		if (ridingId >= 0) {
			ride(mc, p);
		} else {
			carry(mc, p);
		}
	}

	/**
	 * Feet up to this far below a car's roof still count as standing on it. The proxy box is
	 * re-fitted to BeamNG's oriented box every tick, so its top moves with suspension and body
	 * flex as well as with the car; once it rises past Steve's feet, Minecraft treats him as
	 * already inside the box and lets him fall through (measured: roof 101.749 m, a moment later
	 * Steve at 100.0 m inside the box). Snapping the feet to the top fixes both.
	 */
	static final double SNAP_DEPTH = 0.3;

	/** On a roof: ride on its top and move with the car. */
	private static void carry(Minecraft mc, LocalPlayer p) {
		Vec3 v = p.getDeltaMovement();
		if (v.y > 0 || p.getAbilities().flying || p.isFallFlying()) {
			return;   // jumping off, flying or gliding
		}
		AABB feet = p.getBoundingBox();
		AABB probe = new AABB(feet.minX, feet.minY - 0.08, feet.minZ, feet.maxX, feet.minY + SNAP_DEPTH, feet.maxZ);
		for (BngVehicleEntity car : mc.level.getEntitiesOfClass(BngVehicleEntity.class, probe)) {
			AABB box = car.getBoundingBox();
			boolean over = p.getX() > box.minX && p.getX() < box.maxX && p.getZ() > box.minZ && p.getZ() < box.maxZ;
			double sink = box.maxY - feet.minY;   // > 0: the roof has risen around the feet
			if (over && sink > -0.08 && sink < SNAP_DEPTH) {   // not when brushing past a low wreck
				p.setPos(p.getX() + (car.getX() - car.xo), box.maxY, p.getZ() + (car.getZ() - car.zo));
				p.setDeltaMovement(v.x, 0, v.z);
				p.setOnGround(true);
				p.fallDistance = 0;
				return;
			}
		}
	}

	/** Driving in BeamNG: Steve sits on the car and feels its crashes. */
	private static void ride(Minecraft mc, LocalPlayer p) {
		BngVehicleEntity car = null;
		for (BngVehicleEntity e : mc.level.getEntitiesOfClass(BngVehicleEntity.class, p.getBoundingBox().inflate(256))) {
			if (e.bngId() == ridingId) {
				car = e;
				break;
			}
		}
		if (car == null) {
			return;
		}
		AABB box = car.getBoundingBox();
		p.setPos(box.getCenter().x, box.maxY, box.getCenter().z);
		p.setDeltaMovement(Vec3.ZERO);
		p.fallDistance = 0;
		Vehicles.Info v = Vehicles.latest().get(ridingId);
		if (v == null || v.vel() == null || v.rxMs() == lastRx) {
			return;
		}
		lastRx = v.rxMs();
		double speed = v.vel().length();
		if (lastSpeed >= 0 && speed < Vehicles.MAX_PLAUSIBLE_SPEED && lastSpeed < Vehicles.MAX_PLAUSIBLE_SPEED) {
			float dmg = CrashDamage.damageFor(lastSpeed - speed);
			var server = mc.getSingleplayerServer();
			if (dmg > 0 && server != null && dev.bngmc.bridge.BridgeSettings.get().crashHurts()) {
				var uuid = p.getUUID();
				server.execute(() -> {
					ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
					if (sp != null) {
						CrashDamage.apply(sp, dmg);
					}
				});
				LOG.info("Crash in BeamNG {}: lost {} m/s, {} damage to {}", ridingId, String.format("%.1f", lastSpeed - speed),
					String.format("%.1f", dmg), p.getName().getString());
			}
		}
		lastSpeed = speed;
	}
}
