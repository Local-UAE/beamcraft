package dev.bngmc.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * The math of TerrainSync, without Minecraft: Minecraft's surface heights (in blocks) turned into
 * the surface a BeamNG car drives. One-block steps are 0.71 m walls at the host scale, higher than
 * a wheel climbs, so for the smooth ground (BridgeSettings.Ground.SMOOTH) the heights are blurred
 * into slopes (with the default radius 2 a step spreads over 5 blocks, about 11 degrees). Only
 * steps a block high (two on a natural hillside) are blurred: a pit, a cliff, a wall someone built
 * keeps its edge and leaves the ground beside it alone, so a dug hole stays a hole. Radius 0 is the
 * real blocks: every step stays a step. Heights are n x n, row by row (row = z, column = x). Pure
 * Java, unit-tested.
 */
public final class TerrainHeights {
	private TerrainHeights() {
	}

	/** Blocks each way the blur reaches by default. */
	static final int SMOOTH_RADIUS = 2;
	/** Blur moving a column more than this many blocks: it keeps its own height. */
	static final float KEEP = 1.0F;
	/** No height known yet (a chunk Minecraft hasn't loaded). */
	static final float UNKNOWN = Float.NaN;

	/** The highest step the blur joins into a slope: one block, what Minecraft walks up (a kerb, a stair, a terrace). */
	static final float STEP = 1.0F + 1e-3F;
	/** ...and between two natural columns, a hillside, two. */
	static final float NATURAL_STEP = 2.0F + 1e-3F;

	/**
	 * The driven surface for the samples in rows r0..r1, columns c0..c1 (inclusive), from all raw
	 * heights: each column the mean of the columns around it no more than a STEP above or below it
	 * (NATURAL_STEP when both are natural ground, natural[i]; null for none). Taller neighbours, a
	 * wall, a barrier, a building, don't count: they used to lift the road beside them, two blocks
	 * out, by up to a block (KEEP), a 0.7 m ridge along every barrier of Newisle's roads that cars hit
	 * (2026-10-05).
	 */
	static void smooth(float[] raw, int n, boolean[] natural, float[] out, int r0, int c0, int r1, int c1, int radius) {
		for (int r = Math.max(0, r0); r <= Math.min(n - 1, r1); r++) {
			for (int c = Math.max(0, c0); c <= Math.min(n - 1, c1); c++) {
				int i = r * n + c;
				float own = raw[i];
				boolean ownNatural = natural != null && natural[i];
				double sum = 0;
				int count = 0;
				for (int dr = -radius; dr <= radius; dr++) {
					for (int dc = -radius; dc <= radius; dc++) {
						int j = Math.max(0, Math.min(n - 1, r + dr)) * n + Math.max(0, Math.min(n - 1, c + dc));
						float v = raw[j];
						if (Float.isNaN(v) || (!Float.isNaN(own) && Math.abs(v - own) > (ownNatural && natural[j] ? NATURAL_STEP : STEP))) {
							continue;
						}
						sum += v;
						count++;
					}
				}
				if (Float.isNaN(own)) {
					out[i] = count > 0 ? (float) (sum / count) : UNKNOWN;
					continue;
				}
				float blurred = (float) (sum / count);
				out[i] = Math.abs(blurred - own) > KEEP ? own : blurred;
			}
		}
	}

	/** All of it. */
	static float[] smooth(float[] raw, int n, boolean[] natural, int radius) {
		float[] out = new float[raw.length];
		smooth(raw, n, natural, out, 0, 0, n - 1, n - 1, radius);
		return out;
	}

	/** All of it, nothing natural. */
	static float[] smooth(float[] raw, int n, int radius) {
		return smooth(raw, n, null, radius);
	}

	/** All of it, with the default radius. */
	static float[] smooth(float[] raw, int n) {
		return smooth(raw, n, SMOOTH_RADIUS);
	}

	/**
	 * The height at terrain sample (row, col) when each of the area x area blocks is perBlock x
	 * perBlock samples: the block's own height, so a block's top is flat and the step to the next
	 * block happens between two samples, 1/perBlock of a block apart.
	 */
	static float sample(float[] heights, int area, int perBlock, int row, int col) {
		return heights[(row / perBlock) * area + col / perBlock];
	}

