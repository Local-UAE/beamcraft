package dev.bngmc.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.bngmc.bridge.coords.CrossoverCoords;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroundSyncTest {
	private static boolean[] full() {
		boolean[] s = new boolean[16 * 16 * GroundSync.LAYERS];
		java.util.Arrays.fill(s, true);
		return s;
	}

	private static int volume(List<int[]> boxes) {
		int v = 0;
		for (int[] b : boxes) {
			v += (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
		}
		return v;
	}

	@Test
	void anUntouchedChunkIsOneBox() {
		List<int[]> boxes = GroundSync.merge(full());
		assertEquals(1, boxes.size());
		assertArrayEquals(new int[] {0, 0, 0, 15, GroundSync.LAYERS - 1, 15}, boxes.get(0));
	}

	@Test
	void aHoleSplitsTheChunkButEveryBlockStaysCovered() {
		boolean[] s = full();
		// a 2 x 2 hole through the top two layers at x 5..6, z 9..10
		for (int y = 1; y < 3; y++) {
			for (int x = 5; x <= 6; x++) {
				for (int z = 9; z <= 10; z++) {
					s[GroundSync.index(x, y, z)] = false;
				}
			}
		}
		List<int[]> boxes = GroundSync.merge(s);
		assertEquals(16 * 16 * 3 - 8, volume(boxes));
		for (int[] b : boxes) {
			for (int y = b[1]; y <= b[4]; y++) {
				for (int x = b[0]; x <= b[3]; x++) {
					for (int z = b[2]; z <= b[5]; z++) {
						assertEquals(true, s[GroundSync.index(x, y, z)], "box covers a dug block");
					}
				}
			}
		}
		assertEquals(true, boxes.size() <= 6, "merged, not one box per block: " + boxes.size());
	}

	@Test
	void anEmptyChunkHasNoBoxes() {
		assertEquals(0, GroundSync.merge(new boolean[16 * 16 * GroundSync.LAYERS]).size());
	}

	@Test
	void boxesBecomeCanonicalMetres() {
		// host region: Minecraft (0, -60, 0) = BeamNG (0, 0, 0); a block (x, y, z) is the cell
		// (x, -z - 1, y + 60) (BlockSync.canonicalCell)
		CrossoverCoords.Region r = CrossoverCoords.Region.of(0);
		double[] c = GroundSync.canonical(new int[] {0, 0, 0, 15, 2, 15}, 32, -63, -16, r);
		int[] one = BlockSync.canonicalCell(32, -63, -16, r);
		int[] other = BlockSync.canonicalCell(47, -61, -1, r);
		assertArrayEquals(new double[] {one[0], other[1], one[2], other[0] + 1, one[1] + 1, other[2] + 1}, c, 1e-9);
	}

	@Test
	void aScaledRegionGivesSmallerBoxesInMetres() {
		CrossoverCoords.Region r = new CrossoverCoords.Region(0, 0, -63, 0, 2.0);
		double[] c = GroundSync.canonical(new int[] {0, 0, 0, 15, 2, 15}, 0, -63, -16, r);
		assertArrayEquals(new double[] {0, 0, 0, 8, 8, 1.5}, c, 1e-9);   // 16 x 16 x 3 blocks = 8 x 8 x 1.5 m
	}
}
