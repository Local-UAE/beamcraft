package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.Map;

class FlexBindingTest {
	// Two parts: "body" follows nodes 0-3 (a tetrahedron around the origin), "door" nodes 4-7 (one at x = 10).
	private static final float[] NODES = {
		0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1,
		10, 0, 0, 11, 0, 0, 10, 1, 0, 10, 0, 1,
	};
	// vertex 0 belongs to the body, vertex 1 to the door
	private static final float[] VERTS = {0.3F, 0.2F, 0.1F, 10.3F, 0.2F, 0.1F};
	private static final float[] NORMALS = {0, 0, 1, 0, 1, 0};
	private static final String[] VERTEX_PART = {"body", "door"};
	private static final Map<String, int[]> GROUPS = Map.of("body", new int[] {0, 1, 2, 3}, "door", new int[] {4, 5, 6, 7});

	private static FlexBinding bind() {
		return FlexBinding.bind(VERTS, NORMALS, VERTEX_PART, GROUPS, NODES);
	}

	private static void assertVec(float[] a, int i, double x, double y, double z) {
		assertEquals(x, a[i * 3], 1e-4);
		assertEquals(y, a[i * 3 + 1], 1e-4);
		assertEquals(z, a[i * 3 + 2], 1e-4);
	}

	@Test
	void unmovedNodesGiveTheRestMesh() {
		FlexBinding b = bind();
		float[] pos = new float[6], nrm = new float[6];
		b.deform(NODES, pos, nrm);
		assertVec(pos, 0, 0.3, 0.2, 0.1);
		assertVec(pos, 1, 10.3, 0.2, 0.1);
		assertVec(nrm, 0, 0, 0, 1);
	}

	@Test
	void theWholeCarMovingAndTurningCarriesEveryVertex() {
		// rotate 90 degrees about Y (x -> -z, z -> x) and shift by (5, 2, -3)
		float[] moved = new float[NODES.length];
		for (int i = 0; i < NODES.length; i += 3) {
			moved[i] = NODES[i + 2] + 5;
			moved[i + 1] = NODES[i + 1] + 2;
			moved[i + 2] = -NODES[i] - 3;
		}
		float[] pos = new float[6], nrm = new float[6];
		bind().deform(moved, pos, nrm);
		assertVec(pos, 0, 0.1 + 5, 0.2 + 2, -0.3 - 3);
		assertVec(pos, 1, 0.1 + 5, 0.2 + 2, -10.3 - 3);
		assertVec(nrm, 0, 1, 0, 0);    // (0,0,1) turned the same way
	}

	@Test
	void aVertexOnlyFollowsItsOwnPart() {
		float[] moved = NODES.clone();
		for (int i = 12; i < 24; i += 3) {
			moved[i + 1] += 4;    // the door's nodes lift by 4
		}
		float[] pos = new float[6], nrm = new float[6];
		bind().deform(moved, pos, nrm);
		assertVec(pos, 0, 0.3, 0.2, 0.1);          // the body didn't move
		assertVec(pos, 1, 10.3, 4.2, 0.1);         // the door did
	}

	/** CarShader's vertex shader, line for line: the frame from three nodes, the vertex rebuilt in it. */
	private static float[] shaderBend(int[] f, float[] l, float[] nodes) {
		float[] o = {nodes[f[0] * 3], nodes[f[0] * 3 + 1], nodes[f[0] * 3 + 2]};
		if (f[1] < 0) {
			return new float[] {o[0] + l[0], o[1] + l[1], o[2] + l[2]};
		}
		float[] a = new float[3], b = new float[3];
		for (int k = 0; k < 3; k++) {
			a[k] = nodes[f[1] * 3 + k] - o[k];
			b[k] = nodes[f[2] * 3 + k] - o[k];
		}
		float[] t1 = normalize(a);
		float[] t3 = normalize(cross(t1, b));
		float[] t2 = cross(t3, t1);
		float[] p = new float[3];
		for (int k = 0; k < 3; k++) {
			p[k] = o[k] + l[0] * t1[k] + l[1] * t2[k] + l[2] * t3[k];
		}
		return p;
	}

	private static float[] cross(float[] a, float[] b) {
		return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static float[] normalize(float[] a) {
		float l = Math.max((float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), 1e-9F);
		return new float[] {a[0] / l, a[1] / l, a[2] / l};
	}

	@Test
	void theVertexDataForTheGpuBendsLikeTheCpu() {
		FlexBinding b = bind();
		java.nio.IntBuffer frames = java.nio.IntBuffer.allocate(6);
		java.nio.FloatBuffer local = java.nio.FloatBuffer.allocate(6), localN = java.nio.FloatBuffer.allocate(6);
		b.write(frames, local, localN);
		float[] moved = NODES.clone();
		for (int i = 0; i < moved.length; i += 3) {   // twist and shift every node a little differently
			moved[i] += 0.1F * i;
			moved[i + 1] += 0.05F * (moved[i] * moved[i]);
			moved[i + 2] -= 0.2F;
		}
		float[] pos = new float[6], nrm = new float[6];
		b.deform(moved, pos, nrm);
		for (int v = 0; v < 2; v++) {
			int[] f = {frames.get(v * 3), frames.get(v * 3 + 1), frames.get(v * 3 + 2)};
			float[] p = shaderBend(f, new float[] {local.get(v * 3), local.get(v * 3 + 1), local.get(v * 3 + 2)}, moved);
			assertVec(p, 0, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]);
		}
	}

	@Test
	void aPartWithoutAGroupFallsBackToTheNearestNodes() {
		FlexBinding b = FlexBinding.bind(VERTS, NORMALS, new String[] {"mystery", "mystery"}, GROUPS, NODES);
		float[] moved = NODES.clone();
		for (int i = 12; i < 24; i += 3) {
			moved[i] += 1;
		}
		float[] pos = new float[6], nrm = new float[6];
		b.deform(moved, pos, nrm);
		assertVec(pos, 0, 0.3, 0.2, 0.1);
		assertVec(pos, 1, 11.3, 0.2, 0.1);         // nearest nodes are the door's
	}
}