	/**
	 * Steps of at most this many blocks (a slab, a kerb, a stair) are ramps in real blocks; a full
	 * block (0.71 m) stays a wall. As a sharp edge a half block is 0.36 m, about a sports car's wheel
	 * radius: the M3 couldn't climb a kerb from a crawl and needed 6 m/s (house map, 2026-10-03).
	 */
	static final float KERB = 0.5F + 1e-3F;

	/**
	 * As {@link #sample}, with kerb-high steps as ramps: the sample next to a block's edge leans a
	 * third of the way toward a neighbour at most KERB higher or lower, so the step rises between the
	 * outer samples of the two blocks, 1.5 blocks apart (about 19 degrees for a half block). One
	 * sample per block (smooth ground) is unchanged.
	 */
	static float sampleKerbs(float[] heights, int area, int perBlock, int row, int col) {
		int br = row / perBlock, bc = col / perBlock;
		float own = heights[br * area + bc];
		if (perBlock < 2) {
			return own;
		}
		int sr = row % perBlock, sc = col % perBlock;
		float h = own;
		if (sc == 0 && bc > 0) {
			h += lean(own, heights[br * area + bc - 1]);
		} else if (sc == perBlock - 1 && bc < area - 1) {
			h += lean(own, heights[br * area + bc + 1]);
		}
		if (sr == 0 && br > 0) {
			h += lean(own, heights[(br - 1) * area + bc]);
		} else if (sr == perBlock - 1 && br < area - 1) {
			h += lean(own, heights[(br + 1) * area + bc]);
		}
		return h;
	}

	private static float lean(float own, float next) {
		float d = next - own;
		return d != 0 && Math.abs(d) <= KERB ? d / 3 : 0;   // NaN fails the comparison: no lean
	}

	/** Samples one terrain_cells message carries at most: 512 heights stay well under BeamNG's 8 KB. */
	static final int MAX_CELLS = 512;

	/** A rectangle of terrain samples for BeamNG: its corner on BeamNG's grid, w x h values, grid row by grid row. */
	record Cells(int x, int y, int w, int h, double[] z) {
	}

	/**
	 * The samples of block rows br0..br1, columns bc0..bc1 (inclusive) as terrain_cells patches of
	 * at most max samples each. BeamNG's grid rows go north while image rows go south, so sample
	 * row s is grid row n - 1 - s (n = area * perBlock), and each patch lists its southernmost row
	 * first. value turns a height in blocks into what BeamNG gets.
	 */
	static List<Cells> cells(float[] heights, int area, int perBlock, int br0, int bc0, int br1, int bc1, int max, DoubleUnaryOperator value) {
		int n = area * perBlock;
		int s0 = br0 * perBlock, s1 = br1 * perBlock + perBlock - 1, c0 = bc0 * perBlock, c1 = bc1 * perBlock + perBlock - 1;
		int w = c1 - c0 + 1, rowsEach = Math.max(1, max / w);
		List<Cells> out = new ArrayList<>();
		for (int top = s0; top <= s1; top += rowsEach) {
			int bottom = Math.min(s1, top + rowsEach - 1), h = bottom - top + 1;
			double[] z = new double[w * h];
			for (int k = 0; k < h; k++) {
				for (int c = 0; c < w; c++) {
					z[k * w + c] = value.applyAsDouble(sampleKerbs(heights, area, perBlock, bottom - k, c0 + c));
				}
			}
			out.add(new Cells(c0, n - 1 - bottom, w, h, z));
		}
		return out;
	}

	/** One block of a column, top down (TerrainSync.surface): what a car meets there. */
	static final byte AIR = 0, FLUID = 1, BUILT = 2, NATURAL = 3;
	/**
	 * Natural and loose (dirt, grass, sand, gravel): what a street is laid on and a subway buried
	 * in. NATURAL is the rest, rock and clay, which builders also use for walls and arches.
	 */
	static final byte SOIL = 4;

	static boolean natural(byte k) {
		return k == NATURAL || k == SOIL;
	}

