package dev.bngmc.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import dev.bngmc.bridge.link.Vehicles;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minecraft's ground as BeamNG collision in the host world (docs/minecraft-host.md, "Holes").
 * BeamNG's smallgrid floor is solid everywhere at z = 0 and can't be lowered, so it is Minecraft's
 * bedrock (BngWorld.HOST_REGION puts the bedrock top there), and the dirt and grass layers above it
 * go to BeamNG per chunk as merged boxes (ground.lua): a flat chunk is one box, a hole splits it
 * into a few, and a car over a hole drops onto the floor. Chunks near players and near cars are
 * sent; a chunk Minecraft hasn't loaded counts as untouched ground. Once the chunks around the
 * players are in, GROUND_ON lifts the cars still standing on the floor onto the surface.
 */
public final class GroundSync {
	private GroundSync() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");

	/** Superflat: dirt, dirt, grass between the bedrock and the surface. */
	static final int LAYERS = 3;
	static final int BOTTOM_Y = BngWorld.HOST_BEDROCK_TOP_Y;   // lowest diggable layer (bedrock is below)
	private static final int RADIUS_CHUNKS = 6;       // around each player
	private static final int CAR_RADIUS_CHUNKS = 2;   // around each car
	private static final int CHUNKS_PER_FLUSH = 24;
	private static final int FLUSH_TICKS = 5;

	private static final Long2IntOpenHashMap SENT = new Long2IntOpenHashMap();   // chunk -> hash of the boxes BeamNG has
	private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
	private static final LongOpenHashSet GUESSED = new LongOpenHashSet();   // sent as untouched before Minecraft loaded them
	private static long sentForSession = -1;
	private static boolean floorSent;
	private static int ticks;

	/** Block (x, y, z) in a chunk's ground layers -> index into the solid array. */
	static int index(int x, int y, int z) {
		return x + 16 * (z + 16 * y);
	}

