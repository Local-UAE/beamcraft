package dev.bngmc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Map;

/** Column voxel plans for the cases measured in BeamNG (smallgrid, gridmap_v2). */
class ColumnPlanTest {
	private static double topAt(Map<Integer, Integer> plan, int y) {
		return y + plan.get(y) / 16.0;
	}

	@Test
	void flatGroundTopMatchesToASixteenth() {
		Map<Integer, Integer> p = ColumnPlan.plan(new double[] {100.0}, 100.0, false);
		assertEquals(16, p.get(99));
		assertEquals(100.0, topAt(p, 99), 1e-9);
		assertFalse(p.containsKey(100));
		assertTrue(p.containsKey(97));   // two blocks below the top voxel
	}

	@Test
	void groundBetweenBlocksRoundsUpWithinOneSixteenth() {
		Map<Integer, Integer> p = ColumnPlan.plan(new double[] {102.76}, 102.8, false);
		assertEquals(13, p.get(102));      // 102 + 13/16 = 102.8125
		assertTrue(topAt(p, 102) - 102.76 < 1.0 / 16 + 1e-9);
	}

	@Test
	void rampOverGroundSampledFromBelowKeepsTheRampTop() {
		// gridmap_v2 cell (-25, -251): ramp top 103.25 over the ground at 100. Sampled while Steve
		// stood on the ground, the wall probes hit the ramp's solid body.
		Map<Integer, Integer> p = ColumnPlan.plan(new double[] {103.25, 100.0}, 100.0, true);
		assertEquals(4, p.get(103), "the ramp's top voxel keeps its height (103.25), it isn't raised to 104");
		assertFalse(p.containsKey(104));
		assertEquals(103.25, ColumnPlan.restingHeight(p, 104), 1e-9);
		// the body under the ramp is filled (it's solid): no walking into it from the ground
		assertTrue(p.containsKey(100) && p.containsKey(101) && p.containsKey(102));
	}

	@Test
	void rampOverGroundSampledFromAboveIsTheSameSurface() {
		Map<Integer, Integer> p = ColumnPlan.plan(new double[] {103.25, 100.0}, 102.81, false);
		assertEquals(103.25, ColumnPlan.restingHeight(p, 104), 1e-9);
		assertEquals(4, p.get(103));
	}

	@Test
	void bridgeLeavesHeadroomUnderTheDeck() {
		// deck at 106 over a road at 100, no walls: the road keeps 2+ blocks of air above it
		Map<Integer, Integer> p = ColumnPlan.plan(new double[] {106.0, 100.0}, 100.0, false);
		assertEquals(106.0, topAt(p, 105), 1e-9);
		for (int y = 100; y <= 102; y++) {
			assertFalse(p.containsKey(y), "air at " + y);
		}
		assertEquals(100.0, ColumnPlan.restingHeight(p, 101.8), 1e-9);
	}

	@Test
	void wallFillsToHeadHeight() {
		Map<Integer, Integer> p = ColumnPlan.plan(new double[] {100.0}, 100.0, true);
		assertTrue(p.containsKey(103));     // floor(100 + 3)
		assertEquals(16, p.get(100));
	}

	@Test
	void wallBelowAProbeIsIgnoredWhenTheGroundIsAlreadyHigh() {
		// probe at 101 inside a slope whose surface is 101.5: not a wall, just ground
		Map<Integer, Integer> p = ColumnPlan.plan(new double[] {101.5}, 100.0, true);
		assertEquals(101.5, ColumnPlan.restingHeight(p, 110), 1e-9);
	}

	@Test
	void hillAboveThePlayerIsCappedAndSolid() {
		Map<Integer, Integer> p = ColumnPlan.plan(new double[] {130.0}, 100.0, false);
		assertEquals(106.0, ColumnPlan.restingHeight(p, 200), 1e-9);   // capped at feet + CLIFF_FILL
		for (int y = 98; y <= 105; y++) {
			assertTrue(p.containsKey(y), "solid at " + y);
		}
	}

	@Test
	void noSurfacesNoBlocks() {
		assertTrue(ColumnPlan.plan(new double[0], 100.0, true).isEmpty());
	}
}
