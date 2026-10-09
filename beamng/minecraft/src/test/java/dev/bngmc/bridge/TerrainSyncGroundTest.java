package dev.bngmc.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerrainSyncGroundTest {
	@BeforeAll
	static void minecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void signsBannersAndPressurePlatesDontCollide() {
		// Minecraft calls them solid (forceSolidOn) but nothing collides with them: a street sign over
		// Newisle's road stood in BeamNG as a three-block pillar across the lane (2026-10-05)
		assertTrue(Blocks.OAK_WALL_SIGN.defaultBlockState().blocksMotion());
		assertTrue(Blocks.STONE_PRESSURE_PLATE.defaultBlockState().blocksMotion());
		assertFalse(TerrainSync.collides(Blocks.OAK_WALL_SIGN.defaultBlockState()));
		assertFalse(TerrainSync.collides(Blocks.OAK_SIGN.defaultBlockState()));
		assertFalse(TerrainSync.collides(Blocks.OAK_HANGING_SIGN.defaultBlockState()));
		assertFalse(TerrainSync.collides(Blocks.WHITE_BANNER.defaultBlockState()));
		assertFalse(TerrainSync.collides(Blocks.STONE_PRESSURE_PLATE.defaultBlockState()));
	}

	@Test
	void whatACarRunsIntoCollides() {
		assertTrue(TerrainSync.collides(Blocks.STONE.defaultBlockState()));
		assertTrue(TerrainSync.collides(Blocks.STONE_SLAB.defaultBlockState()));
		assertTrue(TerrainSync.collides(Blocks.OAK_FENCE.defaultBlockState()));
		assertTrue(TerrainSync.collides(Blocks.IRON_BARS.defaultBlockState()));
	}
}
