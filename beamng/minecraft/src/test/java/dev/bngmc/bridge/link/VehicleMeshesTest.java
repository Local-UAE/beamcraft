package dev.bngmc.bridge.link;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import org.junit.jupiter.api.Test;

class VehicleMeshesTest {
	private static final CrossoverCoords.Region HOST = new CrossoverCoords.Region(0, 0.0, -60.0, 0.0);

	private static JsonArray nums(double... v) {
		JsonArray a = new JsonArray();
		for (double d : v) {
			a.add(d);
		}
		return a;
	}

	private static JsonObject vmesh(long id, int tv, double[] pos, double... cm) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("tv", tv);
		o.addProperty("n", cm.length / 3);
		o.add("pos", nums(pos));
		o.add("p", nums(cm));
		return o;
	}

	private static JsonObject vtris(long id, int tv, int n, double... idx) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("tv", tv);
		o.addProperty("n", n);
		o.add("tris", nums(idx));
		return o;
	}

	@Test
	void parsesNodeOffsetsInCentimetres() {
		VehicleMeshes.Mesh m = VehicleMeshes.parseMesh(vmesh(7, 2, new double[] {10, 20, 0.3}, -112, 63, 11, 0, 0, 150), 5.0);
		assertNotNull(m);
		assertEquals(7, m.id());
		assertEquals(2, m.tv());
		assertEquals(2, m.nodes());
		assertEquals(new V3(10, 20, 0.3), m.pos());
		assertArrayEquals(new int[] {-112, 63, 11, 0, 0, 150}, m.p());
		assertEquals(100, m.q());   // no q: centimetres, as older senders
	}

	@Test
	void millimetreOffsetsComeOutInMetres() {
		JsonObject o = vmesh(7, 2, new double[] {0, 0, 0}, -1123, 634, 7);
		o.addProperty("q", 1000);
		VehicleMeshes.Mesh m = VehicleMeshes.parseMesh(o, 5.0);
		assertEquals(1000, m.q());
		MeshTimeline t = new MeshTimeline();
		t.add(m);
		float[] out = new float[3];
		t.sample(Double.MAX_VALUE).nodesMinecraftAxes(out);
		assertEquals(-1.123F, out[0], 1e-6);   // canonical (x, y, z) -> Minecraft (x, z, -y)
		assertEquals(0.007F, out[1], 1e-6);
		assertEquals(-0.634F, out[2], 1e-6);
	}

	@Test
	void rejectsMalformedMessages() {
		assertNull(VehicleMeshes.parseMesh(new JsonObject(), 0));
		JsonObject bad = vmesh(7, 1, new double[] {0, 0, 0}, 1, 2);   // not a multiple of 3
		assertNull(VehicleMeshes.parseMesh(bad, 0));
		assertNull(VehicleMeshes.parseTris(vtris(7, 1, 3, 0, 1)));      // not a multiple of 3
	}

	@Test
	void trianglesLandWhereCrossoverCoordsPutsTheNodes() {
		VehicleMeshes.Mesh m = VehicleMeshes.parseMesh(vmesh(7, 1, new double[] {10, 20, 0.3},
			0, 0, 0, 100, 0, 0, 0, 250, 140), 0);
		VehicleMeshes.Tris t = VehicleMeshes.parseTris(vtris(7, 1, 3, 0, 1, 2));
		double camX = 5, camY = -58, camZ = -18;
		float[] v = VehicleMeshes.cameraRelativeTriangles(m, t, HOST, camX, camY, camZ);
		assertEquals(9, v.length);
		V3[] expected = {new V3(10, 20, 0.3), new V3(11, 20, 0.3), new V3(10, 22.5, 1.7)};
		for (int i = 0; i < 3; i++) {
			V3 mc = CrossoverCoords.canonicalToMinecraftPosition(expected[i], HOST);
			assertEquals(mc.x() - camX, v[i * 3], 1e-4);
			assertEquals(mc.y() - camY, v[i * 3 + 1], 1e-4);
			assertEquals(mc.z() - camZ, v[i * 3 + 2], 1e-4);
		}
	}

	@Test
	void trianglesNamingMissingNodesAreSkipped() {
		VehicleMeshes.Mesh m = VehicleMeshes.parseMesh(vmesh(7, 1, new double[] {0, 0, 0}, 0, 0, 0, 1, 0, 0, 0, 1, 0), 0);
		VehicleMeshes.Tris t = VehicleMeshes.parseTris(vtris(7, 1, 3, 0, 1, 2, 0, 1, 9, -1, 0, 1));
		assertEquals(9, VehicleMeshes.cameraRelativeTriangles(m, t, HOST, 0, 0, 0).length);
	}

	@Test
	void trianglesAreAskedForOnlyWhenTheShapeIsKnownAndMissing() {
		VehicleMeshes.Mesh reading = VehicleMeshes.parseMesh(vmesh(7, 0, new double[] {0, 0, 0}, 0, 0, 0), 0);
		VehicleMeshes.Mesh known = VehicleMeshes.parseMesh(vmesh(7, 3, new double[] {0, 0, 0}, 0, 0, 0), 0);
		VehicleMeshes.Tris old = VehicleMeshes.parseTris(vtris(7, 2, 1, 0, 0, 0));
		VehicleMeshes.Tris current = VehicleMeshes.parseTris(vtris(7, 3, 1, 0, 0, 0));
		assertFalse(VehicleMeshes.needsTris(reading, null));
		assertTrue(VehicleMeshes.needsTris(known, null));
		assertTrue(VehicleMeshes.needsTris(known, old));
		assertFalse(VehicleMeshes.needsTris(known, current));
	}

	@Test
	void drawableOnlyWhenTrianglesMatchTheShape() {
		VehicleMeshes.Mesh m = VehicleMeshes.parseMesh(vmesh(7, 3, new double[] {0, 0, 0}, 0, 0, 0, 1, 0, 0, 0, 1, 0), 0);
		assertFalse(VehicleMeshes.drawable(m, VehicleMeshes.parseTris(vtris(7, 2, 3, 0, 1, 2))));
		assertFalse(VehicleMeshes.drawable(m, VehicleMeshes.parseTris(vtris(7, 3, 4, 0, 1, 2))));
		assertTrue(VehicleMeshes.drawable(m, VehicleMeshes.parseTris(vtris(7, 3, 3, 0, 1, 2))));
	}
}
