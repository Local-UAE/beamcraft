package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

class CarModelTest {
	/**
	 * A GLB shaped like BeamNG's exporter output: one shared vertex buffer (4 vertices) used by two
	 * flexmeshes ("body": 2 triangles, painted; "window": 1 triangle, glass), plus a prop with its
	 * own vertices (ignored), and one embedded image.
	 */
	static byte[] sampleGlb() {
		ByteBuffer bin = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
		float[] pos = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
		float[] nrm = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
		float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
		int[] idx = {0, 1, 2, 0, 2, 3, 1, 2, 3};
		float[] propPos = {5, 5, 5, 6, 5, 5, 5, 6, 5};
		byte[] png = {1, 2, 3, 4, 5};
		int oPos = bin.position();
		for (float f : pos) {
			bin.putFloat(f);
		}
		int oNrm = bin.position();
		for (float f : nrm) {
			bin.putFloat(f);
		}
		int oUv = bin.position();
		for (float f : uv) {
			bin.putFloat(f);
		}
		int oIdx = bin.position();
		for (int i : idx) {
			bin.putInt(i);
		}
		int oProp = bin.position();
		for (float f : propPos) {
			bin.putFloat(f);
		}
		int oPng = bin.position();
		bin.put(png);
		while (bin.position() % 4 != 0) {
			bin.put((byte) 0);
		}
		int binLen = bin.position();
		String json = """
			{"asset":{"version":"2.0"},
			 "buffers":[{"byteLength":%d}],
			 "bufferViews":[
			  {"buffer":0,"byteOffset":%d,"byteLength":48},
			  {"buffer":0,"byteOffset":%d,"byteLength":48},
			  {"buffer":0,"byteOffset":%d,"byteLength":32},
			  {"buffer":0,"byteOffset":%d,"byteLength":36},
			  {"buffer":0,"byteOffset":%d,"byteLength":36},
			  {"buffer":0,"byteOffset":%d,"byteLength":5}],
			 "accessors":[
			  {"bufferView":0,"componentType":5126,"count":4,"type":"VEC3"},
			  {"bufferView":1,"componentType":5126,"count":4,"type":"VEC3"},
			  {"bufferView":2,"componentType":5126,"count":4,"type":"VEC2"},
			  {"bufferView":3,"componentType":5125,"count":6,"type":"SCALAR"},
			  {"bufferView":3,"byteOffset":24,"componentType":5125,"count":3,"type":"SCALAR"},
			  {"bufferView":4,"componentType":5126,"count":3,"type":"VEC3"}],
			 "images":[{"bufferView":5,"mimeType":"image/png"}],
			 "textures":[{"source":0}],
			 "materials":[
			  {"name":"car_main","pbrMetallicRoughness":{"baseColorTexture":{"index":0}},
			   "extras":{"bngMaterial":{"version":1.5,"activeLayers":2,"baseColorMap":["/b.png",""],"baseColorMapIndex":[0],
			     "colorPaletteMap":["","/mask.png"],"colorPaletteMapIndex":{"2":0},"instanceDiffuse":[false,true],"clearCoatFactor":[0,1]}}},
			  {"name":"car_glass","extras":{"bngMaterial":{"version":1.5,"activeLayers":1,"translucent":"1"}}}],
			 "meshes":[
			  {"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"material":0}]},
			  {"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":4,"material":1}]},
			  {"primitives":[{"attributes":{"POSITION":5},"material":0}]}],
			 "nodes":[{"name":"body","mesh":0},{"name":"window","mesh":1},{"name":"steering_wheel","mesh":2,"translation":[1,2,3]}]}
			""".formatted(binLen, oPos, oNrm, oUv, oIdx, oProp, oPng);
		byte[] jb = json.getBytes(StandardCharsets.UTF_8);
		int jLen = (jb.length + 3) / 4 * 4;
		ByteBuffer out = ByteBuffer.allocate(12 + 8 + jLen + 8 + binLen).order(ByteOrder.LITTLE_ENDIAN);
		out.putInt(0x46546C67).putInt(2).putInt(out.capacity());
		out.putInt(jLen).putInt(0x4E4F534A).put(jb);
		for (int i = jb.length; i < jLen; i++) {
			out.put((byte) ' ');
		}
		out.putInt(binLen).putInt(0x004E4942).put(bin.array(), 0, binLen);
		return out.array();
	}

