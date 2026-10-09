package dev.bngmc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class BridgeSettingsTest {
	@Test
	void theSettingsSurviveTheFile() {
		BridgeSettings s = new BridgeSettings(BridgeSettings.Ground.BLOCKS, 3, false, false, 40, 0, false, false, 8.5, 2.0, false, false, true);
		assertEquals(s, BridgeSettings.fromJson(s.toJson()));
	}

	@Test
	void anEmptyOrOldFileGivesTheDefaults() {
		assertEquals(BridgeSettings.DEFAULTS, BridgeSettings.fromJson(new JsonObject()));
	}

	@Test
	void aBrokenValueFallsBackToItsDefaultAndTheRestStay() {
		JsonObject o = JsonParser.parseString("""
			{"ground": "lava", "slopeBlocks": "lots", "seaWater": false, "dentPercent": 999, "chaseDistance": -4}
			""").getAsJsonObject();
		BridgeSettings s = BridgeSettings.fromJson(o);
		assertEquals(BridgeSettings.Ground.SMOOTH, s.ground());
		assertEquals(BridgeSettings.DEFAULTS.slopeBlocks(), s.slopeBlocks());
		assertFalse(s.seaWater());
		assertEquals(BridgeSettings.MAX_PERCENT, s.dentPercent());
		assertEquals(BridgeSettings.MIN_CHASE_DISTANCE, s.chaseDistance(), 0);
	}

	@Test
	void realBlocksHaveNoBlurAndTwoSamplesPerBlock() {
		BridgeSettings s = BridgeSettings.DEFAULTS.withGround(BridgeSettings.Ground.BLOCKS);
		assertEquals(0, s.slopeRadius());
		assertEquals(2, s.ground().samplesPerBlock);
		assertEquals(BridgeSettings.DEFAULTS.slopeBlocks(), BridgeSettings.DEFAULTS.slopeRadius());
	}

	@Test
	void oneSettingChangesByItsName() {
		BridgeSettings s = BridgeSettings.DEFAULTS.with("ground", new JsonPrimitive("blocks"));
		assertEquals(BridgeSettings.Ground.BLOCKS, s.ground());
		assertEquals(BridgeSettings.DEFAULTS.withGround(BridgeSettings.Ground.BLOCKS), s);
		assertEquals(BridgeSettings.DEFAULTS, BridgeSettings.DEFAULTS.with("nonsense", new JsonPrimitive(1)));
	}

	@Test
	void theTerrainLooksAsTheSettingsSay() {
		assertEquals(new TerrainSync.Look(1, 2, true, true), TerrainSync.Look.of(BridgeSettings.DEFAULTS));
		BridgeSettings blocks = BridgeSettings.DEFAULTS.withGround(BridgeSettings.Ground.BLOCKS).with("seaWater", new JsonPrimitive(false))
			.with("openBuildings", new JsonPrimitive(false));
		assertEquals(new TerrainSync.Look(2, 0, false, false), TerrainSync.Look.of(blocks));
	}

	@Test
	void aMissingFileIsWrittenWithTheDefaultsAndAChangeIsSaved() throws Exception {
		Path dir = Files.createTempDirectory("bngbridge-settings");
		Path file = dir.resolve("config").resolve("bngbridge.json");
		try {
			BridgeSettings.load(file);
			assertEquals(BridgeSettings.DEFAULTS, BridgeSettings.get());
			assertTrue(Files.exists(file));
			BridgeSettings.set(BridgeSettings.DEFAULTS.withStatusHud(true));
			BridgeSettings.load(file);
			assertTrue(BridgeSettings.get().statusHud());
			Files.writeString(file, "{ not json");
			BridgeSettings.load(file);
			assertEquals(BridgeSettings.DEFAULTS, BridgeSettings.get());
		} finally {
			BridgeSettings.set(BridgeSettings.DEFAULTS);
			Files.deleteIfExists(file);
			Files.deleteIfExists(file.getParent());
			Files.deleteIfExists(dir);
		}
	}
}
