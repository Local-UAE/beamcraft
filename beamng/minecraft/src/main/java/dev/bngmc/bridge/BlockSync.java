package dev.bngmc.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft blocks become obstacles for BeamNG cars: every solid, non-terrain block in the bridge
 * world has a 1 m collision cube in BeamNG at the same place (bridge.lua, handlers.blocks).
 *
 * <p>Every block change goes through LevelChunk.setBlockState (LevelChunkMixin -> {@link #onChange}),
 * including placing, breaking and explosions. Changes are batched and sent every
 * {@link #FLUSH_TICKS} ticks. The set of tracked blocks is saved in the world folder, so after a
 * reconnect, a BeamNG restart or a level change, BeamNG gets the full set again.
 */
public final class BlockSync {
	private BlockSync() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static final String FILE = "bngbridge-blocks.txt";
	private static final int FLUSH_TICKS = 5;
	/** Cells per message: ~15 characters each keeps a message under the 8 KB limit. */
	private static final int CELLS_PER_MESSAGE = 400;

	private static final LongOpenHashSet SOLID = new LongOpenHashSet();
	private static final LongOpenHashSet DIRTY = new LongOpenHashSet();
	private static volatile boolean active;
	private static boolean dirtyFile;
	private static long sentForSession = -1;
	private static CrossoverCoords.Region sentForRegion;
	private static int ticks;
	private static boolean scanned;
	/** BeamNG's cubes are cleared while the player is in the End: they are the overworld's tree trunks. */
	private static boolean clearedAway;
	/** Chunks around each player searched once for blocks placed before mirroring existed. */
	private static final int SCAN_RADIUS_CHUNKS = 8;
	/** In a real-terrain world: tree trunks only, and fewer chunks (BeamNG takes 4000 cubes). */
	private static final int TERRAIN_SCAN_RADIUS_CHUNKS = 4;

	/**
	 * In a Minecraft-hosted world the superflat ground is BeamNG's own floor, so only blocks from
	 * its top up are mirrored (hundreds of thousands of ground blocks would not fit anyway).
	 */
	static boolean mirrorsY(int y, boolean hostWorld) {
		return !hostWorld || BngWorld.isTerrainWorld() || y >= BngWorld.HOST_GROUND_TOP_Y;
	}

	/**
	 * A block that becomes a BeamNG cube. None in a real-terrain world: the ground and everything built
	 * on it is BeamNG terrain (TerrainSync), and tree trunks and thin posts are boxes near the cars
	 * (TerrainPosts). Every log ever scanned used to be a cube, ~3000 by the end of a night in Newisle,
	 * and each collision rebuild took 145 ms.
	 */
	static boolean mirrored(BlockState s) {
		return counts(s) && !BngWorld.isTerrainWorld();
	}

	/** Solid enough to stop a car: something to collide with (TerrainSync.collides) and not BeamNG's own invisible terrain. */
	static boolean counts(BlockState s) {
		return s != null && !s.isAir() && !s.is(BngBridgeMod.TERRAIN) && TerrainSync.collides(s);
	}

	/** Server thread, from LevelChunkMixin, for every block change in a loaded chunk. */
	public static void onChange(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
		if (active && !level.isClientSide && counts(oldState) != counts(newState)) {
			GroundSync.onChange(level, pos);   // the ground layers go as boxes, not cubes
		}
		if (active && !level.isClientSide && (TerrainSync.ground(oldState) != TerrainSync.ground(newState)
			|| TerrainPosts.post(oldState) != TerrainPosts.post(newState))) {
			TerrainSync.onChange(level, pos);  // or reshape the terrain: its own idea of ground (a log turned to stone too)
		}
		if (!active || level.isClientSide || level.dimension() != Level.OVERWORLD || !mirrorsY(pos.getY(), BngWorld.isHostWorld())) {
			return;
		}
		boolean was = mirrored(oldState);
		boolean is = mirrored(newState);
		if (was == is) {
			return;
		}
		long key = pos.asLong();
		if (is) {
			SOLID.add(key);
		} else {
			SOLID.remove(key);
		}
		DIRTY.add(key);
		dirtyFile = true;
	}

	public static void onServerStarted(MinecraftServer server) {
		reset();
		if (!BngWorld.isLinkedWorld()) {
			return;
		}
		Path p = path(server);
		if (Files.exists(p) && !BngWorld.isTerrainWorld()) {
			try {
				for (String line : Files.readAllLines(p)) {
					line = line.strip();
					if (!line.isEmpty()) {
						SOLID.add(Long.parseLong(line));
					}
				}
				LOG.info("Loaded {} Minecraft blocks to mirror in BeamNG", SOLID.size());
			} catch (IOException | NumberFormatException e) {
				LOG.warn("Ignoring unreadable {}: {}", p, e.toString());
			}
		}
		active = true;
	}

	public static void onServerStopping(MinecraftServer server) {
		if (active) {
			save(server);
		}
		reset();
	}

	public static void reset() {
		active = false;
		SOLID.clear();
		DIRTY.clear();
		sentForSession = -1;
		sentForRegion = null;
		dirtyFile = false;
		scanned = false;
		clearedAway = false;
	}

	public static int count() {
		return SOLID.size();
	}

	public static void onServerTick(MinecraftServer server) {
		if (!active || ++ticks % FLUSH_TICKS != 0) {
			return;
		}
		BngLink link = BngLink.get();
		CrossoverCoords.Region region = BngWorld.region();
		if (!link.responsive() || region == null) {   // block edits wait out a BeamNG freeze (DIRTY is kept)
			return;
		}
		if (BngWorld.activeLevel(server) != server.overworld()) {
			if (!clearedAway) {
				send(link, region, List.of(), List.of(), true);
				clearedAway = true;
				sentForSession = -1;   // all of them again on the way back
			}
			return;
		}
		clearedAway = false;
		if (!scanned && !server.getPlayerList().getPlayers().isEmpty()) {
			scanned = true;
			scanLoaded(server);
		}
		if (link.sessionId() != sentForSession || region != sentForRegion) {
			// New BeamNG session or level: send everything in this level's region.
			List<Long> all = new ArrayList<>();
			for (LongIterator it = SOLID.iterator(); it.hasNext(); ) {
				long k = it.nextLong();
				if (region.containsMcX(BlockPos.getX(k))) {
					all.add(k);
				}
			}
			send(link, region, all, List.of(), true);
			DIRTY.clear();
			sentForSession = link.sessionId();
			sentForRegion = region;
			LOG.info("Mirrored {} Minecraft blocks into BeamNG as collision cubes", all.size());
		} else if (!DIRTY.isEmpty()) {
			List<Long> add = new ArrayList<>();
			List<Long> remove = new ArrayList<>();
			for (LongIterator it = DIRTY.iterator(); it.hasNext(); ) {
				long k = it.nextLong();
				if (region.containsMcX(BlockPos.getX(k))) {
					(SOLID.contains(k) ? add : remove).add(k);
				}
			}
			DIRTY.clear();
			send(link, region, add, remove, false);
		}
		if (dirtyFile && ticks % 600 == 0) {
			save(server);
		}
	}

	private static void send(BngLink link, CrossoverCoords.Region region, List<Long> add, List<Long> remove, boolean clear) {
		if (!clear && add.isEmpty() && remove.isEmpty()) {
			return;
		}
		int i = 0, j = 0;
		boolean first = true;
		while (first || i < add.size() || j < remove.size()) {
			JsonObject msg = new JsonObject();
			if (first && clear) {
				msg.addProperty("clear", true);
			}
			msg.addProperty("cell", 1.0 / region.scale());   // metres per block
			JsonArray a = new JsonArray();
			JsonArray r = new JsonArray();
			int n = 0;
			for (; i < add.size() && n < CELLS_PER_MESSAGE; i++, n++) {
				cell(a, add.get(i), region);
			}
			for (; j < remove.size() && n < CELLS_PER_MESSAGE; j++, n++) {
				cell(r, remove.get(j), region);
			}
			if (a.size() > 0) {
				msg.add("add", a);
			}
			if (r.size() > 0) {
				msg.add("remove", r);
			}
			link.send(Protocol.BLOCKS, msg);
			first = false;
		}
	}

	/** Minecraft block (x, y, z) -> canonical cell min corner (x - ox, -z - 1 + oz, y - oy). */
	static int[] canonicalCell(int x, int y, int z, CrossoverCoords.Region r) {
		return new int[] {x - (int) r.mcOriginX(), -z - 1 + (int) r.mcOriginZ(), y - (int) r.mcOriginY()};
	}

	private static void cell(JsonArray out, long key, CrossoverCoords.Region region) {
		int[] c = canonicalCell(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), region);
		out.add(c[0]);
		out.add(c[1]);
		out.add(c[2]);
	}

	/**
	 * Adds solid blocks already in the loaded chunks around the players (placed before this
	 * mirroring existed, or by world edits that bypass chunks). Empty sections are skipped.
	 */
	private static void scanLoaded(MinecraftServer server) {
		ServerLevel level = server.overworld();
		long t0 = System.nanoTime();
		int before = SOLID.size();
		LongOpenHashSet seen = new LongOpenHashSet();
		boolean host = BngWorld.isHostWorld();
		int radius = BngWorld.isTerrainWorld() ? TERRAIN_SCAN_RADIUS_CHUNKS : SCAN_RADIUS_CHUNKS;
		for (var player : server.getPlayerList().getPlayers()) {
			int pcx = player.chunkPosition().x;
			int pcz = player.chunkPosition().z;
			for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
				for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
					if (!seen.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) {
						continue;
					}
					var chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) {
						continue;
					}
					var sections = chunk.getSections();
					for (int i = 0; i < sections.length; i++) {
						var sec = sections[i];
						if (sec == null || sec.hasOnlyAir()) {
							continue;
						}
						int baseY = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
						for (int y = 0; y < 16; y++) {
							for (int z = 0; z < 16; z++) {
								for (int x = 0; x < 16; x++) {
									if (mirrorsY(baseY + y, host) && mirrored(sec.getBlockState(x, y, z))) {
										long key = BlockPos.asLong((cx << 4) + x, baseY + y, (cz << 4) + z);
										if (SOLID.add(key)) {
											DIRTY.add(key);
										}
									}
								}
							}
						}
					}
				}
			}
		}
		if (SOLID.size() > before) {
			dirtyFile = true;
		}
		LOG.info("Block scan: {} chunks, {} existing blocks found ({} new) in {} ms", seen.size(), SOLID.size(), SOLID.size() - before,
			String.format("%.1f", (System.nanoTime() - t0) / 1e6));
	}

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
	}

	private static void save(MinecraftServer server) {
		StringBuilder b = new StringBuilder();
		for (LongIterator it = SOLID.iterator(); it.hasNext(); ) {
			b.append(it.nextLong()).append('\n');
		}
		try {
			Files.writeString(path(server), b);
			dirtyFile = false;
		} catch (IOException e) {
			LOG.warn("Could not save {}: {}", FILE, e.toString());
		}
	}

	/** For ServerLevel users that need it (dev commands). */
	public static boolean tracked(ServerLevel level, BlockPos pos) {
		return SOLID.contains(pos.asLong());
	}
}