	/**
	 * Greedy merge of a chunk's solid ground cells into boxes {x0, y0, z0, x1, y1, z1} (inclusive,
	 * chunk-local, y counted from BOTTOM_Y): grow along x, then z, then up while every cell is solid
	 * and not yet taken.
	 */
	static List<int[]> merge(boolean[] solid) {
		boolean[] taken = new boolean[solid.length];
		List<int[]> out = new ArrayList<>();
		for (int y = 0; y < LAYERS; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					if (!solid[index(x, y, z)] || taken[index(x, y, z)]) {
						continue;
					}
					int x1 = x;
					while (x1 + 1 < 16 && free(solid, taken, x1 + 1, x1 + 1, y, y, z, z)) {
						x1++;
					}
					int z1 = z;
					while (z1 + 1 < 16 && free(solid, taken, x, x1, y, y, z1 + 1, z1 + 1)) {
						z1++;
					}
					int y1 = y;
					while (y1 + 1 < LAYERS && free(solid, taken, x, x1, y1 + 1, y1 + 1, z, z1)) {
						y1++;
					}
					for (int yy = y; yy <= y1; yy++) {
						for (int zz = z; zz <= z1; zz++) {
							for (int xx = x; xx <= x1; xx++) {
								taken[index(xx, yy, zz)] = true;
							}
						}
					}
					out.add(new int[] {x, y, z, x1, y1, z1});
				}
			}
		}
		return out;
	}

	private static boolean free(boolean[] solid, boolean[] taken, int x0, int x1, int y0, int y1, int z0, int z1) {
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					if (!solid[index(x, y, z)] || taken[index(x, y, z)]) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * A chunk-local box to canonical metres {x0, y0, z0, x1, y1, z1}, max exclusive. Block (X, Y, Z)
	 * is the canonical cell (X - ox, -Z - 1 + oz, Y - oy) (BlockSync.canonicalCell), one block being
	 * 1 / scale metres.
	 */
	static double[] canonical(int[] b, int minX, int bottomY, int minZ, CrossoverCoords.Region r) {
		int ox = (int) r.mcOriginX(), oy = (int) r.mcOriginY(), oz = (int) r.mcOriginZ();
		int bx0 = minX + b[0], bx1 = minX + b[3];
		int by0 = bottomY + b[1], by1 = bottomY + b[4];
		int bz0 = minZ + b[2], bz1 = minZ + b[5];
		int[] cells = {bx0 - ox, -bz1 - 1 + oz, by0 - oy, bx1 - ox + 1, -bz0 + oz, by1 - oy + 1};
		double[] m = new double[6];
		for (int i = 0; i < 6; i++) {
			m[i] = cells[i] / r.scale();
		}
		return m;
	}

	/** Solid ground cells of a loaded chunk; an unloaded one (null) counts as untouched superflat. */
	static boolean[] solidOf(LevelChunk chunk) {
		boolean[] s = new boolean[16 * 16 * LAYERS];
		if (chunk == null) {
			Arrays.fill(s, true);
			return s;
		}
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
		for (int y = 0; y < LAYERS; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					s[index(x, y, z)] = BlockSync.counts(chunk.getBlockState(p.set(minX + x, BOTTOM_Y + y, minZ + z)));
				}
			}
		}
		return s;
	}

	/** Server thread, from BlockSync.onChange: a block changed in the ground layers. */
	static void onChange(Level level, BlockPos pos) {
		if (pos.getY() >= BOTTOM_Y && pos.getY() < BngWorld.HOST_GROUND_TOP_Y && level.dimension() == Level.OVERWORLD && BngWorld.isHostWorld()
			&& !BngWorld.isTerrainWorld()) {
			DIRTY.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
		}
	}

	public static void onServerTick(MinecraftServer server) {
		if (++ticks % FLUSH_TICKS != 0 || !BngWorld.isHostWorld() || BngWorld.isTerrainWorld()) {
			return;   // a real-terrain world's ground is TerrainSync's
		}
		BngLink link = BngLink.get();
		CrossoverCoords.Region region = BngWorld.region();
		if (!link.responsive() || region == null || server.getPlayerList().getPlayers().isEmpty()) {   // waits out BeamNG freezes
			return;
		}
		if (link.sessionId() != sentForSession) {   // a new BeamNG session has none of it
			SENT.clear();
			GUESSED.clear();
			floorSent = false;
			sentForSession = link.sessionId();
		}
		ServerLevel level = server.overworld();
		LongLinkedOpenHashSet want = new LongLinkedOpenHashSet();
		for (var player : server.getPlayerList().getPlayers()) {
			addAround(want, player.chunkPosition().x, player.chunkPosition().z, RADIUS_CHUNKS);
		}
		LongOpenHashSet nearPlayers = new LongOpenHashSet(want);
		for (Vehicles.Info car : Vehicles.latest().values()) {
			V3 mc = CrossoverCoords.canonicalToMinecraftPosition(car.pos(), region);
			addAround(want, (int) Math.floor(mc.x()) >> 4, (int) Math.floor(mc.z()) >> 4, CAR_RADIUS_CHUNKS);
		}
		int sent = 0;
		boolean pending = false;
		for (long key : want) {
			if (GUESSED.contains(key) && level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null) {
				DIRTY.add(key);   // loaded now: send what is really there
			}
			if (SENT.containsKey(key) && !DIRTY.contains(key)) {
				continue;
			}
			if (sent >= CHUNKS_PER_FLUSH) {
				pending = true;
				break;
			}
			int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
			if (!region.containsMcX(cx << 4)) {
				continue;
			}
			LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
			if (chunk == null) {
				GUESSED.add(key);
			} else {
				GUESSED.remove(key);
			}
			List<int[]> boxes = merge(solidOf(chunk));
			JsonArray flat = new JsonArray();
			for (int[] b : boxes) {
				for (double v : canonical(b, cx << 4, BOTTOM_Y, cz << 4, region)) {
					flat.add(v);
				}
			}
			int hash = flat.hashCode();
			DIRTY.remove(key);
			if (SENT.containsKey(key) && SENT.get(key) == hash) {
				continue;
			}
			JsonObject msg = new JsonObject();
			msg.addProperty("cx", cx);
			msg.addProperty("cz", cz);
			msg.add("boxes", flat);
			if (!link.send(Protocol.GROUND_CHUNK, msg)) {
				return;
			}
			SENT.put(key, hash);
			sent++;
		}
		if (!floorSent && !pending && SENT.keySet().containsAll(nearPlayers)) {
			JsonObject msg = new JsonObject();
			double top = (BngWorld.HOST_GROUND_TOP_Y - region.mcOriginY()) / region.scale();   // the surface, canonical metres
			msg.addProperty("top", top);
			msg.addProperty("lift", top - (BOTTOM_Y - region.mcOriginY()) / region.scale());
			if (link.send(Protocol.GROUND_ON, msg)) {
				floorSent = true;
				LOG.info("Minecraft's ground is BeamNG's now: {} chunks as boxes on BeamNG's floor (the bedrock)", SENT.size());
			}
		}
	}

	private static void addAround(LongLinkedOpenHashSet out, int cx, int cz, int r) {
		for (int d = 0; d <= r; d++) {   // nearest rings first
			for (int x = cx - d; x <= cx + d; x++) {
				for (int z = cz - d; z <= cz + d; z++) {
					if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) == d) {
						out.add(ChunkPos.asLong(x, z));
					}
				}
			}
		}
	}

	/** World closing: BeamNG gets its own floor back. */
	public static void onServerStopping(MinecraftServer server) {
		if (floorSent || !SENT.isEmpty()) {
			BngLink.get().send(Protocol.GROUND_RESET, new JsonObject());
		}
		SENT.clear();
		DIRTY.clear();
		GUESSED.clear();
		floorSent = false;
		sentForSession = -1;
	}
}