	@Test
	void readsTheSharedFlexmeshVerticesAndSkipsProps() {
		CarModel m = CarModel.read(sampleGlb());
		assertEquals(4, m.vertexCount());
		assertArrayEquals(new float[] {1, 1, 0}, new float[] {m.positions[6], m.positions[7], m.positions[8]});
		assertArrayEquals(new float[] {1, 1}, new float[] {m.uvs[4], m.uvs[5]});
		assertEquals(2, m.parts.size());
		assertEquals("body", m.parts.get(0).mesh());
		assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3}, m.parts.get(0).indices());
		assertArrayEquals(new int[] {1, 2, 3}, m.parts.get(1).indices());
	}

	@Test
	void eachVertexBelongsToTheFirstPartThatUsesIt() {
		CarModel m = CarModel.read(sampleGlb());
		assertArrayEquals(new String[] {"body", "body", "body", "body"}, m.vertexPart);
	}

	@Test
	void materialsKnowTheirImageAndGlass() {
		CarModel m = CarModel.read(sampleGlb());
		assertEquals(0, m.materials.get(0).image());
		assertFalse(m.materials.get(0).glass());
		assertEquals(-1, m.materials.get(1).image());
		assertTrue(m.materials.get(1).glass());
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, m.images.get(0));
	}

	@Test
	void materialsCarryBeamngsLayers() {
		CarModel m = CarModel.read(sampleGlb());
		BngMaterial body = m.materials.get(0).bng();
		assertEquals(2, body.layers());
		assertEquals(0, body.layer(0).baseImage());
		assertEquals(0, body.layer(1).paletteImage());
		assertEquals(1, body.paletteLayer());
		assertTrue(m.materials.get(1).bng().translucent());
		assertEquals(8, m.uvs2.length);   // no TEXCOORD_1 in the sample: zeros, one pair per vertex
	}

	@Test
	void trianglesNamingMissingVerticesAreDropped() {
		// the window's triangle (1, 2, 3) becomes (1, 2, 9): vertex 9 doesn't exist
		byte[] glb = sampleGlb();
		java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(glb).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		int jsonLen = b.getInt(12);
		int bin = 20 + jsonLen + 8;
		int oIdx = 48 + 48 + 32;            // after positions, normals and UVs
		b.putInt(bin + oIdx + 8 * 4, 9);     // 9th index = third index of the window's triangle
		CarModel m = CarModel.read(glb);
		assertEquals(0, m.parts.get(1).indices().length);
		assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3}, m.parts.get(0).indices());
	}

	@Test
	void normalsTurnFromBeamngAxesLikeTheVertices() {
		// BeamNG up (z) becomes glTF / Minecraft up (y); BeamNG forward-left (y) becomes -z
		float[] n = {0, 0, 1, 0, 1, 0, 1, 0, 0};
		CarModel.bngNormalsToGltf(n);
		org.junit.jupiter.api.Assertions.assertArrayEquals(new float[] {0, 1, 0, 0, 0, -1, 1, 0, 0}, n, 1e-6F);
	}

	@Test
	void rejectsSomethingThatIsNotAGlb() {
		assertThrows(IllegalArgumentException.class, () -> CarModel.read(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}));
		assertNull(null);
	}

	@Test
	void pngsAreToldFromTheJpegsModsShip() {
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
		byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};
		assertTrue(CarModel.isPng(png));
		assertFalse(CarModel.isPng(jpeg));
		assertFalse(CarModel.isPng(new byte[] {(byte) 0x89, 'P', 'N'}));
		assertFalse(CarModel.isPng(null));
	}
}
