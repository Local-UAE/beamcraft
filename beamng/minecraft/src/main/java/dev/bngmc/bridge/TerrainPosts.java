package dev.bngmc.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Thin posts in a terrain world (docs/minecraft-host.md, "What a car hits in a city"): a lamp post,
 * a bollard, a lone fence post, an end rod. In the heightfield one filled a whole block and sloped
 * out a block each way, so a 0.36 m lamp post stood about 1.2 m wide at bumper height. TerrainSync
 * leaves them out of the terrain (TerrainSync.ground) and collects, for each chunk it reads, the ones
 * standing on something, as boxes of their own size; tree trunks and other logs too, which used to
 * be a BeamNG cube each for every log ever scanned. Those near a BeamNG car (POST_RADIUS plus its
 * travel) are in BeamNG (post_chunk, bridge.lua), and leave again when no car is near: every box
 * makes BeamNG's collision rebuilds slower. Connected fences, walls and railings are long, not thin:
 * they stay terrain. Server thread only.
 */
public final class TerrainPosts {
	private TerrainPosts() {
	}

	/** A collision shape narrower than this (blocks) both ways is a post. */
	static final double THIN = 0.75;
	/** Boxes are at least this wide (blocks): a pane's 2/16 is thinner than BeamNG's nodes need to hit. */
	static final double MIN_SIDE = 0.28;
	/**
	 * Blocks around a BeamNG car whose posts are in BeamNG, plus POST_LEAD_S of its travel, up to
	 * POST_MAX_RADIUS. Every box slows BeamNG's collision rebuild (~18 us each: 8000 objects took
	 * 145 ms), so only the posts a car could reach soon.
	 */
	static final int POST_RADIUS = 24;
	static final double POST_LEAD_S = 1.0;
	static final int POST_MAX_RADIUS = 64;
	/** Chunks sent (or taken out) per refresh. */
	private static final int SENDS_PER_REFRESH = 16;

	/** Per chunk with posts: boxes in blocks, {x0, y0, z0, x1, y1, z1} each. */
	private static final Long2ObjectOpenHashMap<float[]> CHUNKS = new Long2ObjectOpenHashMap<>();
	private static final LongOpenHashSet SENT = new LongOpenHashSet();    // chunks whose posts are in BeamNG
	private static final LongOpenHashSet CHANGED = new LongOpenHashSet(); // read again since: to send again

	private static final java.util.Map<BlockState, Boolean> THIN_STATES = new java.util.concurrent.ConcurrentHashMap<>();

	/** A post: a thin block, or a log (a tree trunk, a log wall), a box of its own rather than terrain. */
	static boolean post(BlockState s) {
		return thin(s) || (s != null && s.is(net.minecraft.tags.BlockTags.LOGS));
	}

	/** Has a collision shape narrower than THIN both ways (asked for every block a terrain build reads: cached). */
	static boolean thin(BlockState s) {
		if (s == null || s.isAir()) {
			return false;
		}
		return THIN_STATES.computeIfAbsent(s, st -> {
			VoxelShape v = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			if (v.isEmpty()) {
				return false;
			}
			AABB b = v.bounds();
			return b.getXsize() < THIN && b.getZsize() < THIN;
		});
	}

	/**
	 * One column's posts, scanned top down (TerrainSync.surface): thin blocks in a row make a post when
	 * the block under the lowest is solid; one hanging over air (a chain) is no obstacle a car meets.
	 */
	static final class Column {
		private int x, z;
		private boolean inRun;
		private int top, bottom;
		private double minX, maxX, minZ, maxZ, topHeight;
		final FloatArrayList boxes = new FloatArrayList();

		void start(int bx, int bz) {
			x = bx;
			z = bz;
			inRun = false;
			boxes.clear();
		}

		/** A thin block at y with its collision box (fractions of the block). */
		void thin(int y, double x0, double x1, double z0, double z1, double maxY) {
			if (!inRun) {
				inRun = true;
				top = y;
				minX = x0;
				maxX = x1;
				minZ = z0;
				maxZ = z1;
				topHeight = Math.min(1.0, maxY);
			} else {
				minX = Math.min(minX, x0);
				maxX = Math.max(maxX, x1);
				minZ = Math.min(minZ, z0);
				maxZ = Math.max(maxZ, z1);
			}
			bottom = y;
		}

		/** The block under the run is solid: the run stands on it. */
		void solid() {
			if (inRun) {
				double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
				double hx = Math.max(MIN_SIDE, maxX - minX) / 2, hz = Math.max(MIN_SIDE, maxZ - minZ) / 2;
				boxes.add((float) (x + cx - hx));
				boxes.add(bottom);
				boxes.add((float) (z + cz - hz));
				boxes.add((float) (x + cx + hx));
				boxes.add((float) (top + topHeight));
				boxes.add((float) (z + cz + hz));
			}
			inRun = false;
		}

