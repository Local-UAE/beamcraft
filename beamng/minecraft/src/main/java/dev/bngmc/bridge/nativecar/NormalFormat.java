package dev.bngmc.bridge.nativecar;

import java.util.function.IntBinaryOperator;

/**
 * How a normal map stores its normal. BeamNG decodes by the DDS format (shadergen.h.hlsl:214-232:
 * DXT5 in alpha and green, BC5 in red and green with z rebuilt, otherwise all three channels); the
 * exporter's PNG copy loses that, so it is read off the pixels: a DXT5 normal map has a constant red
 * and the x in alpha, a two-channel map a constant blue. Pure Java, unit-tested.
 */
public final class NormalFormat {
	private NormalFormat() {
	}

	/** x, y, z in red, green, blue. */
	public static final int XYZ = 0;
	/** x, y in red, green; z = sqrt(1 - x^2 - y^2). */
	public static final int RG = 1;
	/** x, y in alpha, green; z rebuilt. */
	public static final int AG = 2;

	private static final int SAMPLES = 32;          // per side
	private static final double FLAT = 0.02;        // a channel varying less than this (0-1, std dev) is constant

	/**
	 * @param abgr pixel (x, y) -> 0xAABBGGRR, as NativeImage.getPixelRGBA returns it
	 */
	public static int of(IntBinaryOperator abgr, int width, int height) {
		double[] sum = new double[4], sq = new double[4];
		int n = 0;
		for (int j = 0; j < SAMPLES; j++) {
			for (int i = 0; i < SAMPLES; i++) {
				int p = abgr.applyAsInt((int) ((i + 0.5) * width / SAMPLES), (int) ((j + 0.5) * height / SAMPLES));
				for (int c = 0; c < 4; c++) {
					double v = ((p >>> (8 * c)) & 0xFF) / 255.0;
					sum[c] += v;
					sq[c] += v * v;
				}
				n++;
			}
		}
		double[] std = new double[4];
		for (int c = 0; c < 4; c++) {
			double mean = sum[c] / n;
			std[c] = Math.sqrt(Math.max(0, sq[c] / n - mean * mean));
		}
		if (std[0] < FLAT && std[3] >= FLAT) {
			return AG;
		}
		if (std[2] < FLAT && (std[0] >= FLAT || std[1] >= FLAT)) {
			return RG;
		}
		return XYZ;
	}
}
