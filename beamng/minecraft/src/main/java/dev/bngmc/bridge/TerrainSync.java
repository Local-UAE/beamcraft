package dev.bngmc.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A real-terrain Minecraft world's ground as a BeamNG terrain (docs/minecraft-host.md, "Terrain";
 * BeamNG side terrain.lua). Around the player, AREA x AREA columns: each column's surface (the top
 * of its highest solid block that isn't a log, leaves or fluid), smoothed into slopes a car can
 * drive or kept as real block steps (BridgeSettings.ground, TerrainHeights), written as a 16-bit
 * heightmap PNG into BeamNG's user folder (from the welcome message) and loaded there as a
 * TerrainBlock, one sample per block for slopes and 2 x 2 per block for steps. Chunks Minecraft hadn't loaded
 * yet count as the median height until they load; they and every block change are sent again as
 * patches. Driving near the edge builds the terrain anew around the player. The load is one
 * datagram: until BeamNG answers TERRAIN_LOADED it is sent again every RESEND_MS (the first one
 * was lost while BeamNG stood still for a 23 s car export, measured).
 */
public final class TerrainSync {
	private TerrainSync() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");

	static final int AREA = 1024;                     // blocks per side
	private static final int CHUNKS = AREA / 16;
	private static final int RECENTER_MARGIN = 192;   // blocks from the edge before building anew
	private static final int READY_RADIUS = 2;        // chunks around the player loaded before the first build
	private static final int REFRESH_RADIUS = 10;     // chunks around players checked for newly loaded ones
	private static final int PATCHES_PER_FLUSH = 6;
	private static final int FLUSH_TICKS = 5;
	private static final int SCAN_DEPTH = 256;        // blocks down from the heightmap looking for ground: a tower over a road
	private static final int HEADROOM = 48;           // blocks above the highest ground the terrain can rise to
	// and at least this many above the lowest: chunks read after the build (a city's towers, 2026-10-03)
	// can stand far above the ground read first, and anything over the range is cut at its top
	private static final int MIN_SPAN = 320;
	private static final int FOOTROOM = 32;           // and below the lowest it can be dug
	static final String FILE = "terrain_h.png";

	private static float[] raw;     // surface heights (y of the top face, blocks), row = z, column = x
	private static float[] done;    // driven surface (TerrainHeights.smooth)
	private static boolean[] known; // per chunk: really read (not the median stand-in)
	/**
	 * Per column: the deck over its surface (TerrainHeights.deck, NaN for none), and whether BeamNG
	 * gets the deck there. One heightfield can't be the elevated highway and the road under it, so
	 * around each car the level at the car's height goes in: a car on the deck drives on the deck, one
	 * under it on the ground (Jas fell through Newisle's highway to the road below, 2026-10-05).
	 */
	private static float[] decks;     // MAX_DECKS per column, top down, NaN past the last
	private static float[] grounds;   // per column: the surface itself (what onDeck -1 gives)
	private static boolean[] naturals; // per column: BeamNG gets natural ground there (a hillside's steps are blurred more)
	private static byte[] onDeck;     // per column: the deck BeamNG gets (index into decks), -1 the surface
	private static final float[] LAST_DECKS = new float[TerrainHeights.MAX_DECKS];   // surface()'s, for the column it just read
	private static int lastDecks;
	private static boolean lastNatural;   // surface()'s: the column's surface block is natural ground
	/**
	 * Blocks around a car whose levels are chosen, plus LEAD_S of its travel... BeamNG's physics takes
	 * up a terrain patch 0.6-0.7 s after it is applied (measured 2026-10-05: the ground lowered under a
	 * parked car, it fell 0.7 s later), and a level chosen a second ahead at highway speed came too
	 * late: the autopilot fell through a deck whose heights were already in BeamNG's data.
	 */
	static final int DECK_RADIUS = 32;
	static final double LEAD_S = 2.0;
	/** ...up to this many. */
	static final int MAX_TRACE = 128;
	private static int x0, z0;      // block of sample row 0, column 0
	private static double baseZ;    // canonical z of the terrain's corner, metres
	private static double range;    // metres of height the terrain covers
	private static long builtFor = -1;
	/** The dimension the terrain in BeamNG was read from: going to the End (or back) builds it anew. */
	private static net.minecraft.resources.ResourceKey<Level> builtDim;
	/** The End's empty columns: deep enough for a long fall, above smallgrid's floor at y -63. */
	static final float END_VOID_Y = -60;
	private static volatile boolean endVoid;   // the area being read is the End's (server thread)
	private static final long RESEND_MS = 10_000;
	private static volatile boolean loaded;   // BeamNG answered the last load
	// each build's id, so an answer to an older one is told apart; a random start, as BeamNG keeps
	// the last terrain (and its id) when Minecraft closes, and answers a load with that id "same"
	private static int loadId = new java.util.Random().nextInt(1 << 24);
	private static volatile int awaitedId;
	private static long sentMs;
	private static JsonObject lastLoad;
	private static boolean carPlaced;   // this session's car brought to the player
	private static final int CAR_NEAR = 48;        // blocks: a car this close stays where it is
	private static final int SPOT_RADIUS = 40;     // blocks around the player searched for open ground
	private static final int SPOT_HALF = 4;        // the spot is 9 x 9 blocks
	private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
	// Patches BeamNG hasn't acknowledged yet: pid -> {chunk key, sent ms}. A patch is one datagram, and
	// one lost while BeamNG was busy (five cars falling at once, 2026-10-03) left a chunk of the city at
	// the stand-in height, a 0.7 m step across a street, for good. Unacknowledged after ACK_MS, the
	// chunk is read and sent again. Only once BeamNG has acknowledged anything (an older BeamNG
	// mod doesn't).
	private static final java.util.Map<Integer, long[]> UNACKED = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long ACK_MS = 3000;
	private static int nextPid;
	private static volatile boolean acks;
	private static volatile int resent;

