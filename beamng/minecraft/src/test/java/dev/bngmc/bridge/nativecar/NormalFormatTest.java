package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NormalFormatTest {
	private static int abgr(int r, int g, int b, int a) {
		return (a << 24) | (b << 16) | (g << 8) | r;
	}

	/** A bumpy normal at (x, y): x and y swing around the middle. */
	private static int bump(int x, int y) {
		return 128 + (int) (60 * Math.sin(x * 0.3) * Math.cos(y * 0.2));
	}

	@Test
	void aThreeChannelMapIsXyz() {
		assertEquals(NormalFormat.XYZ, NormalFormat.of((x, y) -> abgr(bump(x, y), bump(y, x), 200 + (x % 40), 255), 256, 256));
	}

	@Test
	void aMapWithConstantBlueIsTwoChannel() {
		assertEquals(NormalFormat.RG, NormalFormat.of((x, y) -> abgr(bump(x, y), bump(y, x), 0, 255), 256, 256));
	}

	@Test
	void aDxt5MapKeepsXInAlpha() {
		assertEquals(NormalFormat.AG, NormalFormat.of((x, y) -> abgr(255, bump(y, x), 0, bump(x, y)), 256, 256));
	}

	@Test
	void aFlatMapDecodesAsXyz() {
		assertEquals(NormalFormat.XYZ, NormalFormat.of((x, y) -> abgr(128, 128, 255, 255), 64, 64));
	}
}
