package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeExportTest {
	// Shape of the side file bridge.lua writes for the Scintilla (materials section, trimmed).
	private static final String MATERIALS = """
		{"scintilla_main_carbon":{"aoUv1":true,"baseColorFactor":[[0.05,0.05,0.05,1],false,false,false],
		  "detail":{"file":"C:/bng/temp/mccross/tex/_vehicles_common_carbonfiber_d.color.dds.png","layer":0,
		            "strength":0.89,"scale":[80,40],"uv1":true}},
		 "scintilla_body":{"aoUv1":false,"baseColorFactor":[false,[1,1,1,1],false,false]}}
		""";

	@Test
	void carbonGetsItsDetailMapOnTheSecondUvSet() {
		Map<String, NativeExport.MaterialExtra> m = NativeExport.materialExtras(JsonParser.parseString(MATERIALS).getAsJsonObject());

		NativeExport.MaterialExtra carbon = m.get("scintilla_main_carbon");
		assertTrue(carbon.aoUv1());
		assertTrue(carbon.detailUv1());
		assertTrue(carbon.detailFile().endsWith("carbonfiber_d.color.dds.png"));
		assertEquals(0.89F, carbon.detailStrength(), 1e-6);
		assertEquals(80F, carbon.detailScaleU(), 1e-6);
		assertEquals(40F, carbon.detailScaleV(), 1e-6);
		assertEquals((float) Math.pow(0.05, 2.2), carbon.baseColors()[0][0], 1e-6);   // BeamNG colours are gamma, the shader linear
		assertEquals(1F, carbon.baseColors()[0][3], 0F);                              // alpha as it is
		assertNull(carbon.baseColors()[1]);
	}

	@Test
	void aMaterialWithoutDetailHasNone() {
		NativeExport.MaterialExtra body = NativeExport.materialExtras(JsonParser.parseString(MATERIALS).getAsJsonObject()).get("scintilla_body");

		assertFalse(body.aoUv1());
		assertNull(body.detailFile());
		assertEquals(0F, body.detailStrength(), 1e-6);
		assertNull(body.baseColors()[0]);
		assertEquals(1F, body.baseColors()[1][2], 1e-6);
	}

	@Test
	void noMaterialsSectionMeansNoExtras() {
		assertTrue(NativeExport.materialExtras(null).isEmpty());
	}

	@Test
	void layersAreReadAsCarexportWritesThem() {
		NativeExport.MaterialExtra paint = NativeExport.materialExtras(JsonParser.parseString("""
			{"RRpbrPaintPrimary":{"aoUv1":false,"version":1.5,"activeLayers":2,"layers":[
			  {"baseColorFactor":[0.59,0,0.003,1],"metallic":1,"roughness":1,"instanceDiffuse":false,
			   "maps":{"base":"/v/RR_Paint_PBR_bcolor.dds"},"uv1":{},"palette":{"base":true}},
			  {"baseColorFactor":[1,1,1,1],"metallic":1,"roughness":0.5,"clearCoat":1,"instanceDiffuse":true,
			   "maps":{"palette":"/v/RR_Paint_PBR_skin_metallic_uv1color.dds"},"uv1":{"palette":true},
			   "palette":{"base":true,"metallic":true,"roughness":false}}]}}
			""").getAsJsonObject()).get("RRpbrPaintPrimary");
		assertEquals(2, paint.layers().length);
		NativeExport.LayerSetup top = paint.layers()[1];
		assertTrue(top.instanceDiffuse());
		assertEquals(0.5F, top.roughness(), 1e-6);
		assertTrue(Float.isNaN(top.opacity()));   // not given: BeamNG's value stays
		assertTrue(top.uv1()[BngMaterial.PALETTE]);
		assertTrue(top.palette()[BngMaterial.P_METALLIC]);
		assertFalse(top.palette()[BngMaterial.P_ROUGHNESS]);
		assertEquals("/v/RR_Paint_PBR_skin_metallic_uv1color.dds", top.maps()[BngMaterial.PALETTE]);
		assertEquals("", top.maps()[BngMaterial.BASE]);
		assertEquals((float) Math.pow(0.59, 2.2), paint.layers()[0].baseColor()[0], 1e-6);
	}

	@Test
	void paintDataFallsBackToBeamngsDefaults() {
		float[][] d = NativeExport.paintData(JsonParser.parseString("{\"paintData\":[[0.9,0.1,1,0],false]}").getAsJsonObject());
		assertEquals(0.9F, d[0][0], 1e-6);
		assertEquals(0.1F, d[0][1], 1e-6);
		assertEquals(0.2F, d[1][0], 1e-6);   // createVehiclePaint's metallic default
		assertEquals(0.8F, d[2][2], 1e-6);   // and clear coat
	}
}