	/** What a terrain is built from besides the blocks: when the settings change it, the terrain is built again. */
	record Look(int perBlock, int radius, boolean sea, boolean open) {
		static Look of(BridgeSettings s) {
			return new Look(s.ground().samplesPerBlock, s.slopeRadius(), s.seaWater(), s.openBuildings());
		}

		String describe() {
			return (radius == 0 ? "real blocks" : "smooth slopes over " + (radius * 2 + 1) + " blocks") + (sea ? "" : ", no water")
				+ (open ? "" : ", buildings solid");
		}
	}

	private static Look built;   // the look of the terrain in BeamNG (or on its way there)

	/** BeamNG's answers to a load (the link thread). */
	public static void install(BngLink link) {
		link.onMessage(Protocol.TERRAIN_ACK, env -> {
			if (env.body().has("pid")) {
				acks = true;
				UNACKED.remove(env.body().get("pid").getAsInt());
			}
		});
		link.onMessage(Protocol.TERRAIN_LOADED, env -> {
			JsonObject body = env.body();
			if (body.has("id") && body.get("id").getAsInt() != awaitedId) {
				return;   // a late answer to an older build: the new one isn't in BeamNG yet
			}
			loaded = true;
			LOG.info("BeamNG built the terrain: {}", env.body());
		});
		link.onMessage(Protocol.ERROR, env -> {
			if (env.body().has("code") && "terrain_failed".equals(env.body().get("code").getAsString())) {
				LOG.warn("BeamNG couldn't build the terrain: {}", env.body());
			}
		});
	}
	private static int ticks;
	private static volatile int patchesSent, editsSent, editsSeen;

	/** For the status HUD. */
	public static String describe() {
		if (!BngWorld.isTerrainWorld()) {
			return "not a terrain world";
		}
		boolean[] chunks = known;   // the HUD's thread: the server thread may drop it meanwhile
		if (raw == null || chunks == null) {
			return "not built";
		}
		int k = 0;
		for (boolean b : chunks) {
			k += b ? 1 : 0;
		}
		Look look = built;
		return String.format("%s, %s, %d of %d chunks read, %d patches sent (%d of %d edits), %d edited chunks waiting, %d resent, %d unacknowledged, %s",
			loaded ? "in BeamNG" : "waiting for BeamNG", look != null ? look.describe() : "?", k, chunks.length, patchesSent, editsSent, editsSeen,
			DIRTY.size(), resent, UNACKED.size(), TerrainPosts.describe());
	}