	static boolean solid(byte k) {
		return k == BUILT || natural(k);
	}
	/** Open blocks under a built run that make it a roof a car drives under (a garage door is 3 or 4). */
	static final int MIN_HEADROOM = 2;
	/**
	 * Natural blocks in a row a roof may have: a lawn over a garage or a basement is a roof when a
	 * built ceiling follows within these and there is room under it. More is the ground, whatever is
	 * built on it or under it: Newisle's streets are concrete on a dozen blocks of dirt over the
	 * subway, and as roofs BeamNG got the tunnel floor 19 blocks down (2026-10-05). A built run of any
	 * depth with room under it is driven under: an 8-block sign gantry over the street stood in
	 * BeamNG as a pillar across the lane (Jas crashed into nothing, the same day).
	 */
	static final int ROOF_SOIL = 3;
	/**
	 * Natural blocks in a row a run may have and still be a roof: a hill over a road tunnel (Newisle's
	 * park, 2026-10-05: the tunnel mouth stood in BeamNG as a wall), an arch with ore in it. More is the
	 * ground. Soil right under a built block (a street) is the ground from ROOF_SOIL on.
	 */
	static final int TUNNEL_COVER = 16;

	/**
	 * Whether the block a column's surface (from columnSurface) is the top of is natural ground. The
	 * surface is top - i + blockTop[i] for that block's index i, blockTop in (0, 1].
	 */
	static boolean naturalSurface(int top, byte[] kinds, int n, float surface) {
		int i = (int) Math.floor(top + 1 - surface + 1e-4);
		return i >= 0 && i < n && natural(kinds[i]);
	}

	/** Decks kept per column: a monorail over an elevated road over a street is three levels. */
	static final int MAX_DECKS = 3;
	/** Levels a column offers a car: its surface, then its decks. */
	static final int LEVELS = 1 + MAX_DECKS;
	/** The highest step between two neighbouring columns' levels a car drives up or down: a block (a ramp, a stair, a kerb). */
	static final float CLIMB = 1.25F;
	/** The levels a car stands on: from this far under its position (BeamNG's sits half a block or so over the road)... */
	static final float STAND_BELOW = 1.5F;
	/** ...to this far over it. */
	static final float STAND_ABOVE = 0.5F;

	private static int[] reachDist = new int[0], reachQueue = new int[0];   // reach()'s, reused (server thread)

	/**
	 * Which level each column of a size x size window around a car gets (levels: LEVELS per column,
	 * its surface then its decks, NaN for none; the car over the middle column at height carY): the
	 * one the car can drive to soonest. From the levels the car stands on (in the 3 x 3 columns under
	 * it), the ground is walked column to column, up or down at most CLIMB at a step, within a disc of
	 * radius size / 2; each column takes the level reached in the fewest steps, of two reached alike
	 * the one nearer the car's height. Returns per column the level's index (0 the surface, 1 + k
	 * deck k), -1 where nothing was reached (a car in the air reaches nothing). So the street goes on
	 * under an elevated road and the road goes on over it, a ramp is followed up from its foot, and
	 * a lamp's arm, an awning or a bridge no road leads to is never put in a car's way. (The level
	 * nearest the car's height carried along its nose's pitch put such things up across the road
	 * whenever the car pitched, 2026-10-05.)
	 */
	static byte[] reach(float[] levels, int size, float carY) {
		byte[] out = new byte[size * size];
		java.util.Arrays.fill(out, (byte) -1);
		int nodes = size * size * LEVELS;
		if (reachDist.length < nodes) {   // reused: a car's window is up to 257 x 257 columns, four times a second
			reachDist = new int[nodes];
			reachQueue = new int[nodes];
		}
		int[] dist = reachDist, queue = reachQueue;
		java.util.Arrays.fill(dist, 0, nodes, Integer.MAX_VALUE);
		int head = 0, tail = 0, c = size / 2;
		// the column under the car's middle, else (it is over a kerb, a gap) the ones around it: so at a
		// ramp's foot the ramp is the way on, not the street beside it
		for (int ring = 0; ring <= 1 && tail == 0; ring++) {
			for (int dr = -ring; dr <= ring; dr++) {
				for (int dc = -ring; dc <= ring; dc++) {
					int col = (c + dr) * size + c + dc;
					for (int k = 0; k < LEVELS; k++) {
						float v = levels[col * LEVELS + k];
						if (!Float.isNaN(v) && v >= carY - STAND_BELOW && v <= carY + STAND_ABOVE && dist[col * LEVELS + k] != 0) {
							dist[col * LEVELS + k] = 0;
							queue[tail++] = col * LEVELS + k;
						}
					}
				}
			}
		}
		long r2 = (long) c * c;
		int[] dcs = {1, -1, 0, 0}, drs = {0, 0, 1, -1};
		while (head < tail) {
			int node = queue[head++];
			int col = node / LEVELS, ic = col % size, ir = col / size;
			float v = levels[node];
			for (int d = 0; d < 4; d++) {
				int nc = ic + dcs[d], nr = ir + drs[d];
				if (nc < 0 || nr < 0 || nc >= size || nr >= size || (long) (nc - c) * (nc - c) + (long) (nr - c) * (nr - c) > r2) {
					continue;
				}
				int base = (nr * size + nc) * LEVELS;
				for (int k = 0; k < LEVELS; k++) {
					float w = levels[base + k];
					if (!Float.isNaN(w) && Math.abs(w - v) <= CLIMB && dist[base + k] == Integer.MAX_VALUE) {
						dist[base + k] = dist[node] + 1;
						queue[tail++] = base + k;
					}
				}
			}
		}
		for (int col = 0; col < size * size; col++) {
			int pick = -1;
			for (int k = 0; k < LEVELS; k++) {
				int n = col * LEVELS + k;
				if (dist[n] == Integer.MAX_VALUE) {
					continue;
				}
				int p = col * LEVELS + pick;
				if (pick < 0 || dist[n] < dist[p] || (dist[n] == dist[p] && Math.abs(levels[n] - carY) < Math.abs(levels[p] - carY))) {
					pick = k;
				}
			}
			out[col] = (byte) pick;
		}
		return out;
	}

