package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

class CarPropsTest {
	// Accessor 0: the flexmeshes' shared positions (not a prop). Accessors 1-3: one prop triangle,
	// its normals (BeamNG axes: up is z) and indices. The prop's node "steering" hangs under
	// "dash", which is moved 1 along x; "steering" itself is turned 90 degrees about y and moved 2 up.
	private static final String GLTF = """
		{"scene":0,"scenes":[{"nodes":[0,2]}],
		 "nodes":[{"name":"dash","translation":[1,0,0],"children":[1]},
		          {"name":"steering","mesh":1,"translation":[0,2,0],"rotation":[0,0.70710678,0,0.70710678]},
		          {"name":"body","mesh":0}],
		 "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":3,"material":0}]},
		           {"primitives":[{"attributes":{"POSITION":1,"NORMAL":2},"indices":3,"material":1}]}]}
		""";

	private static final Map<Integer, float[]> DATA = Map.of(
		1, new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1},
		2, new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1});

	private static CarProps read() {
		return read(GLTF);
	}

	private static CarProps read(String gltf) {
		JsonObject gl = JsonParser.parseString(gltf).getAsJsonObject();
		return CarProps.read(gl, (acc, comps) -> DATA.get(acc).clone(), acc -> new int[] {0, 1, 2}, 0, 100);
	}

	@Test
	void aMirroredPropKeepsItsFrontFaceOutward() {
		// a right mirror made from the left one by scale -1: glTF says the winding flips with it
		CarProps p = read(GLTF.replace("\"mesh\":1,", "\"mesh\":1,\"scale\":[-1,1,1],"));
		assertArrayEquals(new int[] {100, 102, 101}, p.parts().get(0).indices());
	}

	@Test
	void aPropIsPlacedByItsNodeAndItsParents() {
		CarProps p = read();
		assertEquals(3, p.count());
		// (1, 0, 0) turned 90 degrees about y is (0, 0, -1); then up 2, then along x 1
		assertArrayEquals(new float[] {1, 2, -1, 1, 3, 0, 2, 2, 0}, p.pos(), 1e-5F);
		assertEquals("steering", p.part()[0]);
	}

	@Test
	void itsNormalsTurnFromBeamngAxesAndWithTheNode() {
		// BeamNG up (0, 0, 1) is glTF up (0, 1, 0), which a turn about y leaves alone
		assertArrayEquals(new float[] {0, 1, 0, 0, 1, 0, 0, 1, 0}, read().nrm(), 1e-5F);
	}

	@Test
	void itsTrianglesComeAfterTheFlexmeshVertices() {
		CarProps p = read();
		assertEquals(1, p.parts().size());
		assertArrayEquals(new int[] {100, 101, 102}, p.parts().get(0).indices());
		assertEquals(1, p.parts().get(0).material());
	}
}
