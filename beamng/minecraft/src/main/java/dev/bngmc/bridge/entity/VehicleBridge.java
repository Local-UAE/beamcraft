package dev.bngmc.bridge.entity;

import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Vehicles;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Keeps exactly one proxy entity per BeamNG vehicle (BeamNG id -> entity): created when a vehicle
 * appears, discarded when it leaves the data or BeamNG goes away, never respawned per frame.
 * Server thread only.
 */
public final class VehicleBridge {
	private VehicleBridge() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	/** Vehicle data older than this is a stall: proxies (and whoever sits in them) stay put. */
	private static final double STALE_MS = 1500;
	/**
	 * Only data this old counts as BeamNG gone. BeamNG stalls for seconds while it swaps a car and
	 * for 20-30 s on a model's first export; removing the proxies then threw Jas out of his seat.
	 */
	private static final double GONE_MS = 90_000;
	private static final Long2ObjectOpenHashMap<BngVehicleEntity> PROXIES = new Long2ObjectOpenHashMap<>();

	public static void reset() {
		PROXIES.clear();
		VehicleHits.reset();
	}

	public static int count() {
		return PROXIES.size();
	}

	public static void onServerTick(MinecraftServer server) {
		if (!BngWorld.isLinkedWorld()) {
			return;
		}
		ServerLevel level = dev.bngmc.bridge.BngWorld.activeLevel(server);
		Map<Long, Vehicles.Info> data = Vehicles.latest();
		boolean stalled = !BngLink.get().connected() || Vehicles.ageMs() > STALE_MS;
		if (BngWorld.region() == null || (stalled && Vehicles.ageMs() > GONE_MS)) {
			if (!PROXIES.isEmpty()) {
				LOG.info("BeamNG vehicle data gone: removing {} vehicle proxies", PROXIES.size());
				PROXIES.values().forEach(BngVehicleEntity::discard);
				PROXIES.clear();
			}
			return;
		}
		if (stalled) {
			return;   // BeamNG busy (a swap, a first export): keep every proxy where it was
		}
		for (Vehicles.Info v : data.values()) {
			BngVehicleEntity proxy = PROXIES.get(v.id());
			if (proxy != null && !proxy.isRemoved() && proxy.level() != level) {
				proxy.discard();   // the player went to the End or back: the car's stand-in goes along
				proxy = null;
			}
			if (proxy == null || proxy.isRemoved()) {
				proxy = BngEntities.VEHICLE.create(level);
				if (proxy == null) {
					continue;
				}
				proxy.setBngId(v.id());
				proxy.follow();
				level.addFreshEntity(proxy);
				PROXIES.put(v.id(), proxy);
				LOG.info("BeamNG vehicle {} ({}) now has a Minecraft proxy at {}", v.id(), v.model(), proxy.position());
			}
		}
		for (BngVehicleEntity proxy : PROXIES.values()) {
			Vehicles.Info v = data.get(proxy.bngId());
			if (v != null && !proxy.isRemoved()) {
				// A proxy follows its car in its own tick, and an entity whose chunk isn't ticking
				// doesn't tick: when BeamNG moved the car far (the terrain world brings it to the
				// player), the proxy stayed behind and the car was drawn with no hitbox (seen: the
				// pickup left 1100 blocks back, the remover stick went through it). Carry it over.
				if (!level.isPositionEntityTicking(proxy.blockPosition())) {
					proxy.follow();
				}
				VehicleHits.tick(level, proxy, v);
			}
		}
		PROXIES.long2ObjectEntrySet().removeIf(en -> {
			if (!data.containsKey(en.getLongKey())) {
				LOG.info("BeamNG vehicle {} left: proxy removed", en.getLongKey());
				en.getValue().discard();
				return true;
			}
			return false;
		});
	}
}
