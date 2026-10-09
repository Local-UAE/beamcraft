package dev.bngmc.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import org.junit.jupiter.api.Test;

class BlockSyncTest {
	@Test
	void blockCellMatchesTheCanonicalTransform() {
		for (int idx : new int[] {0, 1, 3}) {
			CrossoverCoords.Region r = CrossoverCoords.Region.of(idx);
			int x = (int) r.mcOriginX() - 7, y = 101, z = 265;
			int[] c = BlockSync.canonicalCell(x, y, z, r);
			// the block's centre, converted the official way, is the cell's centre
			V3 centre = CrossoverCoords.minecraftToCanonicalPosition(new V3(x + 0.5, y + 0.5, z + 0.5), r);
			assertEquals(c[0] + 0.5, centre.x(), 1e-9);
			assertEquals(c[1] + 0.5, centre.y(), 1e-9);
			assertEquals(c[2] + 0.5, centre.z(), 1e-9);
		}
	}

	@Test
	void hostAnchorPutsTheSuperflatBedrockTopOnBeamngsFloor() {
		// BeamNG's floor (z = 0) can't be lowered, so it is the bedrock: its top face (y = -63) is z = 0,
		// and the grass top (y = -60) is 3 blocks above it, with the dirt and grass as boxes in between.
		CrossoverCoords.Region host = BngWorld.HOST_REGION;
		V3 bedrockTop = CrossoverCoords.minecraftToCanonicalPosition(new V3(0.5, BngWorld.HOST_BEDROCK_TOP_Y, 0.5), host);
		assertEquals(0.0, bedrockTop.z(), 1e-9);
		V3 grassTop = CrossoverCoords.minecraftToCanonicalPosition(new V3(0.5, BngWorld.HOST_GROUND_TOP_Y, 0.5), host);
		assertEquals(3.0 / BngWorld.HOST_SCALE, grassTop.z(), 1e-9);   // 3 blocks, at HOST_SCALE blocks per metre
		// a block placed on the grass fills cell z = 3 (cells are blocks; BeamNG sizes them 1 / scale m)
		assertEquals(3, BlockSync.canonicalCell(3, BngWorld.HOST_GROUND_TOP_Y, -4, host)[2]);
	}

	@Test
	void hostWorldOnlyMirrorsBlocksAboveTheGround() {
		assertFalse(BlockSync.mirrorsY(BngWorld.HOST_GROUND_TOP_Y - 1, true));    // the grass layer
		assertFalse(BlockSync.mirrorsY(-64, true));                               // bedrock
		assertTrue(BlockSync.mirrorsY(BngWorld.HOST_GROUND_TOP_Y, true));         // first block on the grass
		assertTrue(BlockSync.mirrorsY(-64, false));                               // the bridge world keeps everything
	}

	@Test
	void regionZeroExample() {
		// BeamNG cell (-7, -266, 101) = Minecraft block (-7, 101, 265) in region 0
		assertArrayEquals(new int[] {-7, -266, 101}, BlockSync.canonicalCell(-7, 101, 265, CrossoverCoords.Region.of(0)));
	}
}