	/**
	 * The decks over a column's surface, top down, into out: the top of each solid run at least
	 * MIN_HEADROOM blocks over the surface with room for a car above it (the first run has the sky;
	 * a lower one needs MIN_HEADROOM open blocks over it), at most MAX_DECKS. An elevated road, a
	 * bridge, a roof the surface rule looked through: a car up there needs it instead of the ground
	 * (TerrainSync, decks near cars). Returns how many.
	 */
	static int decks(int top, byte[] kinds, float[] blockTop, int n, float surface, float[] out) {
		int count = 0, open = Integer.MAX_VALUE;   // open blocks over the current position
		for (int i = 0; i < n && count < MAX_DECKS; i++) {
			if (solid(kinds[i])) {
				if (open >= MIN_HEADROOM) {   // the top of a run with room over it
					float t = top - i + blockTop[i];
					if (t < surface + MIN_HEADROOM) {
						break;   // down at the surface: no more decks
					}
					out[count++] = t;
				}
				open = 0;
			} else {
				open = open == Integer.MAX_VALUE ? open : open + 1;
				if (kinds[i] == FLUID) {
					break;
				}
			}
		}
		return count;
	}

	/**
	 * The ground of one column for a heightfield, which has no overhangs: kinds[i] (i < n) is the block at
	 * y = top - i (AIR for anything a car passes, FLUID, BUILT for solid blocks people place,
	 * NATURAL for generated ground), blockTop[i] its collision top (0..1]. The top of the first
	 * solid run from above, except, when openUnder, a run with at least MIN_HEADROOM blocks of air
	 * under it whose lowest block is built (a roof, a lintel, an upper floor, a gantry, a lawn on a
	 * garage, a hill over a tunnel's lining) or natural over something built (a stone monorail over
	 * the highway, an arch): the car drives under it. Such a run is still the ground with more than
	 * TUNNEL_COVER natural blocks in a row, or more than ROOF_SOIL right under a built block (a street
	 * on soil, over the subway or not). Walls stand on the ground with no air under them and stay
	 * walls; a bridge over water stays a bridge; natural overhangs over open ground (cliffs, caves)
	 * and ground over a void (old superflat maps) stay as they were.
	 */
	/**
	 * A built floor with room for a car over it (a road) from kinds[from] down, before more than
	 * ROOF_SOIL natural blocks in a row (the ground) or water. Natural blocks over one are part of a
	 * build: a stone monorail over the highway, a clay canopy over the street (2026-10-05).
	 */
	static boolean floorBelow(byte[] kinds, int from, int n) {
		int air = MIN_HEADROOM, soil = 0;   // the gap over kinds[from] is room enough
		for (int j = from; j < n; j++) {
			byte k = kinds[j];
			if (k == AIR) {
				air++;
				soil = 0;
				continue;
			}
			if (k == FLUID || (natural(k) && ++soil > ROOF_SOIL)) {
				return false;
			}
			if (k == BUILT && air >= MIN_HEADROOM) {
				return true;
			}
			if (k == BUILT) {
				soil = 0;
			}
			air = 0;
		}
		return false;
	}

