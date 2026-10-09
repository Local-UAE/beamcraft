package dev.bngmc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerrainHeightsTest {
	private static final int N = 16;

	private static float[] flat(float h) {
		float[] a = new float[N * N];
		java.util.Arrays.fill(a, h);
		return a;
	}

	@Test
	void flatGroundStaysFlat() {
		for (float v : TerrainHeights.smooth(flat(70), N)) {
			assertEquals(70F, v, 1e-5F);
		}
	}

	@Test
	void aOneBlockStepBecomesASlopeACarCanClimb() {
		float[] raw = flat(70);
		for (int r = 0; r < N; r++) {
			for (int c = 8; c < N; c++) {
				raw[r * N + c] = 71;   // one block up from column 8 on
			}
		}
		float[] s = TerrainHeights.smooth(raw, N);
		int row = 8 * N;
		float steepest = 0;
		for (int c = 1; c < N; c++) {
			steepest = Math.max(steepest, s[row + c] - s[row + c - 1]);
		}
		// one block spread over the 5-block blur: at most 0.2 blocks rise per block (11 degrees)
		assertTrue(steepest <= 0.2F + 1e-5F, "steepest rise " + steepest);
		assertEquals(70F, s[row + 2], 1e-5F);
		assertEquals(71F, s[row + 13], 1e-5F);
	}

	@Test
	void aDugHoleStaysAHole() {
		float[] raw = flat(70);
		raw[8 * N + 8] = 68;   // two blocks deep, one wide
		float[] s = TerrainHeights.smooth(raw, N);
		assertEquals(68F, s[8 * N + 8], 1e-5F);
		assertTrue(s[8 * N + 7] > 69.8F, "the ground beside the hole hardly moves: " + s[8 * N + 7]);
	}

	@Test
	void aTallWallKeepsItsHeight() {
		float[] raw = flat(70);
		for (int r = 0; r < N; r++) {
			raw[r * N + 8] = 74;   // a 4 high wall, one thick
		}
		float[] s = TerrainHeights.smooth(raw, N);
		assertEquals(74F, s[8 * N + 8], 1e-5F);
	}

	@Test
	void aBarrierBesideARoadLeavesNoRidgeOnTheRoad() {
		float[] raw = flat(71);
		for (int c = 0; c < N; c++) {
			raw[8 * N + c] = 76;   // a highway barrier, five blocks over the road, one thick
		}
		float[] s = TerrainHeights.smooth(raw, N, null, TerrainHeights.SMOOTH_RADIUS);
		for (int r = 4; r < 8; r++) {
			// the blur used to lift the road two blocks out by a whole block (0.7 m): a ridge across
			// Newisle's road stub that the car hit (2026-10-05)
			assertEquals(71F, s[r * N + 5], 0F, "row " + r);
		}
		assertEquals(76F, s[8 * N + 5], 0F);
	}

	@Test
	void aTwoBlockNaturalStepStillBecomesASlopeButABuiltOneStaysAWall() {
		float[] raw = stepAtColumn8(70, 72);
		boolean[] natural = new boolean[N * N];
		java.util.Arrays.fill(natural, true);
		float[] hill = TerrainHeights.smooth(raw, N, natural, TerrainHeights.SMOOTH_RADIUS);
		float steepest = 0;
		for (int c = 1; c < N; c++) {
			steepest = Math.max(steepest, hill[8 * N + c] - hill[8 * N + c - 1]);
		}
		assertTrue(steepest <= 0.41F, "steepest rise on the hill " + steepest);
		float[] wall = TerrainHeights.smooth(raw, N, new boolean[N * N], TerrainHeights.SMOOTH_RADIUS);
		assertEquals(70F, wall[8 * N + 7], 0F);
		assertEquals(72F, wall[8 * N + 8], 0F);
	}

	private static float[] stepAtColumn8(float low, float high) {
		float[] raw = flat(low);
		for (int r = 0; r < N; r++) {
			for (int c = 8; c < N; c++) {
				raw[r * N + c] = high;
			}
		}
		return raw;
	}

	@Test
	void realBlocksKeepEveryOneBlockStep() {
		float[] s = TerrainHeights.smooth(stepAtColumn8(70, 71), N, 0);
		assertEquals(70F, s[8 * N + 7], 0F);
		assertEquals(71F, s[8 * N + 8], 0F);
	}

	@Test
	void aLongerSlopeIsGentler() {
		float[] raw = stepAtColumn8(70, 71);
		float steepest2 = 0, steepest4 = 0;
		float[] s2 = TerrainHeights.smooth(raw, N, 2), s4 = TerrainHeights.smooth(raw, N, 4);
		for (int c = 1; c < N; c++) {
			steepest2 = Math.max(steepest2, s2[8 * N + c] - s2[8 * N + c - 1]);
			steepest4 = Math.max(steepest4, s4[8 * N + c] - s4[8 * N + c - 1]);
		}
		assertEquals(1F / 5, steepest2, 1e-5F);   // spread over 5 blocks
		assertEquals(1F / 9, steepest4, 1e-5F);   // over 9
	}

	@Test
	void twoSamplesPerBlockMakeAFlatTopAndAStepBetweenTwoSamples() {
		float[] h = {70, 71, 72, 73};   // 2 x 2 blocks
		// samples 0-1 are block column 0, 2-3 block column 1; rows likewise
		assertEquals(70F, TerrainHeights.sample(h, 2, 2, 0, 0), 0F);
		assertEquals(70F, TerrainHeights.sample(h, 2, 2, 1, 1), 0F);
		assertEquals(71F, TerrainHeights.sample(h, 2, 2, 1, 2), 0F);
		assertEquals(73F, TerrainHeights.sample(h, 2, 2, 3, 3), 0F);
	}

	@Test
	void aChunkOfRealBlocksGoesToBeamngInPatchesSmallEnoughToSend() {
		float[] h = new float[64 * 64];
		for (int i = 0; i < h.length; i++) {
			h[i] = i / 64;   // height = block row
		}
		// one chunk, block rows and columns 16..31, at 2 samples per block: 32 x 32 = 1024 samples
		var cells = TerrainHeights.cells(h, 64, 2, 16, 16, 31, 31, TerrainHeights.MAX_CELLS, v -> v);
		assertEquals(2, cells.size());
		int total = 0;
		for (var c : cells) {
			assertEquals(32, c.x());
			assertEquals(32, c.w());
			assertTrue(c.w() * c.h() <= TerrainHeights.MAX_CELLS);
			assertEquals(c.w() * c.h(), c.z().length);
			total += c.z().length;
		}
		assertEquals(1024, total);
		// sample rows 32..63 of 128: grid rows go north, so the southern patch (sample rows 48..63)
		// starts at grid row 128 - 1 - 63 = 64 and lists sample row 63 (block row 31) first
		var south = cells.get(1);
		assertEquals(64, south.y());
		assertEquals(16, south.h());
		assertEquals(31, south.z()[0], 0);
		assertEquals(24, south.z()[south.z().length - 1], 0);   // its last row: sample row 48, block row 24
		assertEquals(80, cells.get(0).y());
	}

	@Test
	void smoothPatchesStayOneMessage() {
		float[] h = flat(70);
		var cells = TerrainHeights.cells(h, N, 1, 0, 0, N - 1, N - 1, TerrainHeights.MAX_CELLS, v -> v);
		assertEquals(1, cells.size());
		assertEquals(0, cells.get(0).y());
		assertEquals(N * N, cells.get(0).z().length);
	}

	private static final byte A = TerrainHeights.AIR, W = TerrainHeights.FLUID, B = TerrainHeights.BUILT, G = TerrainHeights.NATURAL,
		D = TerrainHeights.SOIL;   // dirt: loose natural ground

	private static float column(boolean open, byte... kinds) {
		float[] tops = new float[kinds.length];
		java.util.Arrays.fill(tops, 1F);
		return TerrainHeights.columnSurface(72, kinds, tops, kinds.length, open);
	}

	@Test
	void aGarageIsDrivenIntoAndItsRoofIsNotGround() {
		// roof at 72, a 3 high opening, the concrete floor at 68 on the dirt
		assertEquals(69F, column(true, B, A, A, A, B, G), 0F);
		assertEquals(73F, column(false, B, A, A, A, B, G), 0F);   // "Drive into buildings" off: a solid lump
	}

	@Test
	void wallsBridgesOverWaterAndNaturalGroundStayWhatTheyAre() {
		assertEquals(73F, column(true, B, B, B, B, G), 0F);          // a wall standing on the ground
		assertEquals(73F, column(true, B, A, W, W, G), 0F);          // a bridge over a river
		assertEquals(73F, column(true, G, A, A, A, G), 0F);          // a natural overhang over a cave
		assertEquals(73F, column(true, B, A, B, G), 0F);             // a one-block gap: no car fits, one lump
		assertEquals(70F, column(true, A, A, A, G), 0F);             // open ground
	}

	@Test
	void aRoofWithANaturalLookingBlockInItIsStillARoof() {
		// a quartz roof, a stone band, a quartz lintel, a 3 high opening, a slab floor on grass
		assertEquals(67F, column(true, B, G, B, A, A, A, B, G, G, G, G, G, G, G, G), 0F);
		// and so is a built run of any depth with room under it: an 8-block sign gantry over a street
		assertEquals(62F, column(true, B, B, B, B, B, B, B, B, A, A, A, G), 0F);
		// a deep natural run with a cave under it is still the ground
		assertEquals(73F, column(true, G, G, G, G, G, G, G, G, A, A, A, G), 0F);
	}

	@Test
	void aStoneMonorailOverTheHighwayIsDrivenUnder() {
		// Newisle: a stone block of the monorail at 72, ten of air, the highway's concrete at 61 on
		// pillars, four of air, the grass: the stone was taken for a floating island, ground, and stood
		// as a twelve-block wall across the highway (2026-10-05). It is a deck over a deck.
		byte[] kinds = {G, A, A, A, A, A, A, A, A, A, A, B, A, A, A, A, G};
		assertEquals(57F, column(true, kinds), 0F);
		float[] tops = new float[kinds.length];
		java.util.Arrays.fill(tops, 1F);
		float[] out = new float[TerrainHeights.MAX_DECKS];
		assertEquals(2, TerrainHeights.decks(72, kinds, tops, kinds.length, 57F, out));
		assertEquals(73F, out[0], 0F);
		assertEquals(62F, out[1], 0F);
		// a natural overhang over a natural cave floor is still the ground
		assertEquals(73F, column(true, G, A, A, A, G), 0F);
	}

	@Test
	void aClayCanopyOverTheRoadIsDrivenUnder() {
		// Newisle: a slab on clay, three of air, more clay, four of air, the road: clay is natural, and
		// natural over natural over a gap counted as a cave's roof, the ground (2026-10-05)
		assertEquals(63F, column(true, B, G, A, A, A, G, A, A, A, A, B, G), 0F);
		// a cliff over a cave with a natural floor is still the ground
		assertEquals(73F, column(true, G, G, A, A, A, G, A, A, A, G, G, G, G, G), 0F);
	}

	@Test
	void aPocketInATunnelsHillDoesNotCloseTheTunnel() {
		// grass, dirt, a one-block pocket, grass, dirt, the lining, six of air, the road
		assertEquals(61F, column(true, G, G, A, G, G, B, A, A, A, A, A, A, B, G), 0F);
	}

	@Test
	void anArchOverTheRoadIsDrivenUnderEvenWithOreInIt() {
		// Newisle: a stone-brick cap, a gap, gold ore and stone (natural blocks, used as a build), six
		// of air, the road: the cap made the ore count as a street's soil, and it stood as a wall
		assertEquals(58F, column(true, B, B, B, A, G, G, G, G, G, A, A, A, A, A, A, B, G), 0F);
		// a street paved on soil over a subway with no lining is still the ground
		assertEquals(73F, column(true, B, D, D, D, D, D, D, A, A, A, B, G), 0F);
		// an arch's cap right on five ore and stone blocks is no street: they are rock, not soil
		assertEquals(62F, column(true, B, G, G, G, G, G, A, A, A, A, A, B, G), 0F);
	}

	@Test
	void aClayBuildingOverARoadIsDrivenUnder() {
		// Newisle: a road through a building of clay walls on a brick roof, four blocks over the road
		assertEquals(62F, column(true, B, G, G, G, G, G, G, A, A, A, A, B, G), 0F);
	}

	@Test
	void aRoadTunnelThroughAHillIsDrivenInto() {
		// Newisle's park: grass, four dirt, five stone, the tunnel's concrete lining at 62, six of air,
		// the road at 55: the hill counted as ground and the tunnel mouth stood as a wall (2026-10-05)
		assertEquals(56F, column(true, G, G, G, G, G, G, G, G, G, G, B, A, A, A, A, A, A, B, G), 0F);
		// a hill with no tunnel, and one over a cave, are still the ground
		assertEquals(73F, column(true, G, G, G, G, G, G, G, G, G, G, G, G), 0F);
		assertEquals(73F, column(true, G, G, G, G, G, G, A, A, A, G), 0F);
		// deeper than TUNNEL_COVER the hill is the ground whatever is under it
		byte[] deep = new byte[TerrainHeights.TUNNEL_COVER + 6];
		java.util.Arrays.fill(deep, G);
		deep[TerrainHeights.TUNNEL_COVER + 1] = B;
		deep[TerrainHeights.TUNNEL_COVER + 2] = A;
		deep[TerrainHeights.TUNNEL_COVER + 3] = A;
		deep[TerrainHeights.TUNNEL_COVER + 4] = A;
		deep[TerrainHeights.TUNNEL_COVER + 5] = B;
		assertEquals(73F, column(true, deep), 0F);
	}

	@Test
	void aStreetPavedOnSoilOverASubwayIsTheGround() {
		// Newisle: black concrete on 12 dirt, the tunnel's stone-brick ceiling, 3 high, its floor
		assertEquals(73F, column(true, B, D, D, D, D, D, D, D, D, D, D, D, D, B, B, A, A, A, B), 0F);
		// but a road on three blocks of soil over a garage is the garage's roof, as a lawn is
		assertEquals(65F, column(true, B, D, D, D, B, A, A, A, B, G), 0F);
	}

	@Test
	void aLawnOnAGarageRoofIsARoofAndALawnOnTheGroundIsTheGround() {
		// grass, dirt, a concrete ceiling, a 3 high hall, the concrete floor on dirt
		assertEquals(67F, column(true, G, G, B, A, A, A, B, G), 0F);
		assertEquals(73F, column(false, G, G, B, A, A, A, B, G), 0F);   // "Drive into buildings" off
		assertEquals(73F, column(true, G, G, G, G, G, G), 0F);          // deep soil is the ground
		assertEquals(73F, column(true, G, B, B, B, B, B, B, B, B), 0F); // soil on a solid foundation: no room under it
		assertEquals(73F, column(true, G, G, A, A, A, G), 0F);          // soil over a cave stays a natural overhang
	}

	@Test
	void aRoadOnAnOldSuperflatMapIsTheGroundNotARoof() {
		// the house map's road: a stone slab on dirt, dirt, bedrock, and nothing under y = 0
		float[] tops = {0.5F, 1, 1, 1, 1, 1, 1};
		assertEquals(3.5F, TerrainHeights.columnSurface(3, new byte[] {B, G, G, G, A, A, A}, tops, 7, true), 0F);
	}

	@Test
	void theSurfaceBlockSaysWhetherTheGroundIsNatural() {
		float[] ones = {1, 1, 1, 1, 1, 1};
		assertTrue(TerrainHeights.naturalSurface(72, new byte[] {G, G}, 2, 73F));
		assertTrue(!TerrainHeights.naturalSurface(72, new byte[] {B, G, G}, 3, 73F));            // a road on soil
		assertTrue(!TerrainHeights.naturalSurface(72, new byte[] {B, G}, 2, 72.5F));             // a slab
		byte[] lawnUnderARoof = {B, A, A, A, G};
		assertTrue(TerrainHeights.naturalSurface(72, lawnUnderARoof, 5, TerrainHeights.columnSurface(72, lawnUnderARoof, ones, 5, true)));
	}

	@Test
	void aSlabIsHalfAStep() {
		assertEquals(72.5F, TerrainHeights.columnSurface(72, new byte[] {B, G}, new float[] {0.5F, 1F}, 2, true), 0F);
	}

	@Test
	void unknownSamplesTakeTheirKnownNeighboursAndTheMedianFillsTheRest() {
		float[] raw = flat(70);
		raw[0] = TerrainHeights.UNKNOWN;
		float[] s = TerrainHeights.smooth(raw, N);
		assertEquals(70F, s[0], 1e-5F);
		float[] none = new float[4];
		java.util.Arrays.fill(none, TerrainHeights.UNKNOWN);
		assertEquals(64F, TerrainHeights.median(none, 64F), 1e-6F);
		assertEquals(71F, TerrainHeights.median(new float[] {70, 71, 90}, 0F), 1e-6F);
	}

	/** A 41 x 41 window: a building (roofed, so not open) covers the middle 15 x 15, open lawn around it. */
	private static boolean[] lawnAroundABuilding(int w) {
		boolean[] open = new boolean[w * w];
		java.util.Arrays.fill(open, true);
		for (int r = 13; r <= 27; r++) {
			for (int c = 13; c <= 27; c++) {
				open[r * w + c] = false;
			}
		}
		return open;
	}

	@Test
	void aCarAskedForIndoorsGoesToTheNearestOpenGround() {
		int w = 41;
		float[] ground = new float[w * w];
		java.util.Arrays.fill(ground, 24F);
		int[] at = TerrainHeights.nearestOpen(ground, lawnAroundABuilding(w), w, w, 4, 0.5F, 20, 20);
		assertTrue(at != null, "no spot");
		// the building's edge is 7 blocks from the middle and a spot needs 4 blocks of open ground around it
		int dc = Math.abs(at[0] - 20), dr = Math.abs(at[1] - 20);
		assertEquals(12, Math.max(dc, dr), "spot " + at[0] + "," + at[1]);
		assertEquals(0, Math.min(dc, dr));
	}

	@Test
	void anOpenSpotIsUsedAsItIs() {
		int w = 41;
		float[] ground = new float[w * w];
		java.util.Arrays.fill(ground, 24F);
		int[] at = TerrainHeights.nearestOpen(ground, lawnAroundABuilding(w), w, w, 4, 0.5F, 5, 30);
		assertEquals(5, at[0]);
		assertEquals(30, at[1]);
	}

	@Test
	void aSpotNeedsFlatGroundAndAnswersNullWhenNothingQualifies() {
		int w = 31;
		float[] ground = new float[w * w];
		for (int r = 0; r < w; r++) {
			for (int c = 0; c < w; c++) {
				ground[r * w + c] = 24F + (c % 2);   // a one-block step every column: no car stands there
			}
		}
		boolean[] open = new boolean[w * w];
		java.util.Arrays.fill(open, true);
		assertEquals(null, TerrainHeights.nearestOpen(ground, open, w, w, 4, 0.5F, 15, 15));
		ground[0] = Float.NaN;   // unloaded columns never qualify either
		java.util.Arrays.fill(ground, 1, ground.length, 24.5F);
		int[] at = TerrainHeights.nearestOpen(ground, open, w, w, 4, 0.5F, 0, 0);
		assertTrue(at[0] + at[1] > 8, "spot over the unloaded column");
	}

	/** Heights of one row of 2 x 2 samples across blocks of the given heights (area = heights.length). */
	private static float[] sampleRow(float... blocks) {
		int area = blocks.length;
		float[] h = new float[area * area];
		for (int r = 0; r < area; r++) {
			System.arraycopy(blocks, 0, h, r * area, area);
		}
		float[] out = new float[area * 2];
		for (int c = 0; c < area * 2; c++) {
			out[c] = TerrainHeights.sampleKerbs(h, area, 2, 2, c);
		}
		return out;
	}

	@Test
	void aKerbIsARampAndAFullBlockStaysAWall() {
		float[] kerb = sampleRow(24F, 24F, 24.5F, 24.5F);
		assertEquals(24F, kerb[2], 1e-6F);             // the outer sample of the low block keeps its height
		assertEquals(24F + 0.5F / 3, kerb[3], 1e-6F);   // the inner ones lean a third of the way
		assertEquals(24.5F - 0.5F / 3, kerb[4], 1e-6F);
		assertEquals(24.5F, kerb[5], 1e-6F);
		float[] wall = sampleRow(24F, 24F, 25F, 25F);
		assertEquals(24F, wall[3], 1e-6F);              // a full block: still a step between two samples
		assertEquals(25F, wall[4], 1e-6F);
	}

	@Test
	void smoothGroundHasNoKerbRamps() {
		float[] h = {24F, 24.5F, 24F, 24.5F};
		assertEquals(24.5F, TerrainHeights.sampleKerbs(h, 2, 1, 0, 1), 0F);
	}

	@Test
	void anElevatedRoadIsADeckOverTheGroundUnderIt() {
		// a two-block concrete deck at 70..71, four blocks of air, the road at 65 on natural ground
		byte[] kinds = {B, B, A, A, A, A, B, G, G};
		float[] tops = {1, 1, 1, 1, 1, 1, 1, 1, 1};
		float ground = TerrainHeights.columnSurface(71, kinds, tops, kinds.length, true);
		assertEquals(66F, ground, 0F);   // the surface rule drives under it
		float[] out = new float[TerrainHeights.MAX_DECKS];
		assertEquals(1, TerrainHeights.decks(71, kinds, tops, kinds.length, ground, out));
		assertEquals(72F, out[0], 0F);
		// a kerb on the ground is no deck
		assertEquals(0, TerrainHeights.decks(66, new byte[] {B, G, G}, new float[] {0.5F, 1, 1}, 3, 66.5F, out));
	}

	@Test
	void aMonorailOverTheElevatedRoadIsTwoDecks() {
		// the monorail at 78, five blocks of air, the road deck at 71..72, three of air, the street at 67
		byte[] kinds = {B, A, A, A, A, A, B, B, A, A, A, B, G};
		float[] tops = new float[kinds.length];
		java.util.Arrays.fill(tops, 1F);
		float ground = TerrainHeights.columnSurface(78, kinds, tops, kinds.length, true);
		assertEquals(68F, ground, 0F);
		float[] out = new float[TerrainHeights.MAX_DECKS];
		assertEquals(2, TerrainHeights.decks(78, kinds, tops, kinds.length, ground, out));
		assertEquals(79F, out[0], 0F);
		assertEquals(73F, out[1], 0F);
	}

	@Test
	void aRunWithNoRoomOverItIsNoDeck() {
		// a slab one block under a ceiling: no car fits on it, so only the ceiling is a deck
		byte[] kinds = {B, A, B, A, A, A, G};
		float[] tops = {1, 1, 1, 1, 1, 1, 1};
		float[] out = new float[TerrainHeights.MAX_DECKS];
		float ground = TerrainHeights.columnSurface(80, kinds, tops, kinds.length, true);
		assertEquals(1, TerrainHeights.decks(80, kinds, tops, kinds.length, ground, out));
		assertEquals(81F, out[0], 0F);
	}

	private static final int S = 31, C = 15;   // a window of columns, the car in the middle one

	/** Every column the street at 64, no decks. */
	private static float[] street() {
		float[] lv = new float[S * S * TerrainHeights.LEVELS];
		java.util.Arrays.fill(lv, Float.NaN);
		for (int i = 0; i < S * S; i++) {
			lv[i * TerrainHeights.LEVELS] = 64;
		}
		return lv;
	}

	private static void set(float[] lv, int row, int col, int level, float v) {
		lv[(row * S + col) * TerrainHeights.LEVELS + level] = v;
	}

	private static int at(byte[] out, int row, int col) {
		return out[row * S + col];
	}

	@Test
	void underAnElevatedRoadTheStreetGoesOnAndOnItTheDeckDoes() {
		float[] lv = street();
		for (int r = 0; r < S; r++) {
			for (int c = 13; c <= 17; c++) {
				set(lv, r, c, 1, 71);   // a highway deck running north-south over the car
			}
		}
		byte[] under = TerrainHeights.reach(lv, S, 64.4F);
		assertEquals(0, at(under, 0, C));    // the street, all along under the deck
		assertEquals(0, at(under, C, 3));    // and beside it
		byte[] over = TerrainHeights.reach(lv, S, 71.4F);
		assertEquals(1, at(over, 0, C));     // the deck all along
		assertEquals(-1, at(over, C, 3));    // the street beside it is a drop away: left as it was
	}

	@Test
	void aRampIsFollowedUpFromItsFootAndTheStreetBesideItStaysTheStreet() {
		float[] lv = street();
		for (int c = 16; c <= 18; c++) {
			set(lv, C, c, 0, 64 + c - 15);   // the ramp's solid foot climbs a block a column...
		}
		for (int c = 19; c < S; c++) {
			set(lv, C, c, 1, 64 + c - 15);   // ...then stands on pillars over the street
		}
		byte[] out = TerrainHeights.reach(lv, S, 64.4F);
		assertEquals(0, at(out, C, 17));       // the foot is the surface
		assertEquals(1, at(out, C, S - 1));    // fifteen columns on, the ramp's deck at 79
		assertEquals(0, at(out, C - 1, S - 2)); // the street beside it
	}

	@Test
	void somethingOverTheStreetThatNoRoadReachesIsNeverChosen() {
		float[] lv = street();
		set(lv, C, 20, 1, 70);   // a street lamp's arm over the lane
		set(lv, C, 21, 1, 68);   // an awning
		byte[] out = TerrainHeights.reach(lv, S, 64.4F);
		assertEquals(0, at(out, C, 20));
		assertEquals(0, at(out, C, 21));
	}

	@Test
	void aCarInTheAirChangesNothing() {
		byte[] out = TerrainHeights.reach(street(), S, 80F);
		for (byte b : out) {
			assertEquals(-1, b);
		}
	}
}
