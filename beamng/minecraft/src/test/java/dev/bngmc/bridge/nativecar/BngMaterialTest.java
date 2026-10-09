package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class BngMaterialTest {
	/** Texture index i is image i + 100, to tell textures and images apart. */
	private static BngMaterial parse(String name, String extrasJson) {
		JsonObject ex = extrasJson == null ? null : JsonParser.parseString(extrasJson).getAsJsonObject();
		return BngMaterial.parse(name, ex, -1, t -> t + 100);
	}

	// Shapes copied from the Scintilla's export (util/export.lua extras.bngMaterial).
	private static final String PAINTED_BODY = """
		{"version":1.5,"activeLayers":2,"alphaRef":"1","translucent":"0",
		 "baseColorMap":["/v/main_b.color.png",""],"baseColorMapIndex":[7],
		 "colorPaletteMap":["","/v/main_mask.color.png"],"colorPaletteMapIndex":{"2":12},
		 "opacityMap":["","/v/main_c.data.png"],"opacityMapIndex":{"2":9},
		 "ambientOcclusionMap":["/v/main_ao.data.png","/v/main_ao.data.png"],"ambientOcclusionMapIndex":[11,11],
		 "metallicMap":["/v/main_m.data.png",""],"metallicMapIndex":[8],
		 "instanceDiffuse":[false,true],"metallicFactor":[1,1],"clearCoatFactor":[0,1]}
		""";

	@Test
	void aPaintedBodyHasABaseLayerAndAPaintLayer() {
		BngMaterial m = parse("car_main.skin", PAINTED_BODY);
		assertEquals(2, m.layers());
		assertEquals(107, m.layer(0).baseImage());
		assertFalse(m.layer(0).instanceDiffuse());
		assertTrue(m.layer(1).instanceDiffuse());
		assertEquals(-1, m.layer(1).baseImage());
		assertEquals(112, m.layer(1).paletteImage());
		assertEquals(109, m.layer(1).opacityImage());
		assertEquals(111, m.aoImage());
		assertEquals(108, m.metalImage());
		assertEquals(1.0F, m.clearCoat(), 1e-6);
		assertFalse(m.translucent());
		assertFalse(m.invisible());
	}

	// The Moonhawk's body (vehicles/moonhawk/main.materials.json): the paint is layer 1, marked only by
	// its palette mask (nullcolormaskR: all paint 1), no instanceDiffuse; layer 2 is trim over it.
	private static final String MOONHAWK_BODY = """
		{"version":1.5,"activeLayers":2,
		 "baseColorMap":["","/vehicles/moonhawk/moonhawk_main_b.color.png"],"baseColorMapIndex":{"2":2},
		 "colorPaletteMap":["/vehicles/common/nullcolormaskR.color.png",""],"colorPaletteMapIndex":[6],
		 "colorPaletteMapUseUV":["0","1"],"opacityMapIndex":{"2":1},
		 "instanceDiffuse":[false,false]}
		""";

	@Test
	void aPaletteMaskPaintsItsLayerWithoutInstanceDiffuse() {
		BngMaterial m = parse("moonhawk_main", MOONHAWK_BODY);
		assertTrue(m.layer(0).painted());
		assertFalse(m.layer(1).painted());
		assertEquals(0, m.paletteLayer());
		assertFalse(m.layer(0).paletteUv1());
	}

	@Test
	void paletteBaseColorOffFromBeamngLeavesTheLayerUnpainted() {
		BngMaterial m = parse("moonhawk_main", MOONHAWK_BODY).withPaletteBaseColor(new boolean[] {false, true});
		assertFalse(m.layer(0).painted());
	}

	@Test
	void instanceDiffuseWithoutAMaskPaintsTheWholeLayer() {
		BngMaterial m = parse("plain_paint", "{\"version\":1.5,\"activeLayers\":1,\"instanceDiffuse\":[true]}");
		assertTrue(m.layer(0).painted());
		assertEquals(-1, m.paletteLayer());
	}

	@Test
	void thePaletteCanUseTheSecondUvSet() {
		BngMaterial m = parse("decal", """
			{"version":1.5,"activeLayers":1,"colorPaletteMap":["/v/mask.color.png"],"colorPaletteMapIndex":[3],"colorPaletteMapUseUV":["1"]}
			""");
		assertTrue(m.layer(0).paletteUv1());
	}

	@Test
	void baseColoursFromBeamngReplaceTheDefaultWhite() {
		BngMaterial m = parse("car_carbon", "{\"version\":1.5,\"activeLayers\":2}").withBaseColors(new float[][] {{0.05F, 0.05F, 0.05F, 1}, null});
		assertEquals(0.05F, m.layer(0).baseColor()[0], 1e-6);
		assertEquals(1.0F, m.layer(1).baseColor()[0], 1e-6);
	}

	@Test
	void anUntexturedSinglePartIsNotPainted() {
		BngMaterial m = parse("car_carbon", """
			{"version":1.5,"activeLayers":1,"baseColorMap":[""],"instanceDiffuse":[false],"metallicFactor":[1]}
			""");
		assertEquals(1, m.layers());
		assertEquals(-1, m.layer(0).baseImage());
		assertFalse(m.layer(0).instanceDiffuse());
		assertEquals(1.0F, m.metallic(), 1e-6);
	}

	@Test
	void glassIsTranslucentAndCutoutsKeepTheirThreshold() {
		assertTrue(parse("car_glass", "{\"version\":1.5,\"activeLayers\":1,\"translucent\":\"1\"}").translucent());
		assertEquals(77 / 255F, parse("grille", "{\"version\":1.5,\"activeLayers\":1,\"alphaRef\":\"77\"}").alphaRef(), 1e-6);
	}

	@Test
	void theInvisibleHelperMaterialIsNotDrawn() {
		assertTrue(parse("invis", "{\"version\":1,\"instanceDiffuse\":[false,false,false],\"colorMap\":[\"\",\"\",\"\"]}").invisible());
	}

	@Test
	void anOldStyleMaterialUsesTheGltfTextureAndItsPaintFlag() {
		BngMaterial m = BngMaterial.parse("old_paint", JsonParser.parseString("{\"version\":1,\"instanceDiffuse\":[true]}").getAsJsonObject(), 5, t -> t + 100);
		assertEquals(1, m.layers());
		assertEquals(5, m.layer(0).baseImage());
		assertTrue(m.layer(0).instanceDiffuse());
	}

	// The M3 CSL's paint (RRpbrPaintPrimary), as its export recorded it, and its live paint layer.
	private static final String M3_PAINT = """
		{"version":1.5,"activeLayers":2,"alphaRef":"1",
		 "baseColorMap":["/vehicles/common/RRpbrPaint/RR_Paint_PBR_bcolor.dds",""],"baseColorMapIndex":[1],
		 "metallicMap":["/vehicles/common/RRpbrPaint/RR_Paint_PBR_mdata.dds",""],"metallicMapIndex":[2],
		 "roughnessMap":["/vehicles/common/RRpbrPaint/RR_Paint_PBR_rdata.dds",""],"roughnessMapIndex":[7],
		 "normalMap":["vehicles/common/RRpbrPaint/RR_Paint_PBR_n.dds",""],"normalMapIndex":[0],
		 "colorPaletteMap":["","/vehicles/common/RRpbrPaint/RR_Paint_PBR_skin_metallic_uv1color.dds"],"colorPaletteMapIndex":{"2":6},
		 "colorPaletteMapUseUV":["0","1"],
		 "opacityMap":["","/vehicles/common/RRpbrPaint/RR_Paint_PBR_skin_odata.dds"],"opacityMapIndex":{"2":3},
		 "clearCoatMap":["","/vehicles/common/RRpbrPaint/RR_Paint_PBR_ccdata.dds"],"clearCoatMapIndex":{"2":4},
		 "instanceDiffuse":[false,true],"metallicFactor":[1,1],"clearCoatFactor":[0,1],"clearCoatRoughnessFactor":[1,1]}
		""";

	private static NativeExport.LayerSetup live(float roughness, boolean paletteRoughness, String baseMap) {
		boolean[] palette = {true, paletteRoughness, true, true, true};
		boolean[] uv1 = new boolean[BngMaterial.MAP_KEYS.length];
		uv1[BngMaterial.PALETTE] = true;
		String[] maps = new String[BngMaterial.MAP_KEYS.length];
		java.util.Arrays.fill(maps, "");
		maps[BngMaterial.BASE] = baseMap;
		return new NativeExport.LayerSetup(new float[] {1, 1, 1, 1}, 1, roughness, 1, 0, 1, 1, true, palette, uv1, maps);
	}

	@Test
	void everyMapOfEveryLayerIsKept() {
		BngMaterial m = parse("RRpbrPaintPrimary", M3_PAINT);
		BngMaterial.Layer base = m.layer(0), paint = m.layer(1);
		assertEquals(101, base.maps()[BngMaterial.BASE]);
		assertEquals(102, base.maps()[BngMaterial.METALLIC]);
		assertEquals(107, base.maps()[BngMaterial.ROUGHNESS]);
		assertEquals(100, base.maps()[BngMaterial.NORMAL]);
		assertEquals(-1, paint.maps()[BngMaterial.METALLIC]);   // the paint layer's metallic is its factor and the paint's
		assertEquals(106, paint.paletteImage());
		assertEquals(103, paint.opacityImage());
		assertEquals(104, paint.maps()[BngMaterial.CLEAR_COAT]);
		assertTrue(paint.paletteUv1());
		assertEquals(1F, paint.factors()[BngMaterial.F_CLEAR_COAT]);
	}

	@Test
	void theLiveSetupFillsWhatTheExportLeavesOut() {
		BngMaterial m = parse("RRpbrPaintPrimary", M3_PAINT).withSetup(new NativeExport.LayerSetup[] {null, live(0.3F, false, "")});
		BngMaterial.Layer paint = m.layer(1);
		assertEquals(0.3F, paint.factors()[BngMaterial.F_ROUGHNESS]);
		assertFalse(paint.palette()[BngMaterial.P_ROUGHNESS]);
		assertTrue(paint.palette()[BngMaterial.P_METALLIC]);
		assertEquals(106, paint.paletteImage());   // images still come from the export
		assertFalse(paint.baseMissing());
		assertEquals(101, m.layer(0).baseImage());   // a layer without live data stays as exported
	}

	@Test
	void aBaseMapBeamngCouldntLoadIsMissingNotWhite() {
		// the M3's tyres: BeamNG names cup2_275_dif_clean, can't load it ("non pow2 size") and
		// draws them black; the export has no image for it
		BngMaterial tyre = parse("sdd_g80_tire_ps", """
			{"version":1.5,"activeLayers":1,"baseColorMap":["/vehicles/sdd_g80/textures/cup2_275_dif_clean.png"]}
			""").withSetup(new NativeExport.LayerSetup[] {live(1, true, "/vehicles/sdd_g80/textures/cup2_275_dif_clean.png")});
		assertEquals(-1, tyre.layer(0).baseImage());
		assertTrue(tyre.layer(0).baseMissing());
	}

	@Test
	void beamngsOwnDoubleSidedFlagDecidesCulling() {
		// the M2's seat leather: single-sided in BeamNG, though the glTF says doubleSided for everything
		assertFalse(parse("g87st_Interior_Zone1", "{\"version\":1.5,\"activeLayers\":1,\"doubleSided\":\"0\"}").doubleSided());
		assertTrue(parse("RRpbrPaintPrimary", M3_PAINT.replace("\"alphaRef\":\"1\"", "\"alphaRef\":\"1\",\"doubleSided\":\"1\"")).doubleSided());
		assertTrue(BngMaterial.parse("plain", null, 3, t -> t).doubleSided());   // nothing known: drawn from both sides
	}

	@Test
	void noExtrasGivesAPlainMaterialWithTheGltfTexture() {
		BngMaterial m = BngMaterial.parse("plain", null, 3, t -> t);
		assertEquals(1, m.layers());
		assertEquals(3, m.layer(0).baseImage());
		assertFalse(m.invisible());
	}

	// BeamNG's own glass (vehicles/bastion): see-through by its opacity map, on the bottom layer
	private static final String BASTION_GLASS = """
		{"version":1.5,"activeLayers":1,"translucent":"1",
		 "baseColorMap":["vehicles/bastion/bastion_glass_b.color.png"],"baseColorMapIndex":[3],
		 "opacityMap":["vehicles/bastion/bastion_glass_o.data.png"],"opacityMapIndex":[4]}
		""";

	@Test
	void glassKeepsItsOpacityMapOnTheBottomLayer() {
		BngMaterial m = parse("bastion_glass", BASTION_GLASS);
		assertEquals(104, m.layer(0).opacityImage());
		assertTrue(m.translucent());
		assertFalse(m.legacy());
		assertTrue(parse("mirror", "{\"version\":1,\"translucent\":\"1\"}").legacy());
		assertTrue(parse("plain", null).legacy());
	}

	@Test
	void translucentWithBlendOpNoneIsSolid() {
		assertFalse(parse("gallardo_paint", "{\"version\":1.5,\"translucent\":\"1\",\"translucentBlendOp\":\"None\"}").translucent());
		assertTrue(parse("gallardo_glass", "{\"version\":1.5,\"translucent\":\"1\",\"translucentBlendOp\":\"PreMulAlpha\"}").translucent());
		assertTrue(parse("old_glass", "{\"version\":1,\"translucent\":\"1\"}").translucent());
	}
}
