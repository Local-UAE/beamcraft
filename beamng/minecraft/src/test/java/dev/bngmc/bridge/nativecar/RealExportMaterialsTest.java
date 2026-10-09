package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * Prints how a real export's materials come out once the side file's live layers are applied (what
 * NativeCars hands the shader). Runs only when BNG_EXPORT_GLB is set; BNG_MATERIALS picks materials
 * by name (comma separated, default all). A diagnostic, not a pass/fail check.
 */
class RealExportMaterialsTest {
	@Test
	void printsMaterialsAsTheShaderGetsThem() throws Exception {
		String glb = System.getenv("BNG_EXPORT_GLB");
		assumeTrue(glb != null && Files.exists(Path.of(glb)), "BNG_EXPORT_GLB not set");
		String pick = System.getenv().getOrDefault("BNG_MATERIALS", "");
		CarModel m = CarModel.read(Files.readAllBytes(Path.of(glb)));
		JsonObject side = JsonParser.parseString(Files.readString(Path.of(glb.substring(0, glb.length() - 4) + ".json"))).getAsJsonObject();
		Map<String, NativeExport.MaterialExtra> extras = NativeExport.materialExtras(side.getAsJsonObject("materials"));
		for (CarModel.Material mt : m.materials) {
			if (!pick.isEmpty() && !Arrays.asList(pick.split(",")).contains(mt.name())) {
				continue;
			}
			NativeExport.MaterialExtra ex = extras.get(mt.name());
			BngMaterial b = ex != null && ex.layers() != null ? mt.bng().withSetup(ex.layers()) : mt.bng();
			System.out.printf("%s: %d layers, glass %s, side layers %s%n", mt.name(), b.layers(), mt.glass(),
				ex == null || ex.layers() == null ? "none" : String.valueOf(ex.layers().length));
			for (int l = 0; l < b.layers(); l++) {
				BngMaterial.Layer y = b.layer(l);
				System.out.printf("  L%d maps %s uv1 %s base %s factors %s inst %s palette %s missing %s%n", l, Arrays.toString(y.maps()),
					Arrays.toString(y.uv1()), Arrays.toString(y.baseColor()), Arrays.toString(y.factors()), y.instanceDiffuse(),
					Arrays.toString(y.palette()), y.baseMissing());
			}
		}
	}
}