	static float columnSurface(int top, byte[] kinds, float[] blockTop, int n, boolean openUnder) {
		int runStart = -1;        // index of the current solid run's top block
		int soil = 0;             // natural blocks in a row
		int loose = 0;            // SOIL blocks in a row...
		boolean paved = false;    // ...right under a built block (no air between): soil under a street
		boolean street = false;   // the run has more of that soil than a roof garden's: a street on the ground
		for (int i = 0; i < n; i++) {
			byte k = kinds[i];
			if (solid(k)) {
				if (runStart < 0) {
					runStart = i;
					street = false;
				}
				soil = natural(k) ? soil + 1 : 0;
				if (k == SOIL) {
					paved = loose == 0 ? i > 0 && kinds[i - 1] == BUILT : paved;
					loose++;
					street |= paved && loose > ROOF_SOIL;
				} else {
					loose = 0;
				}
				if (!openUnder || soil > TUNNEL_COVER) {
					return top - runStart + blockTop[runStart];   // the ground, or what is built on it
				}
				continue;
			}
			soil = 0;
			loose = 0;
			if (runStart < 0) {
				continue;
			}
			if (street) {
				// a street on soil is the ground whatever is under it: Newisle's streets over the subway
				return top - runStart + blockTop[runStart];
			}
			int open = 0;
			while (i + open < n && kinds[i + open] == AIR) {
				open++;
			}
			if (i + open < n && kinds[i + open] == FLUID) {
				return top - runStart + blockTop[runStart];   // a bridge over water
			}
			if (open >= MIN_HEADROOM) {
				if (natural(kinds[i - 1]) && !floorBelow(kinds, i + open, n)) {
					// the run ends in ground, not a ceiling, over the ground or nothing: a natural overhang,
					// a floating island, an old superflat map's four layers over empty space
					return top - runStart + blockTop[runStart];
				}
				runStart = -1;   // a roof: the ground is further down
			}   // else a gap no car fits: part of the same run
			i += open - 1;
		}
		return runStart >= 0 ? top - runStart + blockTop[runStart] : top + 1;
	}

	/**
	 * Where a car can stand nearest to (wantCol, wantRow) in a w x h window of columns: the centre of a
	 * (2 half + 1)^2 patch whose columns are all open (nothing over the ground: no roof, tree or water)
	 * with ground heights within maxStep of each other. ground and open are row by row (row = z).
	 * Returns {col, row}, or null when no patch in the window qualifies.
	 */
	static int[] nearestOpen(float[] ground, boolean[] open, int w, int h, int half, float maxStep, int wantCol, int wantRow) {
		int[] best = null;
		long bestD = Long.MAX_VALUE;
		for (int r = half; r < h - half; r++) {
			for (int c = half; c < w - half; c++) {
				long d = (long) (c - wantCol) * (c - wantCol) + (long) (r - wantRow) * (r - wantRow);
				if (d >= bestD || !patchOpen(ground, open, w, half, maxStep, c, r)) {
					continue;
				}
				bestD = d;
				best = new int[] {c, r};
			}
		}
		return best;
	}

	private static boolean patchOpen(float[] ground, boolean[] open, int w, int half, float maxStep, int c, int r) {
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		for (int rr = r - half; rr <= r + half; rr++) {
			for (int cc = c - half; cc <= c + half; cc++) {
				int i = rr * w + cc;
				if (!open[i] || Float.isNaN(ground[i])) {
					return false;
				}
				lo = Math.min(lo, ground[i]);
				hi = Math.max(hi, ground[i]);
				if (hi - lo > maxStep) {
					return false;
				}
			}
		}
		return true;
	}

	/** The median of the known heights (what a not yet loaded chunk is taken to be), or fallback. */
	static float median(float[] h, float fallback) {
		float[] known = new float[h.length];
		int k = 0;
		for (float v : h) {
			if (!Float.isNaN(v)) {
				known[k++] = v;
			}
		}
		if (k == 0) {
			return fallback;
		}
		java.util.Arrays.sort(known, 0, k);
		return known[k / 2];
	}
}