	/** Server thread, from BlockSync.onChange: ground (as {@link #ground}) appeared or went. */
	static void onChange(Level level, BlockPos pos) {
		if (BngWorld.isTerrainWorld() && level.dimension() == builtDim && raw != null) {
			if (DIRTY.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4))) {
				editsSeen++;
			}
		}
	}

	/** Ground for a car: something a car collides with, not a tree, not water, not a thin post (TerrainPosts: a box of its own). */
	static boolean ground(BlockState s) {
		return s != null && !s.isAir() && collides(s) && !TerrainPosts.thin(s) && !s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES) && s.getFluidState().isEmpty()
			&& !s.is(BngBridgeMod.TERRAIN) && !s.is(Blocks.CHORUS_PLANT) && !s.is(Blocks.CHORUS_FLOWER);
	}

	/**
	 * Blocks motion and has something to collide with. Signs, banners and pressure plates count as
	 * solid in Minecraft (forceSolidOn) with nothing to bump into: a street sign over Newisle's road
	 * stood in BeamNG as a three-block pillar in the lane (2026-10-05).
	 */
	static boolean collides(BlockState s) {
		return s.blocksMotion() && !s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	/** Loose natural ground (TerrainHeights.SOIL): what a street is laid on and a subway buried in, not walls. */
	static boolean soil(BlockState s) {
		return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND);
	}

	/** Natural ground: what world generation lays down. Any other solid block was built (planks, concrete, glass, cobblestone...). */
	static boolean natural(BlockState s) {
		// colored terracotta is left out: builders use it far more than badlands bands get under a roof;
		// clay too, a few blocks under rivers in nature and Newisle's white walls, towers over roads
		return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.TERRACOTTA)
			|| s.is(BlockTags.SNOW) || s.is(BlockTags.ICE) || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES)
			|| s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
			|| s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES) || s.is(Blocks.GRAVEL) || s.is(Blocks.BEDROCK)
			|| s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND)
			|| s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.OBSIDIAN) || s.is(Blocks.MAGMA_BLOCK)
			|| s.is(Blocks.END_STONE);
	}

	private static int seaColumns;   // columns of the area whose top is water at sea level (server thread)
	private static final byte[] KINDS = new byte[SCAN_DEPTH + 1];       // server thread only
	private static final TerrainPosts.Column POSTS = new TerrainPosts.Column();   // surface()'s posts, for readChunk
	private static final float[] TOPS = new float[SCAN_DEPTH + 1];
	private static volatile boolean openUnder = true;                   // the look being read (Look.open)

	/**
	 * The surface of one column (TerrainHeights.columnSurface), scanning down from the heightmap to
	 * the first natural block: the top of what a block collides with, so a slab is half a step and
	 * a dirt path 15/16 (anything taller, a fence, counts as one block); with openUnder a built roof
	 * over room for a car isn't ground, so cars drive into garages.
	 */
	static float surface(LevelChunk chunk, int lx, int lz, BlockPos.MutableBlockPos p) {
		int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
		int bx = chunk.getPos().getMinBlockX() + lx, bz = chunk.getPos().getMinBlockZ() + lz;
		if (top <= chunk.getMinBuildHeight()) {
			// An empty heightmap: Minecraft ignores a stored one of the wrong length (a map saved for
			// another world height) and leaves it empty, which reads as the world's floor and made a
			// 1944-block pit in BeamNG (the town map, 2026-10-03). Find the top block instead.
			int y = chunk.getMinBuildHeight() + (chunk.getHighestFilledSectionIndex() + 1) * 16 - 1;
			while (y >= chunk.getMinBuildHeight() && chunk.getBlockState(p.set(bx, y, bz)).isAir()) {
				y--;
			}
			top = y + 1;
		}
		int bottom = Math.max(chunk.getMinBuildHeight(), top - SCAN_DEPTH);
		POSTS.start(bx, bz);
		int n = 0, soil = 0, loose = 0;   // natural blocks in a row, SOIL ones in a row
		boolean paved = false;            // those right under a built block: a street's (TerrainHeights.columnSurface)
		for (int y = top; y >= bottom; y--) {
			BlockState s = chunk.getBlockState(p.set(bx, y, bz));
			if (TerrainPosts.post(s)) {
				var b = s.getCollisionShape(chunk, p).bounds();
				POSTS.thin(y, b.minX, b.maxX, b.minZ, b.maxZ, b.maxY);
				KINDS[n++] = TerrainHeights.AIR;   // a car passes it in the terrain: the post is a box (TerrainPosts)
				soil = 0;
				loose = 0;
				continue;
			}
			if (ground(s)) {
				POSTS.solid();
				double h = s.getCollisionShape(chunk, p).max(Direction.Axis.Y);
				boolean built = !natural(s);
				TOPS[n] = (float) (h > 0 && h < 1 ? h : 1);
				KINDS[n++] = built ? TerrainHeights.BUILT : soil(s) ? TerrainHeights.SOIL : TerrainHeights.NATURAL;
				soil = built ? 0 : soil + 1;
				if (KINDS[n - 1] == TerrainHeights.SOIL) {
					paved = loose == 0 ? n > 1 && KINDS[n - 2] == TerrainHeights.BUILT : paved;
					loose++;
				} else {
					loose = 0;
				}
				// enough to decide: a street on soil, or natural ground too deep for any roof
				if (!openUnder || (paved && loose > TerrainHeights.ROOF_SOIL) || soil > TerrainHeights.TUNNEL_COVER) {
					break;
				}
			} else {
				POSTS.open();
				KINDS[n++] = s.getFluidState().isEmpty() ? TerrainHeights.AIR : TerrainHeights.FLUID;
				soil = 0;
				loose = 0;
			}
		}
		if (endVoid && noGround(n)) {
			lastDecks = 0;
			lastNatural = false;
			return END_VOID_Y;   // nothing under the sky: the void between the End's islands
		}
		float s = TerrainHeights.columnSurface(top, KINDS, TOPS, n, openUnder);
		lastDecks = openUnder ? TerrainHeights.decks(top, KINDS, TOPS, n, s, LAST_DECKS) : 0;
		lastNatural = TerrainHeights.naturalSurface(top, KINDS, n, s);
		return s;
	}

	private static boolean noGround(int n) {
		for (int i = 0; i < n; i++) {
			if (TerrainHeights.solid(KINDS[i])) {
				return false;
			}
		}
		return true;
	}

	public static void onServerTick(MinecraftServer server) {
		if (++ticks % FLUSH_TICKS != 0 || !BngWorld.isTerrainWorld()) {
			return;
		}
		BngLink link = BngLink.get();
		CrossoverCoords.Region region = BngWorld.region();
		// responsive, not just connected: patches sent while BeamNG stands still in a car export pile up
		// in its socket and the overflow is lost, and an edited chunk isn't sent twice
		if (!link.responsive() || region == null || server.getPlayerList().getPlayers().isEmpty() || userPath(link) == null) {
			return;
		}
		ServerLevel level = BngWorld.activeLevel(server);
		ServerPlayer player = server.getPlayerList().getPlayers().get(0);
		int px = player.getBlockX(), pz = player.getBlockZ();
		lastPlayer = new dev.bngmc.bridge.coords.V3(player.getX(), player.getY(), player.getZ());
		boolean otherDim = raw != null && level.dimension() != builtDim;
		boolean nearEdge = raw != null && (px < x0 + RECENTER_MARGIN || px >= x0 + AREA - RECENTER_MARGIN || pz < z0 + RECENTER_MARGIN
			|| pz >= z0 + AREA - RECENTER_MARGIN);
		Look want = Look.of(BridgeSettings.get());
		if (raw == null || builtFor != link.sessionId() || nearEdge || !want.equals(built) || otherDim) {
			if (loadedAround(level, px >> 4, pz >> 4)) {
				if (builtFor != link.sessionId() || otherDim) {
					carPlaced = false;   // a new BeamNG session, or the End: the car comes to the player
				}
				build(level, link, region, px, pz, want);
			}
			return;
		}
		if (!loaded) {   // no answer yet: patches would find no terrain
			if (System.currentTimeMillis() - sentMs > RESEND_MS && lastLoad != null && link.send(Protocol.TERRAIN_LOAD, lastLoad)) {
				sentMs = System.currentTimeMillis();
				LOG.info("No answer to the terrain load yet: sent again");
			}
			return;
		}
		if (!carPlaced) {
			carPlaced = placeCar(level, link, region, player);
		}
		refresh(server, level, link, region);
	}

	/**
	 * Once a session: BeamNG's car, if it is far away (it stood on BeamNG's floor somewhere), onto
	 * open ground near the player, facing where the player looks (BeamNG's vehicle_place, which puts it
	 * on the ground with spawn.safeTeleport). False until BeamNG's car is known.
	 */
	private static boolean placeCar(ServerLevel level, BngLink link, CrossoverCoords.Region region, ServerPlayer player) {
		var env = link.latest(Protocol.STATE);
		dev.bngmc.bridge.link.BngState st = env != null ? dev.bngmc.bridge.link.BngState.parse(env, BngLink.nowMs()) : null;
		if (st == null || st.vehicle() == null) {
			return false;
		}
		dev.bngmc.bridge.coords.V3 car = CrossoverCoords.canonicalToMinecraftPosition(st.vehicle().pos(), region);
		if (Math.hypot(car.x() - player.getX(), car.z() - player.getZ()) < CAR_NEAR) {
			return true;
		}
		int[] spot = openSpot(level, player.getBlockX(), player.getBlockZ());
		int col = spot[0] - x0, row = spot[1] - z0;
		if (col < 0 || row < 0 || col >= AREA || row >= AREA) {
			return true;
		}
		double yaw = Math.toRadians(player.getYRot());
		double dx = -Math.sin(yaw), dz = Math.cos(yaw);   // where the player looks, Minecraft axes
		JsonObject msg = new JsonObject();
		JsonArray pos = new JsonArray();
		pos.add((spot[0] + 0.5 - region.mcOriginX()) / region.scale());
		pos.add(-(spot[1] + 0.5 - region.mcOriginZ()) / region.scale());
		pos.add(canonicalZ(done[row * AREA + col], region) + 0.6);
		JsonArray fwd = new JsonArray();
		fwd.add(dx);
		fwd.add(-dz);
		fwd.add(0);
		msg.add("pos", pos);
		msg.add("fwd", fwd);
		if (link.send(Protocol.VEHICLE_PLACE, msg)) {
			LOG.info("BeamNG's car brought from Minecraft ({}, {}) to open ground at ({}, {}) beside the player", (int) car.x(), (int) car.z(),
				spot[0], spot[1]);
		}
		return true;
	}

	/**
	 * Open ground near (px, pz): the nearest 9 x 9 patch, in loaded chunks, that is flat (one block
	 * of height at most), dry, and has nothing over it (no tree: the heightmap with leaves is the
	 * surface). The player's own column if there is none.
	 */
	static int[] openSpot(ServerLevel level, int px, int pz) {
		int[] best = {px, pz};
		double bestD = Double.MAX_VALUE;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dz = -SPOT_RADIUS; dz <= SPOT_RADIUS; dz += 2) {
			for (int dx = -SPOT_RADIUS; dx <= SPOT_RADIUS; dx += 2) {
				double d = dx * dx + dz * dz;
				if (d >= bestD || d < 16) {   // not on top of the player
					continue;
				}
				if (open(level, px + dx, pz + dz, p)) {
					bestD = d;
					best = new int[] {px + dx, pz + dz};
				}
			}
		}
		return best;
	}

	private static boolean open(ServerLevel level, int cx, int cz, BlockPos.MutableBlockPos p) {
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (int z = cz - SPOT_HALF; z <= cz + SPOT_HALF; z++) {
			for (int x = cx - SPOT_HALF; x <= cx + SPOT_HALF; x++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
				if (chunk == null) {
					return false;
				}
				int ground = (int) surface(chunk, x & 15, z & 15, p);
				int any = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;   // leaves and water too
				if (any > ground) {
					return false;   // a tree or water over it
				}
				lo = Math.min(lo, ground);
				hi = Math.max(hi, ground);
				if (hi - lo > 1) {
					return false;
				}
			}
		}
		return true;
	}

	/** Blocks around the asked-for place searched for open ground for a new car. */
	public static final int SPAWN_SEARCH = 64;
	/** Ground steps allowed under a new car: a slab, not a block (a block is a 0.71 m wall in BeamNG). */
	private static final float SPAWN_MAX_STEP = 0.5F;

	/**
	 * A place for a new car: block column (x, z), BeamNG's terrain height there (canonical metres) and
	 * how many blocks it is from where it was asked for.
	 */
	public record Spot(int x, int z, double canonicalZ, double movedBlocks) {
	}

	/**
	 * Server thread: where a car asked for at (wantX, wantZ) can stand. The place itself when its
	 * ground is open (nothing over it: not under a roof, a tree or water; Minecraft's motion-blocking
	 * heightmap is no higher than the ground TerrainSync reads), flat (half a block across a 9 x 9
	 * patch), loaded and inside BeamNG's terrain; else the nearest such place within SPAWN_SEARCH
	 * blocks. A car asked for indoors used to be put on BeamNG's one-height ground there: on the roof,
	 * or under the terrain and through the floor (2026-10-03). null when nothing qualifies or BeamNG
	 * has no terrain yet.
	 */
	public static Spot spawnSpot(ServerLevel level, double wantX, double wantZ, CrossoverCoords.Region region) {
		if (raw == null || done == null || !loaded || region == null) {
			return null;
		}
		int wx = (int) Math.floor(wantX), wz = (int) Math.floor(wantZ);
		int r = SPAWN_SEARCH + SPOT_HALF, side = 2 * r + 1;
		float[] ground = new float[side * side];
		boolean[] open = new boolean[side * side];
		java.util.Arrays.fill(ground, Float.NaN);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				int x = wx + dx, z = wz + dz;
				int col = x - x0, row = z - z0;
				if (col < 0 || row < 0 || col >= AREA || row >= AREA) {
					continue;
				}
				LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
				if (chunk == null) {
					continue;
				}
				float g = surface(chunk, x & 15, z & 15, p);
				int any = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;   // leaves and water too
				int i = (dz + r) * side + dx + r;
				ground[i] = g;
				open[i] = any <= Math.ceil(g);
			}
		}
		int[] c = TerrainHeights.nearestOpen(ground, open, side, side, SPOT_HALF, SPAWN_MAX_STEP, r, r);
		if (c == null) {
			return null;
		}
		int x = wx + c[0] - r, z = wz + c[1] - r;
		return new Spot(x, z, canonicalZ(done[(z - z0) * AREA + (x - x0)], region), Math.hypot(c[0] - r, c[1] - r));
	}

	private static boolean loadedAround(ServerLevel level, int cx, int cz) {
		for (int x = cx - READY_RADIUS; x <= cx + READY_RADIUS; x++) {
			for (int z = cz - READY_RADIUS; z <= cz + READY_RADIUS; z++) {
				if (level.getChunkSource().getChunkNow(x, z) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static String userPath(BngLink link) {
		var w = link.welcome();
		return w != null && w.body().has("user") && w.body().get("user").isJsonPrimitive() ? w.body().get("user").getAsString() : null;
	}

	/** Reads every loaded chunk of the area, writes the heightmap, has BeamNG build the terrain. */
	private static void build(ServerLevel level, BngLink link, CrossoverCoords.Region region, int px, int pz, Look look) {
		long t0 = System.nanoTime();
		openUnder = look.open();
		endVoid = level.dimension() == Level.END;
		builtDim = level.dimension();
		x0 = ((px >> 4) - CHUNKS / 2) << 4;
		z0 = ((pz >> 4) - CHUNKS / 2) << 4;
		TerrainPosts.reset(link);   // the new terrain's chunks are read again, and their posts with them
		raw = new float[AREA * AREA];
		java.util.Arrays.fill(raw, TerrainHeights.UNKNOWN);
		decks = new float[AREA * AREA * TerrainHeights.MAX_DECKS];
		java.util.Arrays.fill(decks, Float.NaN);
		onDeck = new byte[AREA * AREA];
		java.util.Arrays.fill(onDeck, (byte) -1);
		grounds = new float[AREA * AREA];
		java.util.Arrays.fill(grounds, Float.NaN);
		naturals = new boolean[AREA * AREA];
		known = new boolean[CHUNKS * CHUNKS];
		int read = 0;
		seaColumns = 0;
		for (int cz = 0; cz < CHUNKS; cz++) {
			for (int cx = 0; cx < CHUNKS; cx++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow((x0 >> 4) + cx, (z0 >> 4) + cz);
				if (chunk != null) {
					readChunk(chunk, cx, cz);
					read++;
				}
			}
		}
		// cars already up on a deck keep it from the first load
		decideDecks(region, false);
		for (int i = 0; i < raw.length; i++) {
			if (onDeck[i] >= 0) {
				raw[i] = decks[i * TerrainHeights.MAX_DECKS + onDeck[i]];
				naturals[i] = false;
			}
		}
		float median = TerrainHeights.median(raw, 64F);
		for (int i = 0; i < raw.length; i++) {
			if (Float.isNaN(raw[i])) {
				raw[i] = median;
			}
		}
		done = TerrainHeights.smooth(raw, AREA, naturals, look.radius());
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		for (float v : done) {
			lo = Math.min(lo, v);
			hi = Math.max(hi, v);
		}
		baseZ = canonicalZ(lo - FOOTROOM, region);
		float top = Math.max(hi + HEADROOM, Math.min(level.getMaxBuildHeight(), lo + MIN_SPAN));
		range = (top - lo + FOOTROOM) / region.scale();
		Path file = Path.of(userPath(link), "temp", "mccross", FILE);
		try {
			Files.createDirectories(file.getParent());
			writePng(file, region, look.perBlock());
		} catch (IOException e) {
			LOG.warn("Couldn't write the terrain heightmap {}: {}", file, e.toString());
			raw = null;
			return;
		}
		int k = look.perBlock();
		double half = 0.5 / k;   // blocks from a block's edge to its first sample
		JsonObject msg = new JsonObject();
		msg.addProperty("file", "/temp/mccross/" + FILE);
		msg.addProperty("size", AREA * k);
		msg.addProperty("square", 1.0 / (region.scale() * k));
		msg.addProperty("x0", (x0 + half - region.mcOriginX()) / region.scale());
		// grid row 0 is the image's last row, the southernmost samples (BeamNG y grows north)
		msg.addProperty("y0", -(z0 + AREA - half - region.mcOriginZ()) / region.scale());
		msg.addProperty("z0", baseZ);
		msg.addProperty("height", range);
		if (look.sea() && seaColumns > 0) {
			// Minecraft's sea: water up to the top of y = sea level - 1, so its surface is at sea level.
			// Only where the world has one: an old superflat map's sea level was 30 blocks over its ground
			msg.addProperty("sea", canonicalZ(level.getSeaLevel(), region));
		}
		msg.addProperty("id", ++loadId);
		awaitedId = loadId;
		loaded = false;
		if (link.send(Protocol.TERRAIN_LOAD, msg)) {
			UNACKED.clear();   // patches of the terrain this one replaces
			builtFor = link.sessionId();
			built = look;
			sentMs = System.currentTimeMillis();
			lastLoad = msg;
			DIRTY.clear();
			LOG.info("Terrain for BeamNG around ({}, {}), {}: {} of {} chunks read, heights {}..{}, sea level {} in {} columns, built in {} ms", px,
				pz, look.describe(), read, CHUNKS * CHUNKS, lo, hi, level.getSeaLevel(), seaColumns,
				String.format("%.0f", (System.nanoTime() - t0) / 1e6));
		} else {
			raw = null;
		}
	}

	private static void readChunk(LevelChunk chunk, int cx, int cz) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		it.unimi.dsi.fastutil.floats.FloatArrayList posts = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		int sea = chunk.getLevel().getSeaLevel() - 1;   // the top water block of a sea
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int idx = (cz * 16 + lz) * AREA + cx * 16 + lx;
				float s = surface(chunk, lx, lz, p);
				posts.addAll(POSTS.boxes);
				grounds[idx] = s;
				for (int d = 0; d < TerrainHeights.MAX_DECKS; d++) {
					decks[idx * TerrainHeights.MAX_DECKS + d] = d < lastDecks ? LAST_DECKS[d] : Float.NaN;
				}
				if (onDeck[idx] >= lastDecks) {
					onDeck[idx] = -1;   // that deck is gone (built over, dug out)
				}
				raw[idx] = onDeck[idx] >= 0 ? LAST_DECKS[onDeck[idx]] : s;
				naturals[idx] = onDeck[idx] < 0 && lastNatural;
				int wet = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz);   // counts fluids, unlike surface()
				if (wet == sea && chunk.getFluidState(p.set(chunk.getPos().getMinBlockX() + lx, wet, chunk.getPos().getMinBlockZ() + lz))
					.is(net.minecraft.tags.FluidTags.WATER)) {
					seaColumns++;
				}
			}
		}
		known[cz * CHUNKS + cx] = true;
		TerrainPosts.store(chunk.getPos().toLong(), posts.toFloatArray());
	}

	/** Chunks with changed blocks, then chunks loaded since the build, as patches. */
	private static void refresh(MinecraftServer server, ServerLevel level, BngLink link, CrossoverCoords.Region region) {
		resendUnacked();
		decideDecks(region, true);
		var carsMc = new java.util.ArrayList<TerrainPosts.Car>();
		for (var v : dev.bngmc.bridge.link.Vehicles.latest().values()) {
			if (v.pos() != null) {
				double speed = v.vel() != null && v.vel().length() < 80 ? v.vel().length() * region.scale() : 0;
				carsMc.add(new TerrainPosts.Car(CrossoverCoords.canonicalToMinecraftPosition(v.pos(), region), speed));
			}
		}
		TerrainPosts.update(link, region, carsMc);
		int sent = 0;
		// blocks someone changed first: a hole just dug must be a hole before the car gets there
		while (!DIRTY.isEmpty() && sent < PATCHES_PER_FLUSH) {
			long key = DIRTY.removeFirstLong();
			int cx = ChunkPos.getX(key) - (x0 >> 4), cz = ChunkPos.getZ(key) - (z0 >> 4);
			if (cx < 0 || cz < 0 || cx >= CHUNKS || cz >= CHUNKS) {
				continue;
			}
			LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
			if (chunk != null && patch(link, region, chunk, cx, cz)) {
				sent++;
				editsSent++;
				LOG.info("Terrain patch for BeamNG: chunk ({}, {}) after a block change", ChunkPos.getX(key), ChunkPos.getZ(key));
			}
		}
		// as far as Minecraft loads chunks around a player (its view distance, 12 here): a car parked
		// 161-192 blocks away used to sit on the stand-in height (audit 2026-10-03)
		int radius = Math.max(REFRESH_RADIUS, server.getPlayerList().getViewDistance());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			int pcx = (player.getBlockX() - x0) >> 4, pcz = (player.getBlockZ() - z0) >> 4;
			for (int d = 0; d <= radius && sent < PATCHES_PER_FLUSH; d++) {   // nearest rings first
				for (int cz = pcz - d; cz <= pcz + d && sent < PATCHES_PER_FLUSH; cz++) {
					for (int cx = pcx - d; cx <= pcx + d && sent < PATCHES_PER_FLUSH; cx++) {
						if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) != d || cx < 0 || cz < 0 || cx >= CHUNKS || cz >= CHUNKS
							|| known[cz * CHUNKS + cx]) {
							continue;
						}
						LevelChunk chunk = level.getChunkSource().getChunkNow((x0 >> 4) + cx, (z0 >> 4) + cz);
						if (chunk != null && patch(link, region, chunk, cx, cz)) {
							sent++;
						}
					}
				}
			}
		}
	}

	/**
	 * Around each BeamNG car, as far as LEAD_S of its travel and at least DECK_RADIUS, each column
	 * with decks gets the level (its surface or a deck) the car can drive to soonest
	 * (TerrainHeights.reach): the street under an elevated road for a car on the street, the deck for
	 * one up on it, a ramp for one climbing it. A column no car reaches keeps its choice. With patch,
	 * changed chunks nearest a car go first. The car nearest the player decides a column two cars
	 * share.
	 */
	private static void decideDecks(CrossoverCoords.Region region, boolean patch) {
		if (decks == null || grounds == null || !openUnder) {
			return;
		}
		var cars = new java.util.ArrayList<>(dev.bngmc.bridge.link.Vehicles.latest().values());
		cars.removeIf(v -> v.pos() == null);
		if (cars.isEmpty()) {
			return;
		}
		dev.bngmc.bridge.coords.V3 me = lastPlayer;
		if (me != null) {   // farthest first, so the nearest car's choice is the one that stays
			cars.sort(java.util.Comparator.comparingDouble((dev.bngmc.bridge.link.Vehicles.Info v) ->
				-CrossoverCoords.canonicalToMinecraftPosition(v.pos(), region).sub(me).length()));
		}
		java.util.Map<Integer, Byte> choice = new java.util.HashMap<>();
		java.util.Map<Integer, Integer> near = new java.util.HashMap<>();   // column -> squared distance to its car
		final int L = TerrainHeights.LEVELS, D = TerrainHeights.MAX_DECKS;
		for (var v : cars) {
			dev.bngmc.bridge.coords.V3 at = CrossoverCoords.canonicalToMinecraftPosition(v.pos(), region);
			double speed = v.vel() != null && v.vel().length() < 80 ? v.vel().length() * region.scale() : 0;   // blocks/s
			int r = (int) Math.min(MAX_TRACE, DECK_RADIUS + speed * LEAD_S);
			int size = 2 * r + 1;
			int cc = (int) Math.floor(at.x()) - x0, cr = (int) Math.floor(at.z()) - z0;
			if (cc < 0 || cr < 0 || cc >= AREA || cr >= AREA) {
				continue;
			}
			if (deckLevels.length < size * size * L) {
				deckLevels = new float[size * size * L];
			}
			float[] lv = deckLevels;
			java.util.Arrays.fill(lv, 0, size * size * L, Float.NaN);
			boolean anyDeck = false;
			for (int wr = 0; wr < size; wr++) {
				int row = cr - r + wr;
				if (row < 0 || row >= AREA) {
					continue;
				}
				for (int wc = 0; wc < size; wc++) {
					int col = cc - r + wc;
					if (col < 0 || col >= AREA) {
						continue;
					}
					int idx = row * AREA + col, w = (wr * size + wc) * L;
					lv[w] = grounds[idx];
					for (int k = 0; k < D; k++) {
						lv[w + 1 + k] = decks[idx * D + k];
					}
					anyDeck |= !Float.isNaN(decks[idx * D]);
				}
			}
			if (!anyDeck) {
				continue;   // no choice to make around this car
			}
			byte[] reached = TerrainHeights.reach(lv, size, (float) at.y());
			for (int wr = 0; wr < size; wr++) {
				for (int wc = 0; wc < size; wc++) {
					int row = cr - r + wr, col = cc - r + wc;
					int o = reached[wr * size + wc];
					if (o < 0 || row < 0 || col < 0 || row >= AREA || col >= AREA || Float.isNaN(decks[(row * AREA + col) * D])) {
						continue;
					}
					choice.put(row * AREA + col, (byte) (o - 1));   // level 0 is the surface (-1), 1 + k deck k
					near.put(row * AREA + col, (wr - r) * (wr - r) + (wc - r) * (wc - r));
				}
			}
		}
		java.util.Map<Long, Integer> changed = new java.util.HashMap<>();   // chunk -> nearest changed column's squared distance
		for (var e : choice.entrySet()) {
			int idx = e.getKey();
			byte want = e.getValue();
			if (want == onDeck[idx]) {
				continue;
			}
			onDeck[idx] = want;
			changed.merge(ChunkPos.asLong((x0 + idx % AREA) >> 4, (z0 + idx / AREA) >> 4), near.get(idx), Math::min);
		}
		if (patch) {   // farthest first to the front, so the chunks nearest a car go out first
			changed.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.forEach(e -> DIRTY.addAndMoveToFirst(e.getKey()));
		}
	}

	private static float[] deckLevels = new float[0];   // decideDecks' window, reused (server thread)
	private static volatile dev.bngmc.bridge.coords.V3 lastPlayer;

	/** Server thread: chunks whose patch BeamNG never acknowledged go round again, as an edit (sent first). */
	private static void resendUnacked() {
		if (!acks || UNACKED.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		var it = UNACKED.entrySet().iterator();
		while (it.hasNext()) {
			long[] v = it.next().getValue();
			if (now - v[1] > ACK_MS) {
				it.remove();
				if (DIRTY.add(v[0])) {
					resent++;
				}
			}
		}
	}

	/**
	 * Re-reads one chunk and sends its samples, and the blur's reach around it, to BeamNG: one
	 * terrain_cells message for slopes, two for real blocks (four times the samples), each with a pid
	 * BeamNG acknowledges (terrain_ack).
	 */
	private static boolean patch(BngLink link, CrossoverCoords.Region region, LevelChunk chunk, int cx, int cz) {
		readChunk(chunk, cx, cz);
		Look look = built;
		int r = look.radius();
		// the blur's reach, and in real blocks the next block, whose edge samples lean toward a kerb here
		int reach = Math.max(r, look.perBlock() > 1 ? 1 : 0);
		int c0 = Math.max(0, cx * 16 - reach), c1 = Math.min(AREA - 1, cx * 16 + 15 + reach);
		int r0 = Math.max(0, cz * 16 - reach), r1 = Math.min(AREA - 1, cz * 16 + 15 + reach);
		TerrainHeights.smooth(raw, AREA, naturals, done, r0, c0, r1, c1, r);
		boolean ok = true;
		for (TerrainHeights.Cells cells : TerrainHeights.cells(done, AREA, look.perBlock(), r0, c0, r1, c1, TerrainHeights.MAX_CELLS,
			h -> Math.round(Math.max(0, Math.min(range - 0.01, canonicalZ(h, region) - baseZ)) * 1000) / 1000.0)) {
			JsonArray z = new JsonArray();
			for (double v : cells.z()) {
				z.add(v);
			}
			JsonObject msg = new JsonObject();
			msg.addProperty("x", cells.x());
			msg.addProperty("y", cells.y());
			msg.addProperty("w", cells.w());
			msg.addProperty("h", cells.h());
			msg.add("z", z);
			int pid = ++nextPid;
			msg.addProperty("pid", pid);
			if (link.send(Protocol.TERRAIN_CELLS, msg)) {
				UNACKED.put(pid, new long[] {ChunkPos.asLong((x0 >> 4) + cx, (z0 >> 4) + cz), System.currentTimeMillis()});
			} else {
				ok = false;
			}
		}
		if (ok) {
			patchesSent++;
		} else {
			known[cz * CHUNKS + cx] = false;   // read again and resent when a player is near
		}
		return ok;
	}

	/**
	 * Dev: the terrain's state over blocks x0..x1, z0..z1 (inclusive), for tests that compare it with
	 * BeamNG's own heights (DevCommands "tdump"): per column the raw height BeamNG is built from,
	 * the driven (smoothed) height, the surface, the decks and which level is chosen. Read without
	 * locking, from whatever thread: good enough for a diagnosis.
	 */
	public static JsonObject dump(int bx0, int bz0, int bx1, int bz1) {
		JsonObject out = new JsonObject();
		float[] r = raw, d = done, g = grounds, dk = decks;
		byte[] od = onDeck;
		if (r == null || d == null || g == null || dk == null || od == null) {
			out.addProperty("error", "no terrain");
			return out;
		}
		out.addProperty("x0", bx0);
		out.addProperty("z0", bz0);
		out.addProperty("w", bx1 - bx0 + 1);
		out.addProperty("h", bz1 - bz0 + 1);
		out.addProperty("terrainX0", x0);
		out.addProperty("terrainZ0", z0);
		JsonArray rows = new JsonArray();
		for (int z = bz0; z <= bz1; z++) {
			for (int x = bx0; x <= bx1; x++) {
				int c = x - x0, row = z - z0;
				JsonArray v = new JsonArray();
				if (c < 0 || row < 0 || c >= AREA || row >= AREA) {
					rows.add(v);
					continue;
				}
				int i = row * AREA + c;
				v.add(r[i]);
				v.add(d[i]);
				v.add(g[i]);
				for (int k = 0; k < TerrainHeights.MAX_DECKS; k++) {
					v.add(Float.isNaN(dk[i * TerrainHeights.MAX_DECKS + k]) ? -999 : dk[i * TerrainHeights.MAX_DECKS + k]);
				}
				v.add(od[i]);
				v.add(known != null && known[(row >> 4) * CHUNKS + (c >> 4)] ? 1 : 0);
				rows.add(v);
			}
		}
		out.add("cols", rows);   // [raw, done, surface, deck0, deck1, deck2, choice, chunk read]
		return out;
	}

	private static double canonicalZ(double surfaceY, CrossoverCoords.Region region) {
		return (surfaceY - region.mcOriginY()) / region.scale();
	}

	/**
	 * 16 bits, 0..65535 = baseZ..baseZ + range; row 0 the northernmost samples, as BeamNG reads
	 * images; perBlock x perBlock samples per block.
	 */
	private static void writePng(Path file, CrossoverCoords.Region region, int perBlock) throws IOException {
		int n = AREA * perBlock;
		BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_USHORT_GRAY);
		WritableRaster r = img.getRaster();
		int[] line = new int[n];
		for (int row = 0; row < n; row++) {
			for (int c = 0; c < n; c++) {
				double v = (canonicalZ(TerrainHeights.sampleKerbs(done, AREA, perBlock, row, c), region) - baseZ) / range;
				line[c] = (int) Math.round(Math.max(0, Math.min(1, v)) * 65535);
			}
			r.setSamples(0, row, n, 1, 0, line);
		}
		Path tmp = file.resolveSibling(FILE + ".part");
		ImageIO.write(img, "png", tmp.toFile());
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * A linked world without a terrain (the BeamNG-hosted bridge world, the superflat host world)
	 * opened: BeamNG drops one a terrain world left behind. After BngWorld.onServerStarted.
	 */
	public static void onServerStarted(MinecraftServer server) {
		if (BngWorld.isLinkedWorld() && !BngWorld.isTerrainWorld() && BngLink.get().connected()) {
			BngLink.get().send(Protocol.TERRAIN_RESET, new JsonObject());
		}
	}

	/**
	 * World closing. BeamNG keeps the terrain: taken out, every car on it fell to the floor below
	 * and the next load lifted it onto whatever stood over it (seen: the car on the garage roof
	 * after each restart). The next load replaces it, or onServerStarted of a world without one.
	 */
	public static void onServerStopping(MinecraftServer server) {
		UNACKED.clear();
		raw = null;
		done = null;
		known = null;
		decks = null;
		onDeck = null;
		grounds = null;
		naturals = null;
		TerrainPosts.clear();
		builtFor = -1;
		builtDim = null;
		built = null;
		loaded = false;
		lastLoad = null;
		carPlaced = false;
		DIRTY.clear();
	}
}
