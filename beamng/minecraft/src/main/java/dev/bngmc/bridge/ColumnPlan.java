package dev.bngmc.bridge;

import java.util.Map;
import java.util.TreeMap;

/**
 * Which terrain voxels one 1x1 column gets, from BeamNG's surfaces under it. Pure function (no
 * Minecraft world), so it is unit-tested on its own (ColumnPlanTest).
 *
 * <p>Input: the surfaces BeamNG found in the column, highest first, in Minecraft Y; the player's
 * feet Y when the column was sampled; whether the wall probes at feet + {@link #PROBE_HEIGHT} hit
 * something steep. Output: Y -> height in sixteenths (16 = full block), the top voxel of each
 * surface sized so its top matches the surface to 1/16 block.
 *
 * <ul>
 *   <li>Each surface is a slab {@link #THICKNESS} + 1 blocks deep, keeping two blocks of air above
 *   the surface below it, so the space under a ramp, a bridge or a roof stays walkable.</li>
 *   <li>The lowest surface is the ground: solid down past the player's feet. If it is above the
 *   player (a hill), it is capped at {@link #CLIFF_FILL} above the feet, so it blocks like a wall.</li>
 *   <li>A wall fills the surface the player stands on up to {@link #WALL_HEIGHT} above the feet,
 *   but never into the surface above it.</li>
 * </ul>
 */
public final class ColumnPlan {
	private ColumnPlan() {
	}

	public static final int THICKNESS = 2;
	public static final double PROBE_HEIGHT = 1.0;
	public static final double WALL_HEIGHT = 3.0;
	public static final double CLIFF_FILL = 6.0;
	/** A surface this far above the feet still counts as the one the player stands on (step up). */
	public static final double STAND_REACH = 1.0;

	public static int floorBlock(double y) {
		return (int) Math.floor(y - 1e-4);
	}

	public static int sixteenths(double y, int block) {
		return Math.max(1, Math.min(16, (int) Math.ceil((y - block) * 16.0 - 1e-3)));
	}

	public static Map<Integer, Integer> plan(double[] surf, double sampleY, boolean wall) {
		TreeMap<Integer, Integer> out = new TreeMap<>();
		int feet = (int) Math.floor(sampleY);
		double probeY = sampleY + PROBE_HEIGHT;
		for (int i = 0; i < surf.length; i++) {
			boolean lowest = i == surf.length - 1;
			double gy = lowest ? Math.min(surf[i], sampleY + CLIFF_FILL) : surf[i];
			int top = floorBlock(gy);
			int bottom = lowest ? Math.min(top, feet) - THICKNESS : top - THICKNESS;
			if (!lowest) {
				bottom = Math.min(top, Math.max(bottom, floorBlock(surf[i + 1]) + 3));
			}
			boolean standsHere = gy <= sampleY + STAND_REACH && (i == 0 || surf[i - 1] > sampleY + STAND_REACH);
			if (wall && standsHere && gy < probeY - 0.25) {
				int wallTop = Math.max(top, (int) Math.floor(sampleY + WALL_HEIGHT));
				if (i > 0) {
					wallTop = Math.min(wallTop, floorBlock(surf[i - 1]) - 1);
				}
				for (int y = wallTop; y >= top; y--) {
					out.putIfAbsent(y, 16);
				}
			} else {
				out.putIfAbsent(top, sixteenths(gy, top));
			}
			for (int y = top - 1; y >= bottom; y--) {
				out.putIfAbsent(y, 16);
			}
		}
		return out;
	}

	/** Top of the highest solid voxel at or below {@code y} (where something at y would rest), or NaN. */
	public static double restingHeight(Map<Integer, Integer> plan, double y) {
		double best = Double.NaN;
		for (var e : plan.entrySet()) {
			double t = e.getKey() + e.getValue() / 16.0;
			if (t <= y + 1e-6 && (Double.isNaN(best) || t > best)) {
				best = t;
			}
		}
		return best;
	}
}
