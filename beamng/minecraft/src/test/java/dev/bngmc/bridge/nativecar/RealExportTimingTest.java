package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Times loading, binding and bending a real BeamNG export. Runs only when BNG_EXPORT_GLB points at
 * a .glb written by the vexport message (its .json side file next to it); skipped otherwise.
 */
class RealExportTimingTest {
	@Test
	void realExportLoadsBindsAndBendsInTime() throws Exception {
		String glb = System.getenv("BNG_EXPORT_GLB");
		assumeTrue(glb != null && Files.exists(Path.of(glb)), "BNG_EXPORT_GLB not set");
		long t0 = System.nanoTime();
		CarModel m = CarModel.read(Files.readAllBytes(Path.of(glb)));
		long t1 = System.nanoTime();
		JsonObject side = JsonParser.parseString(Files.readString(Path.of(glb.substring(0, glb.length() - 4) + ".json"))).getAsJsonObject();
		float[] nodes = NativeExport.nodesMinecraftAxes(side.getAsJsonArray("p"), NativeExport.unitsPerMetre(side));
		Map<String, int[]> groups = NativeExport.groups(side.getAsJsonObject("groups"));
		FlexBinding b = FlexBinding.bind(m.positions, m.normals, m.vertexPart, groups, nodes);
		long t2 = System.nanoTime();
		float[] pos = new float[m.positions.length], nrm = new float[m.positions.length];
		for (int i = 0; i < 5; i++) {
			b.deform(nodes, pos, nrm);   // warm up
		}
		long t3 = System.nanoTime();
		b.deform(nodes, pos, nrm);
		long t4 = System.nanoTime();
		double maxErr = 0;
		for (int i = 0; i < pos.length; i++) {
			maxErr = Math.max(maxErr, Math.abs(pos[i] - m.positions[i]));
		}
		int unbound = 0;
		for (String p : m.vertexPart) {
			if (p == null || !groups.containsKey(p)) {
				unbound++;
			}
		}
		System.out.printf("REAL EXPORT: %d vertices, %d triangles, %d parts, %d materials, %d images%n", m.vertexCount(), m.triangleCount(),
			m.parts.size(), m.materials.size(), m.images.size());
		System.out.printf("REAL EXPORT: read %.0f ms, bind %.0f ms, one bend %.2f ms, rest-pose error %.4f m, vertices without a node group %d%n",
			(t1 - t0) / 1e6, (t2 - t1) / 1e6, (t4 - t3) / 1e6, maxErr, unbound);
		assertTrue(maxErr < 0.001, "rest pose must reproduce the exported mesh");
	}
}
