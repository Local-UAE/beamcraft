package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

class SkyOcclusionTest {
	/** A 1 m square in the plane y = h (two triangles), vertices 4 * k .. 4 * k + 3. */
	private static void square(float[] p, int k, float h) {
		float[] q = {-0.5F, h, -0.5F, 0.5F, h, -0.5F, 0.5F, h, 0.5F, -0.5F, h, 0.5F};
		System.arraycopy(q, 0, p, k * 12, 12);
	}

	@Test
	void anOpenFloorSeesTheWholeSky() {
		float[] p = new float[12];
		square(p, 0, 0);
		float[] n = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
		float[] vis = SkyOcclusion.bake(p, n, List.of(new int[] {0, 1, 2, 0, 2, 3}));
		for (float v : vis) {
			assertEquals(1F, v, 1e-6F);
		}
	}

	@Test
	void aFloorUnderARoofIsInTheShadeButNotAtTheOpenSides() {
		// floor at y 0 facing up, roof at y 0.4 over it; the roof shades the floor's middle most
		float[] p = new float[12 * 2 + 3];
		square(p, 0, 0);
		square(p, 1, 0.4F);
		p[24] = 0;                       // one more floor vertex in the middle
		p[25] = 0;
		p[26] = 0;
		float[] n = new float[p.length];
		for (int i = 1; i < n.length; i += 3) {
			n[i] = 1;
		}
		List<int[]> occluders = List.of(new int[] {0, 1, 2, 0, 2, 3}, new int[] {4, 5, 6, 4, 6, 7});
		float[] vis = SkyOcclusion.bake(p, n, occluders);
		// rays start 1.8 cells (6 cm) up, so those within atan(0.5 / 0.34) = 56 degrees of straight up hit
		// the roof: cos^2(56) = 0.31 gets out, give or take one of the 16 rays
		assertEquals(0.31F, vis[8], 0.08F, "middle of the floor under the roof");
		assertTrue(vis[0] > vis[8], "a corner sees more sky than the middle");
		assertEquals(1F, vis[4], 1e-6F);   // the roof itself sees the open sky
	}

	@Test
	void glassLeftOutOfTheOccludersLetsTheSkyIn() {
		float[] p = new float[12 * 2 + 3];
		square(p, 0, 0);
		square(p, 1, 0.4F);
		float[] n = new float[p.length];
		for (int i = 1; i < n.length; i += 3) {
			n[i] = 1;
		}
		float[] vis = SkyOcclusion.bake(p, n, List.of(new int[] {0, 1, 2, 0, 2, 3}));   // the roof is "glass"
		assertEquals(1F, vis[8], 1e-6F);
	}

	@Test
	void theRaysAreCosineWeightedOverTheHemisphere() {
		float sumZ = 0;
		for (float[] d : SkyOcclusion.hemisphere()) {
			assertEquals(1F, d[0] * d[0] + d[1] * d[1] + d[2] * d[2], 1e-5F);
			assertTrue(d[2] >= 0);
			sumZ += d[2];
		}
		assertEquals(2F / 3F, sumZ / SkyOcclusion.RAYS, 0.05F);   // mean cos of a cosine-weighted hemisphere
	}
}
