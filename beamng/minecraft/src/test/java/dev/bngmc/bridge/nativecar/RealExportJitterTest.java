package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

/**
 * How much a real export's bent mesh shakes when its nodes carry the VMESH stream's rounding:
 * BNG_JITTER_MM per axis (default 5, whole centimetres; 0.5 for millimetres). Runs only when
 * BNG_EXPORT_GLB is set; a diagnostic, not a pass/fail check.
 */
class RealExportJitterTest {
	@Test
	void printsVertexShakeFromRounding() throws Exception {
		float half = Float.parseFloat(System.getenv().getOrDefault("BNG_JITTER_MM", "5")) / 1000F;
		String glb = System.getenv("BNG_EXPORT_GLB");
		assumeTrue(glb != null && Files.exists(Path.of(glb)), "BNG_EXPORT_GLB not set");
		CarModel m = CarModel.read(Files.readAllBytes(Path.of(glb)));
		JsonObject side = JsonParser.parseString(Files.readString(Path.of(glb.substring(0, glb.length() - 4) + ".json"))).getAsJsonObject();
		float[] nodes = NativeExport.nodesMinecraftAxes(side.getAsJsonArray("p"), NativeExport.unitsPerMetre(side));
		Map<String, int[]> groups = NativeExport.groups(side.getAsJsonObject("groups"));
		FlexBinding b = FlexBinding.bind(m.positions, m.normals, m.vertexPart, groups, nodes);
		float[] rest = new float[m.positions.length], pos = new float[m.positions.length], nrm = new float[m.positions.length];
		b.deform(nodes, rest, nrm);
		Random r = new Random(1);
		int nv = m.vertexCount();
		float[] worst = new float[nv];
		for (int trial = 0; trial < 20; trial++) {
			float[] noisy = nodes.clone();
			for (int i = 0; i < noisy.length; i++) {
				noisy[i] += (r.nextFloat() - 0.5F) * 2 * half;
			}
			b.deform(noisy, pos, nrm);
			for (int v = 0; v < nv; v++) {
				float dx = pos[v * 3] - rest[v * 3], dy = pos[v * 3 + 1] - rest[v * 3 + 1], dz = pos[v * 3 + 2] - rest[v * 3 + 2];
				worst[v] = Math.max(worst[v], (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
		}
		float[] sorted = worst.clone();
		Arrays.sort(sorted);
		int over5 = 0, over2 = 0;
		for (float w : worst) {
			over5 += w > 0.05F ? 1 : 0;
			over2 += w > 0.02F ? 1 : 0;
		}
		System.out.printf("JITTER %s +-%.1f mm: %d vertices; worst shake median %.1f mm, p90 %.1f mm, p99 %.1f mm, max %.0f mm; over 2 cm: %d, over 5 cm: %d%n",
			Path.of(glb).getFileName(), half * 1000, nv, sorted[nv / 2] * 1000, sorted[nv * 9 / 10] * 1000, sorted[nv * 99 / 100] * 1000, sorted[nv - 1] * 1000,
			over2, over5);
	}
}
