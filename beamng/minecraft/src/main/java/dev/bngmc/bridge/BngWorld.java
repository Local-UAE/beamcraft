package dev.bngmc.bridge;

import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.BngState;
import dev.bngmc.bridge.link.Protocol;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Server side: which BeamNG level is live, which Minecraft region it owns, and putting players
 * next to the BeamNG vehicle. Two kinds of world are linked to BeamNG, chosen by level name:
 * <ul>
 *   <li>{@link #BRIDGE_LEVEL_NAME}: BeamNG hosts. A void world; each BeamNG level owns a region,
 *   persisted per world (bngbridge-regions.properties) so blocks placed on a map stay with it.</li>
 *   <li>{@link #HOST_LEVEL_NAME}: Minecraft hosts (docs/minecraft-host.md). A superflat world
 *   pinned to BeamNG by one fixed anchor, {@link #HOST_REGION}; BeamNG should run smallgrid,
 *   whose flat floor is the superflat ground.</li>
 *   <li>{@link #TERRAIN_LEVEL_NAME}, or any world whose name ends in {@link #TERRAIN_NAME_TAG} (a
 *   downloaded map: scripts\run-minecraft.ps1 -World): Minecraft hosts on real terrain. Everything
 *   of the host world, but the ground goes to BeamNG as a terrain (TerrainSync), not as boxes.</li>
 * </ul>
 * Any other world is left alone.
 */
public final class BngWorld {
	private BngWorld() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	public static final String BRIDGE_LEVEL_NAME = "BeamNG Bridge";
	public static final String HOST_LEVEL_NAME = "BeamNG Host";
	public static final String TERRAIN_LEVEL_NAME = "BeamNG Terrain";
	/** A world named "... [BeamNG]" is a real-terrain world too. */
	public static final String TERRAIN_NAME_TAG = "[BeamNG]";
	/** Top of the superflat grass (bedrock, 2 dirt, grass from y = -64): BeamNG's z = 0. */
	public static final int HOST_GROUND_TOP_Y = -60;
	/** Minecraft (0, -60, 0) is BeamNG (0, 0, 0), whatever BeamNG level is loaded. */
	/** Top of the superflat bedrock: BeamNG's floor (z = 0), which can't be lowered (ground.lua). */
	public static final int HOST_BEDROCK_TOP_Y = HOST_GROUND_TOP_Y - 3;
	/**
	 * Minecraft blocks per BeamNG metre in the host world: cars at 1:1 looked small next to
	 * Minecraft's characters (Jas), so the world is mapped 1.4 times bigger; a car's roof comes to
	 * about Steve's head. Everything scales with it (collision, ground, camera), so what is drawn is
	 * what collides. -Dbngbridge.hostScale overrides it.
	 */
	public static final double HOST_SCALE = Double.parseDouble(System.getProperty("bngbridge.hostScale", "1.4"));
	public static final CrossoverCoords.Region HOST_REGION = new CrossoverCoords.Region(0, 0.0, HOST_BEDROCK_TOP_Y, 0.0, HOST_SCALE);
	/** The BeamNG level whose floor matches the superflat ground. */
	public static final String HOST_BEAMNG_LEVEL = "smallgrid";
	private static final String REGION_FILE = "bngbridge-regions.properties";

	private static final Map<String, Integer> REGIONS = new HashMap<>();
	private static volatile CrossoverCoords.Region region;
	private static volatile String level;
	private static volatile boolean bridgeWorld;
	private static volatile boolean hostWorld;
	private static volatile boolean terrainWorld;
	private static volatile boolean recallRequested;
	private static volatile int recallServed;

	public static boolean isBridgeWorld() {
		return bridgeWorld;
	}

	/** Minecraft hosts: the superflat host world or the real-terrain one. */
	public static boolean isHostWorld() {
		return hostWorld;
	}

	/** The real-terrain host world (normal generation): its ground is a BeamNG terrain. */
	public static boolean isTerrainWorld() {
		return terrainWorld;
	}

	/** Either kind of world BeamNG is linked to. */
	public static boolean isLinkedWorld() {
		return bridgeWorld || hostWorld;
	}

	/**
	 * The dimension BeamNG mirrors: the End while the player is there, else the overworld. The Nether
	 * stays out: the heightfield is read from the top down and its top is the bedrock roof.
	 */
	public static ServerLevel activeLevel(MinecraftServer server) {
		var players = server.getPlayerList().getPlayers();
		if (!players.isEmpty() && players.get(0).serverLevel().dimension() == Level.END) {
			return players.get(0).serverLevel();
		}
		return server.overworld();
	}

	/** The level BeamNG mirrors is this one (see {@link #activeLevel}). */
	public static boolean isActive(Level level) {
		return level instanceof ServerLevel sl && sl == activeLevel(sl.getServer());
	}

	/** Region of the live BeamNG level, or null while BeamNG isn't connected to a level. */
	public static CrossoverCoords.Region region() {
		return region;
	}

	public static String level() {
		return level;
	}

	/** Puts every player next to the BeamNG vehicle on the next tick that has fresh state. */
	public static void requestRecall() {
		recallRequested = true;
	}

	public static int recallServed() {
		return recallServed;
	}

	public static void reset() {
		bridgeWorld = false;
		hostWorld = false;
		terrainWorld = false;
		region = null;
		level = null;
		REGIONS.clear();
	}

	public static void onServerStarted(MinecraftServer server) {
		reset();
		String name = server.getWorldData().getLevelName();
		bridgeWorld = BRIDGE_LEVEL_NAME.equals(name);
		terrainWorld = TERRAIN_LEVEL_NAME.equals(name) || (name != null && name.endsWith(TERRAIN_NAME_TAG));
		hostWorld = HOST_LEVEL_NAME.equals(name) || terrainWorld;
		if (hostWorld) {
			region = HOST_REGION;
			server.overworld().setDayTime(6000);
			// superflat: the player goes to the car; real terrain: the car comes to the player once
			// BeamNG has the terrain (TerrainSync), the player's spawn being the better place
			recallRequested = !terrainWorld;
			if (terrainWorld) {
				recallServed++;   // nothing to wait for: CameraSync starts sending Minecraft's camera at once
			}
			LOG.info("BeamNG host world opened: Minecraft {} = BeamNG origin", new BlockPos(0, HOST_GROUND_TOP_Y, 0));
			return;
		}
		if (!bridgeWorld) {
			return;
		}
		ServerLevel overworld = server.overworld();
		overworld.setDayTime(6000);
		overworld.setDefaultSpawnPos(new BlockPos(0, 64, 0), 0.0F);
		loadRegions(server);
		recallRequested = true;
		LOG.info("BeamNG bridge world opened (regions {})", REGIONS);
	}

	public static void onServerTick(MinecraftServer server) {
		if (!isLinkedWorld()) {
			return;
		}
		BngLink link = BngLink.get();
		var env = link.latest(Protocol.STATE);
		BngState st = env != null ? BngState.parse(env, BngLink.nowMs()) : null;
		if (st == null || st.level() == null || st.level().isEmpty()) {
			return;
		}
		if (!st.level().equals(level)) {
			if (hostWorld) {
				level = st.level();
				if (!HOST_BEAMNG_LEVEL.equals(level)) {
					LOG.warn("BeamNG is on {}, not {}: its ground won't match the superflat floor", level, HOST_BEAMNG_LEVEL);
				}
			} else {
				switchLevel(server, st.level());
			}
		}
		if (recallRequested && st.vehicle() != null && !server.getPlayerList().getPlayers().isEmpty()) {
			recallRequested = false;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				teleportBesideVehicle(server.overworld(), p, st);
			}
			recallServed++;
		}
	}

	private static void switchLevel(MinecraftServer server, String newLevel) {
		Integer idx = REGIONS.get(newLevel);
		if (idx == null) {
			idx = REGIONS.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
			REGIONS.put(newLevel, idx);
			saveRegions(server);
			LOG.info("New BeamNG level {}: Minecraft region {} (x = {})", newLevel, idx, idx * CrossoverCoords.REGION_SPACING);
		} else {
			LOG.info("BeamNG level {}: Minecraft region {}", newLevel, idx);
		}
		level = newLevel;
		region = CrossoverCoords.Region.of(idx);
		// Players outside this level's region are moved to the vehicle.
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!region.containsMcX(p.getX())) {
				recallRequested = true;
			}
		}
	}

	/** Beside the vehicle (its right-hand side, 2.5 m out), facing it, with a little floor to stand on. */
	private static void teleportBesideVehicle(ServerLevel level, ServerPlayer player, BngState st) {
		CrossoverCoords.Region r = region;
		if (r == null) {
			return;
		}
		BngState.Vehicle v = st.vehicle();
		V3 fwd = v.fwd() != null ? v.fwd() : new V3(0, 1, 0);
		V3 right = fwd.cross(new V3(0, 0, 1)).normalize();
		if (right.length() < 0.5) {
			right = new V3(1, 0, 0);
		}
		V3 spot = v.pos().add(right.scale(2.5));
		V3 mc = CrossoverCoords.canonicalToMinecraftPosition(spot, r);
		V3 toCar = CrossoverCoords.canonicalToMinecraftDirection(v.pos().sub(spot));
		float yaw = (float) Math.toDegrees(Math.atan2(-toCar.x(), toCar.z()));
		double y = mc.y();
		if (isHostWorld()) {
			// Minecraft's own ground: the car may still stand on BeamNG's floor, 3 m down, until
			// GroundSync lifts it onto the surface, and a hole here must stay a hole. The chunk is
			// loaded first: for a chunk that isn't, the heightmap says the bottom of the world and Jas
			// fell into the void.
			int bx = Mth.floor(mc.x()), bz = Mth.floor(mc.z());
			int top = level.getChunk(bx >> 4, bz >> 4).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15) + 1;
			y = top >= HOST_BEDROCK_TOP_Y ? top : HOST_GROUND_TOP_Y;   // never below the bedrock top
		} else {
			BlockState terrain = BngBridgeMod.TERRAIN.defaultBlockState();
			int fy = Mth.floor(mc.y() - 1e-3) - 1;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos pos = new BlockPos(Mth.floor(mc.x()) + dx, fy, Mth.floor(mc.z()) + dz);
					if (level.getBlockState(pos).isAir()) {
						level.setBlock(pos, terrain, Block.UPDATE_CLIENTS);
					}
				}
			}
		}
		player.teleportTo(level, mc.x(), y + 0.01, mc.z(), yaw, 0.0F);
		LOG.info("Moved {} beside BeamNG vehicle {} ({}) at MC {}", player.getName().getString(), v.id(), v.model(),
			new Vec3(mc.x(), mc.y(), mc.z()));
	}

	private static Path regionPath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(REGION_FILE);
	}

	private static void loadRegions(MinecraftServer server) {
		Path p = regionPath(server);
		if (!Files.exists(p)) {
			return;
		}
		Properties props = new Properties();
		try (Reader r = Files.newBufferedReader(p)) {
			props.load(r);
			for (String k : props.stringPropertyNames()) {
				REGIONS.put(k, Integer.parseInt(props.getProperty(k).trim()));
			}
		} catch (IOException | RuntimeException e) {
			LOG.warn("Ignoring unreadable {}: {}", p, e.toString());
		}
	}

	private static void saveRegions(MinecraftServer server) {
		Properties props = new Properties();
		REGIONS.forEach((k, v) -> props.setProperty(k, Integer.toString(v)));
		try (Writer w = Files.newBufferedWriter(regionPath(server))) {
			props.store(w, "BeamNG level -> Minecraft region index (region origin x = index * " + (long) CrossoverCoords.REGION_SPACING + ")");
		} catch (IOException e) {
			LOG.warn("Could not save regions: {}", e.toString());
		}
	}
}
