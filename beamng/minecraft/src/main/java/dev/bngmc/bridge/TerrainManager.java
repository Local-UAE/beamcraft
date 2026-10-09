package dev.bngmc.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import dev.bngmc.bridge.link.Wire;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps invisible {@link TerrainBlock}s under and around the players, shaped like BeamNG's
 * collision. Adapted from upstream's TerrainManager (MHW bridge, MIT): the column walk, the
 * 1/16-block ground height and the wall test are the same; the ray source is BeamNG's
 * castRayStatic, asked through one batched "raycols" message per round trip (protocol.md).
 *
 * <p>Server thread only. Work is bounded: one batch in flight, at most {@link #BATCH} columns,
 * columns are only resampled when the player's height changed by {@link #RESAMPLE_DY}.
 */
public final class TerrainManager {
	private TerrainManager() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	/** Columns within this many blocks of a player are kept sampled. */
	private static final int SAMPLE_RADIUS = 24;
	/** Rays: down from 4 above the feet to 40 below; if that misses, down from 40 above to 4 above. */
	private static final double RAY_ABOVE = 4.0;
	private static final double RAY_BELOW = 40.0;
	private static final double RAY_HIGH = 40.0;
	private static final double RESAMPLE_DY = 4.0;
	/** Surfaces steeper than this (|normal z| below it) count as walls. */
	private static final double WALL_NORMAL_Z = 0.7;
	/** Columns per request: keeps the request under the 8 KB LuaSocket receive limit. */
	private static final int BATCH = 600;
	private static final long RAY_TIMEOUT_MS = 3000;
	private static final int THICKNESS = ColumnPlan.THICKNESS;
	private static final double PROBE_HEIGHT = ColumnPlan.PROBE_HEIGHT;

	/** What was placed in a column (Y levels of terrain blocks), sampled at sampleY. */
	private record Column(double sampleY, int[] ys) {
	}

	private record Pending(long key, int x, int z, double sampleY) {
	}

	private static final Long2ObjectOpenHashMap<Column> COLUMNS = new Long2ObjectOpenHashMap<>();
	/** Sampled column keys, readable from the client thread (TerrainHold). */
	private static final java.util.Set<Long> SAMPLED = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final List<Pending> PENDING = new ArrayList<>();
	private static int nextReq = 1;
	private static int pendingReq = -1;
	private static long pendingSince;
	private static CrossoverCoords.Region columnsRegion;
	private static volatile boolean resetRequested;
	// stats for the HUD
	private static volatile long samplesTotal;
	private static volatile double lastBatchMs;
	private static volatile double samplesPerSec;
	private static long rateWindowStart;
	private static long rateWindowSamples;
	private static int timeouts;

	/** True once BeamNG's ground for this Minecraft column has arrived (any thread). */
	public static boolean isSampled(int x, int z) {
		return SAMPLED.contains(ChunkPos.asLong(x, z));
	}

	public static void reset() {
		COLUMNS.clear();
		SAMPLED.clear();
		PENDING.clear();
		pendingReq = -1;
		columnsRegion = null;
	}

	public static void requestReset() {
		resetRequested = true;
	}

	public static String describe() {
		return String.format("%d columns cached, %.0f samples/s, last batch %.2f ms in BeamNG, %d timeouts", COLUMNS.size(), samplesPerSec,
			lastBatchMs, timeouts);
	}

	public static int cachedColumns() {
		return COLUMNS.size();
	}

	public static double samplesPerSec() {
		return samplesPerSec;
	}

	public static void onServerTick(MinecraftServer server) {
		if (!BngWorld.isBridgeWorld()) {
			return;
		}
		CrossoverCoords.Region region = BngWorld.region();
		BngLink link = BngLink.get();
		if (region == null || !link.connected()) {
			return;
		}
		ServerLevel level = server.overworld();
		if (resetRequested || columnsRegion != region) {
			resetRequested = false;
			clearAll(level);
			columnsRegion = region;
		}
		collect(level, region, link);
		if (pendingReq < 0) {
			List<ServerPlayer> players = server.getPlayerList().getPlayers();
			if (!players.isEmpty()) {
				submit(region, link, players);
			}
		}
		long now = System.currentTimeMillis();
		if (now - rateWindowStart >= 1000) {
			samplesPerSec = rateWindowStart == 0 ? 0 : rateWindowSamples * 1000.0 / (now - rateWindowStart);
			rateWindowStart = now;
			rateWindowSamples = 0;
		}
	}

	private static void submit(CrossoverCoords.Region region, BngLink link, List<ServerPlayer> players) {
		PENDING.clear();
		// One batch per player at most; requests are relative to the first player's cell.
		ServerPlayer player = players.get(0);
		int px = Mth.floor(player.getX());
		int pz = Mth.floor(player.getZ());
		double py = player.getY();
		JsonArray cols = new JsonArray();
		int baseCx = px - (int) region.mcOriginX();
		int baseCy = -pz - 1 + (int) region.mcOriginZ();
		for (int r = 0; r <= SAMPLE_RADIUS && PENDING.size() < BATCH; r++) {
			for (int dx = -r; dx <= r && PENDING.size() < BATCH; dx++) {
				for (int dz = -r; dz <= r && PENDING.size() < BATCH; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r || dx * dx + dz * dz > SAMPLE_RADIUS * SAMPLE_RADIUS) {
						continue;
					}
					int x = px + dx;
					int z = pz + dz;
					long key = ChunkPos.asLong(x, z);
					Column c = COLUMNS.get(key);
					if (c != null && Math.abs(c.sampleY() - py) < RESAMPLE_DY) {
						continue;
					}
					// MC column (x, z) is canonical cell (x - ox, -z - 1 + oz): MC +Z is canonical -Y.
					cols.add(dx);
					cols.add(-dz);
					PENDING.add(new Pending(key, x, z, py));
				}
			}
		}
		if (PENDING.isEmpty()) {
			return;
		}
		double feet = py - region.mcOriginY();   // canonical altitude of the player's feet
		JsonObject p = new JsonObject();
		int req = nextReq++;
		p.addProperty("req", req);
		p.addProperty("bx", baseCx);
		p.addProperty("by", baseCy);
		p.addProperty("zTop", feet + RAY_HIGH);
		p.addProperty("zMid", feet + RAY_ABOVE);
		p.addProperty("zBot", feet - RAY_BELOW);
		p.addProperty("zProbe", feet + PROBE_HEIGHT);
		p.add("cols", cols);
		if (link.send(Protocol.RAYCOLS, p)) {
			pendingReq = req;
			pendingSince = System.currentTimeMillis();
		} else {
			PENDING.clear();
		}
	}

	private static void collect(ServerLevel level, CrossoverCoords.Region region, BngLink link) {
		if (pendingReq < 0) {
			return;
		}
		Wire.Envelope env = link.latest(Protocol.RAYHITS);
		JsonObject o = env != null ? env.body() : null;
		if (o == null || !o.has("req") || o.get("req").getAsInt() != pendingReq) {
			if (System.currentTimeMillis() - pendingSince > RAY_TIMEOUT_MS) {
				timeouts++;
				LOG.warn("Terrain batch {} got no answer within {} ms; asking again", pendingReq, RAY_TIMEOUT_MS);
				pendingReq = -1;
				PENDING.clear();
			}
			return;
		}
		pendingReq = -1;
		int n = o.get("n").getAsInt();
		int stride = o.get("stride").getAsInt();
		double miss = o.get("miss").getAsDouble();
		lastBatchMs = o.has("ms") ? o.get("ms").getAsDouble() : Double.NaN;
		if (n != PENDING.size() || !o.has("res")) {
			LOG.warn("Terrain batch answer has {} columns, expected {}", n, PENDING.size());
			PENDING.clear();
			return;
		}
		JsonArray res = o.getAsJsonArray("res");
		int nSurf = o.has("surfaces") ? o.get("surfaces").getAsInt() : stride - 4;
		BlockState terrain = BngBridgeMod.TERRAIN.defaultBlockState();
		double oy = region.mcOriginY();
		for (int i = 0; i < n; i++) {
			Pending pc = PENDING.get(i);
			int b = i * stride;
			double[] surf = new double[nSurf];
			int k = 0;
			for (int j = 0; j < nSurf; j++) {
				double z = res.get(b + j).getAsDouble();
				if (z != miss) {
					surf[k++] = z + oy;
				}
			}
			boolean probeX = res.get(b + nSurf).getAsDouble() > 0.5 && Math.abs(res.get(b + nSurf + 1).getAsDouble()) < WALL_NORMAL_Z;
			boolean probeY = res.get(b + nSurf + 2).getAsDouble() > 0.5 && Math.abs(res.get(b + nSurf + 3).getAsDouble()) < WALL_NORMAL_Z;
			apply(level, pc, java.util.Arrays.copyOf(surf, k), probeX || probeY, terrain);
		}
		samplesTotal += n;
		rateWindowSamples += n;
		PENDING.clear();
	}

	/** One column: places {@link ColumnPlan}'s voxels and removes what an earlier sample left. */
	private static void apply(ServerLevel level, Pending pc, double[] surf, boolean wall, BlockState terrain) {
		Column old = COLUMNS.get(pc.key());
		java.util.Map<Integer, Integer> plan = ColumnPlan.plan(surf, pc.sampleY(), wall);
		for (var e : plan.entrySet()) {
			setTerrain(level, pc.x(), e.getKey(), pc.z(), terrain.setValue(TerrainBlock.HEIGHT, e.getValue()));
		}
		// A first sample clears the whole range the rays could reach (blocks left by earlier
		// sessions aren't tracked); later samples remove only what the previous one placed.
		if (old == null) {
			for (int y = Mth.floor(pc.sampleY() - RAY_BELOW) - THICKNESS; y <= Mth.floor(pc.sampleY() + RAY_HIGH); y++) {
				if (!plan.containsKey(y)) {
					clearTerrain(level, pc.x(), y, pc.z());
				}
			}
		} else {
			for (int y : old.ys()) {
				if (!plan.containsKey(y)) {
					clearTerrain(level, pc.x(), y, pc.z());
				}
			}
		}
		COLUMNS.put(pc.key(), new Column(pc.sampleY(), plan.keySet().stream().mapToInt(Integer::intValue).toArray()));
		SAMPLED.add(pc.key());
	}

	private static void clearAll(ServerLevel level) {
		for (var en : COLUMNS.long2ObjectEntrySet()) {
			for (int y : en.getValue().ys()) {
				clearTerrain(level, ChunkPos.getX(en.getLongKey()), y, ChunkPos.getZ(en.getLongKey()));
			}
		}
		COLUMNS.clear();
		SAMPLED.clear();
		PENDING.clear();
		pendingReq = -1;
	}

	private static void setTerrain(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState cur = level.getBlockState(pos);
		if ((cur.isAir() || cur.is(BngBridgeMod.TERRAIN)) && cur != state) {
			level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}

	private static void clearTerrain(ServerLevel level, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		if (level.getBlockState(pos).is(BngBridgeMod.TERRAIN)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}
}