		/** Air or water under the run: it hangs. */
		void open() {
			inRun = false;
		}
	}

	/** A chunk was read: its posts (boxes in blocks, flat), to send when a car is near. */
	static void store(long chunk, float[] boxes) {
		float[] old = boxes.length > 0 ? CHUNKS.put(chunk, boxes) : CHUNKS.remove(chunk);
		if (!java.util.Arrays.equals(old == null ? new float[0] : old, boxes)) {
			CHANGED.add(chunk);
		}
	}

	/** A car for update(): Minecraft position and speed in blocks a second. */
	record Car(dev.bngmc.bridge.coords.V3 pos, double speed) {
	}

	/**
	 * Each refresh: chunks reaching within a car's radius (POST_RADIUS + its travel) get their posts in
	 * BeamNG (again when read anew), chunks no car is near lose them.
	 */
	static void update(BngLink link, CrossoverCoords.Region region, java.util.List<Car> cars) {
		LongOpenHashSet want = new LongOpenHashSet();
		for (var car : cars) {
			var c = car.pos();
			double radius = Math.min(POST_MAX_RADIUS, POST_RADIUS + car.speed() * POST_LEAD_S) + 11.4;   // to a chunk's far corner
			int r = (int) Math.ceil(radius / 16);
			int ccx = (int) Math.floor(c.x()) >> 4, ccz = (int) Math.floor(c.z()) >> 4;
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					double ox = ((ccx + dx) << 4) + 8 - c.x(), oz = ((ccz + dz) << 4) + 8 - c.z();
					if (ox * ox + oz * oz <= radius * radius) {
						want.add(ChunkPos.asLong(ccx + dx, ccz + dz));
					}
				}
			}
		}
		int sends = 0;
		for (LongIterator it = want.iterator(); it.hasNext() && sends < SENDS_PER_REFRESH; ) {
			long k = it.nextLong();
			boolean has = CHUNKS.containsKey(k);
			if ((has && !SENT.contains(k)) || (CHANGED.contains(k) && (has || SENT.contains(k)))) {
				if (send(link, region, k, has ? CHUNKS.get(k) : new float[0])) {
					sends++;
					CHANGED.remove(k);
					if (has) {
						SENT.add(k);
					} else {
						SENT.remove(k);
					}
				}
			}
		}
		for (LongIterator it = SENT.iterator(); it.hasNext() && sends < SENDS_PER_REFRESH; ) {
			long k = it.nextLong();
			if (!want.contains(k) && send(link, region, k, new float[0])) {
				sends++;
				it.remove();
			}
		}
	}

	private static boolean send(BngLink link, CrossoverCoords.Region region, long chunk, float[] b) {
		JsonObject msg = new JsonObject();
		msg.addProperty("cx", ChunkPos.getX(chunk));
		msg.addProperty("cz", ChunkPos.getZ(chunk));
		msg.add("boxes", canonical(b, region));
		return link.send(Protocol.POST_CHUNK, msg);
	}

	/** Boxes in blocks -> canonical metres (x east, y north, z up), min corner first, to the millimetre. */
	static JsonArray canonical(float[] b, CrossoverCoords.Region region) {
		JsonArray out = new JsonArray();
		double s = region.scale(), ox = region.mcOriginX(), oy = region.mcOriginY(), oz = region.mcOriginZ();
		for (int i = 0; i + 5 < b.length; i += 6) {
			double[] v = {(b[i] - ox) / s, -(b[i + 5] - oz) / s, (b[i + 1] - oy) / s, (b[i + 3] - ox) / s, -(b[i + 2] - oz) / s, (b[i + 4] - oy) / s};
			for (double d : v) {
				out.add(Math.round(d * 1000) / 1000.0);
			}
		}
		return out;
	}

	/** A new terrain in BeamNG: its posts start over. */
	static void reset(BngLink link) {
		clear();
		link.send(Protocol.POST_RESET, new JsonObject());
	}

	static void clear() {
		CHUNKS.clear();
		SENT.clear();
		CHANGED.clear();
	}

	/** For the status HUD. */
	static String describe() {
		int boxes = 0;
		for (LongIterator it = SENT.iterator(); it.hasNext(); ) {
			float[] b = CHUNKS.get(it.nextLong());
			boxes += b == null ? 0 : b.length / 6;
		}
		return boxes + " posts in BeamNG from " + SENT.size() + " chunks (" + CHUNKS.size() + " chunks with posts read)";
	}
}
