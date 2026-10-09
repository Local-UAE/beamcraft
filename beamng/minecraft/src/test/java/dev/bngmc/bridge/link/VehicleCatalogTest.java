package dev.bngmc.bridge.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class VehicleCatalogTest {
	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	// Shapes as carselect.lua sends them (measured: 122 models in 4 pages on BeamNG 0.39.4).
	private static final String PAGE1 = """
		{"page":1,"pages":2,"models":[
		 {"k":"scintilla","name":"Scintilla","brand":"Civetta","type":"Car","configs":16,"def":"gts","thumb":"C:/bng/temp/mccross/thumbs/s.jpg"},
		 {"k":"haybale","name":"Bales","brand":"","type":"Prop","configs":6,"def":"square"}]}
		""";
	private static final String PAGE2 = """
		{"page":2,"pages":2,"models":[
		 {"k":"pickup","name":"D-Series","brand":"Gavril","type":"Truck","configs":40,"def":"d15_4wd_A","thumb":"C:/bng/p.jpg"}]}
		""";

	@Test
	void theListAppearsOnlyWhenEveryPageIsIn() {
		VehicleCatalog c = new VehicleCatalog();
		c.acceptModels(json(PAGE1));
		assertNull(c.models());
		c.acceptModels(json(PAGE2));
		assertEquals(3, c.models().size());
		VehicleCatalog.Model s = c.models().get(0);
		assertEquals("Civetta Scintilla", s.title());
		assertEquals(16, s.configs());
		assertEquals("gts", s.defaultConfig());
		assertEquals("", c.models().get(1).thumb());
		assertEquals("Bales", c.models().get(1).title());
	}

	@Test
	void pagesInAnyOrderStillMakeTheList() {
		VehicleCatalog c = new VehicleCatalog();
		c.acceptModels(json(PAGE2.replace("\"page\":2", "\"page\":2")));
		c.acceptModels(json(PAGE1));   // page 1 restarts the list
		assertNull(c.models());
		c.acceptModels(json(PAGE2));
		assertEquals(3, c.models().size());
	}

	@Test
	void configsArePerModel() {
		VehicleCatalog c = new VehicleCatalog();
		c.acceptConfigs(json("""
			{"model":"scintilla","page":1,"pages":1,"configs":[
			 {"k":"gts","name":"GTs (DCT)","def":true,"thumb":"C:/t/gts.jpg"},
			 {"k":"rally","name":"Amateur Rally - Asphalt (Sequential)","def":false}]}
			"""));
		List<VehicleCatalog.Config> list = c.configs("scintilla");
		assertEquals(2, list.size());
		assertTrue(list.get(0).isDefault());
		assertEquals("rally", list.get(1).key());
		assertNull(c.configs("pickup"));
	}

	@Test
	void filterKeepsCarsAndTrucksAndMatchesEveryWord() {
		VehicleCatalog c = new VehicleCatalog();
		c.acceptModels(json(PAGE1.replace("\"pages\":2", "\"pages\":1")));
		List<VehicleCatalog.Model> all = c.models();
		assertEquals(List.of("scintilla"), VehicleCatalog.filter(all, "", false).stream().map(VehicleCatalog.Model::key).toList());
		assertEquals(2, VehicleCatalog.filter(all, "", true).size());
		assertEquals(1, VehicleCatalog.filter(all, "civetta scint", false).size());
		assertEquals(0, VehicleCatalog.filter(all, "civetta pickup", false).size());
		assertEquals(1, VehicleCatalog.filter(all, "BALES", true).size());
	}
}
