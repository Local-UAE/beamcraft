package dev.bngmc.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerrainPostsTest {
	@BeforeAll
	static void minecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void aLampPostStandingOnTheRoadIsABoxOfItsOwnSize() {
		// Newisle's lamp post: five andesite wall posts (8/16 wide) on the road at 66, scanned top down
		var c = new TerrainPosts.Column();
		c.start(-229, -267);
		for (int y = 71; y >= 67; y--) {
			c.thin(y, 0.25, 0.75, 0.25, 0.75, 1.5);
		}
		c.solid();
		assertArrayEquals(new float[] {-228.75F, 67F, -266.75F, -228.25F, 72F, -266.25F}, c.boxes.toFloatArray(), 1e-5F);
	}

	@Test
	void aPaneIsWidenedAndAHangingChainIsNoPost() {
		var c = new TerrainPosts.Column();
		c.start(0, 0);
		c.thin(70, 0.4375, 0.5625, 0.4375, 0.5625, 1);   // an unconnected glass pane on the ground
		c.solid();
		float[] b = c.boxes.toFloatArray();
		assertEquals(TerrainPosts.MIN_SIDE, b[3] - b[0], 1e-5);
		c.start(0, 0);
		c.thin(75, 0.40625, 0.59375, 0.40625, 0.59375, 1);   // a chain under a lamp's arm, air below it
		c.open();
		assertEquals(0, c.boxes.size());
	}

	@Test
	void postsAreThinAndRailingsAreNot() {
		assertTrue(TerrainPosts.thin(Blocks.ANDESITE_WALL.defaultBlockState()));   // a lone wall post
		assertTrue(TerrainPosts.thin(Blocks.OAK_FENCE.defaultBlockState()));
		assertTrue(TerrainPosts.thin(Blocks.END_ROD.defaultBlockState()));
		assertTrue(!TerrainPosts.thin(Blocks.OAK_FENCE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceBlock.EAST, true)
			.setValue(net.minecraft.world.level.block.FenceBlock.WEST, true)));   // a fence along a road: a barrier, terrain
		assertTrue(!TerrainPosts.thin(Blocks.STONE.defaultBlockState()));
		assertTrue(!TerrainPosts.thin(Blocks.STONE_SLAB.defaultBlockState()));
	}
}
