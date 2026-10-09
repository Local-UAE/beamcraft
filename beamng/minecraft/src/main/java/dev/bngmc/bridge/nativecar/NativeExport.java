package dev.bngmc.bridge.nativecar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/** Reads the side file the vexport message writes next to a car's .glb (bridge.lua, handlers.vexport). */
public final class NativeExport {
	private NativeExport() {
	}

	/**
	 * Node offsets in whole centimetres, BeamNG world-aligned (x, y, z) per node, as in VMESH, turned
	 * into metres in Minecraft / glTF axes: (x, z, -y).
	 */
	public static float[] nodesMinecraftAxes(JsonArray cm) {
		return nodesMinecraftAxes(cm, 100);
	}

	/** Node offsets in 1/q metre (side file "q"; 100 = cm in older exports) to metres, Minecraft axes. */
	public static float[] nodesMinecraftAxes(JsonArray p, int q) {
		float[] out = new float[p.size()];
		for (int i = 0; i + 2 < p.size(); i += 3) {
			out[i] = p.get(i).getAsFloat() / q;
			out[i + 1] = p.get(i + 2).getAsFloat() / q;
			out[i + 2] = -p.get(i + 1).getAsFloat() / q;
		}
		return out;
	}

	/** The side file's units per metre (1000: mm); 100 when it doesn't say (older exports). */
	public static int unitsPerMetre(JsonObject side) {
		return side.has("q") && side.get("q").isJsonPrimitive() ? Math.max(1, side.get("q").getAsInt()) : 100;
	}

	/** Same, from VMESH's int array. */
	public static void nodesMinecraftAxes(int[] cm, float[] out) {
		for (int i = 0; i + 2 < cm.length && i + 2 < out.length; i += 3) {
			out[i] = cm[i] / 100F;
			out[i + 1] = cm[i + 2] / 100F;
			out[i + 2] = -cm[i + 1] / 100F;
		}
	}

	/**
	 * A colour factor from BeamNG's materials, which are picked in the material editor's colour
	 * picker as they look (editor/materialEditor.lua:1641-1656), in linear light for CarShader. The
	 * Cadillac CT5 mod's black trims are 0.11-0.45 and drew mid grey taken as linear; BeamNG turns its
	 * paint colours to linear the same way (toLinearColor, shadergen.h.hlsl:173-175).
	 */
	static float[] linearColor(float r, float g, float b, float a) {
		return new float[] {lin(r), lin(g), lin(b), a};
	}

	private static float lin(float c) {
		return (float) Math.pow(Math.max(0F, c), 2.2);
	}

	/** Side file "materials": name -> per-layer base colour {r, g, b, a} in linear light (null where BeamNG has none). */
	public static Map<String, float[][]> baseColors(JsonObject materials) {
		Map<String, float[][]> out = new HashMap<>();
		if (materials == null) {
			return out;
		}
		for (Map.Entry<String, JsonElement> e : materials.entrySet()) {
			if (!e.getValue().isJsonObject() || !e.getValue().getAsJsonObject().has("baseColorFactor")
				|| !e.getValue().getAsJsonObject().get("baseColorFactor").isJsonArray()) {
				continue;
			}
			JsonArray layers = e.getValue().getAsJsonObject().getAsJsonArray("baseColorFactor");
			float[][] c = new float[layers.size()][];
			for (int i = 0; i < c.length; i++) {
				if (layers.get(i).isJsonArray() && layers.get(i).getAsJsonArray().size() >= 3) {
					JsonArray v = layers.get(i).getAsJsonArray();
					c[i] = linearColor(v.get(0).getAsFloat(), v.get(1).getAsFloat(), v.get(2).getAsFloat(), v.size() > 3 ? v.get(3).getAsFloat() : 1F);
				}
			}
			out.put(e.getKey(), c);
		}
		return out;
	}

	/**
	 * What the side file adds to a material: per-layer base colours, whether AO uses the second UV
	 * set, a detail map (e.g. carbon weave: a PNG in BeamNG's user folder, tiled scale times) and
	 * the layer it belongs to, and every layer as BeamNG renders it (null in older exports).
	 */
	public record MaterialExtra(float[][] baseColors, boolean aoUv1, String detailFile, float detailStrength, float detailScaleU,
								float detailScaleV, boolean detailUv1, boolean[] paletteBaseColor, int detailLayer, LayerSetup[] layers) {
	}

	/**
	 * One layer of a material, live from BeamNG (carexport.lua layerOf): factors (NaN where BeamNG
	 * gave none), the palette switches (BngMaterial.P_*), which maps use UV1 and the map paths, both
	 * per BngMaterial map slot.
	 */
	public record LayerSetup(float[] baseColor, float metallic, float roughness, float clearCoat, float clearCoatRoughness, float opacity,
							 float normalStrength, boolean instanceDiffuse, boolean[] palette, boolean[] uv1, String[] maps) {
	}

	/** The side file's layers of one material, or null if it has none. */
	static LayerSetup[] layers(JsonObject m) {
		if (!m.has("layers") || !m.get("layers").isJsonArray()) {
			return null;
		}
		JsonArray a = m.getAsJsonArray("layers");
		LayerSetup[] out = new LayerSetup[a.size()];
		for (int i = 0; i < out.length; i++) {
			JsonObject l = a.get(i).isJsonObject() ? a.get(i).getAsJsonObject() : new JsonObject();
			float[] base = null;
			if (l.has("baseColorFactor") && l.get("baseColorFactor").isJsonArray() && l.getAsJsonArray("baseColorFactor").size() >= 3) {
				JsonArray c = l.getAsJsonArray("baseColorFactor");
				base = linearColor(c.get(0).getAsFloat(), c.get(1).getAsFloat(), c.get(2).getAsFloat(), c.size() > 3 ? c.get(3).getAsFloat() : 1F);
			}
			boolean[] palette = new boolean[BngMaterial.PALETTE_KEYS.length];
			for (int p = 0; p < palette.length; p++) {
				palette[p] = flag(l, "palette", BngMaterial.PALETTE_KEYS[p]);
			}
			boolean[] uv1 = new boolean[BngMaterial.MAP_KEYS.length];
			String[] maps = new String[BngMaterial.MAP_KEYS.length];
			for (int s = 0; s < maps.length; s++) {
				uv1[s] = flag(l, "uv1", BngMaterial.MAP_KEYS[s]);
				JsonObject mp = l.has("maps") && l.get("maps").isJsonObject() ? l.getAsJsonObject("maps") : null;
				maps[s] = mp != null && mp.has(BngMaterial.MAP_KEYS[s]) && mp.get(BngMaterial.MAP_KEYS[s]).isJsonPrimitive()
					? mp.get(BngMaterial.MAP_KEYS[s]).getAsString() : "";
			}
			out[i] = new LayerSetup(base, num(l, "metallic"), num(l, "roughness"), num(l, "clearCoat"), num(l, "clearCoatRoughness"),
				num(l, "opacity"), num(l, "normalStrength"), l.has("instanceDiffuse") && l.get("instanceDiffuse").getAsBoolean(), palette, uv1, maps);
		}
		return out;
	}

	private static boolean flag(JsonObject l, String group, String key) {
		if (!l.has(group) || !l.get(group).isJsonObject()) {
			return false;
		}
		JsonElement v = l.getAsJsonObject(group).get(key);
		return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
	}

	private static float num(JsonObject l, String key) {
		JsonElement v = l.get(key);
		return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsFloat() : Float.NaN;
	}

	/**
	 * The driver's view (side file "camera", carexport.lua cameraOf): the driver camera's node, the
	 * nodes that aim it, and its offset along (forward, left, up). Node cids; -1 where absent.
	 */
	public record DriverView(int driver, int ref, int left, int back, int idUp, int idBack, int idRef, float[] offset) {
		/** The node the eye sits on: the driver camera, or the car's ref node without one. */
		public int eyeNode() {
			return driver >= 0 ? driver : ref;
		}
	}

	/** The side file's driver view, or null if it has none (older exports). */
	public static DriverView driverView(JsonObject side) {
		if (!side.has("camera") || !side.get("camera").isJsonObject()) {
			return null;
		}
		JsonObject c = side.getAsJsonObject("camera");
		float[] offset = {0, 0, 0};
		if (c.has("offset") && c.get("offset").isJsonArray()) {
			JsonArray o = c.getAsJsonArray("offset");
			for (int i = 0; i < 3 && i < o.size(); i++) {
				offset[i] = o.get(i).getAsFloat();
			}
		}
		DriverView v = new DriverView(cid(c, "driver"), cid(c, "ref"), cid(c, "left"), cid(c, "back"), cid(c, "idUp"), cid(c, "idBack"),
			cid(c, "idRef"), offset);
		return v.eyeNode() >= 0 ? v : null;
	}

	private static int cid(JsonObject c, String key) {
		JsonElement v = c.get(key);
		return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsInt() : -1;
	}

	/** BeamNG's paint data when the car doesn't say (createVehiclePaint, ge_utils.lua:958-961). */
	private static final float[] DEFAULT_PAINT_DATA = {0.2F, 0.5F, 0.8F, 0F};

	/**
	 * The car's three paints' {metallic, roughness, clear coat, clear coat roughness} (side file
	 * "paintData", veh:getMetallicPaintData()); BeamNG's defaults where missing.
	 */
	public static float[][] paintData(JsonObject side) {
		float[][] out = new float[3][];
		JsonArray a = side.has("paintData") && side.get("paintData").isJsonArray() ? side.getAsJsonArray("paintData") : new JsonArray();
		for (int i = 0; i < 3; i++) {
			out[i] = DEFAULT_PAINT_DATA.clone();
			if (i < a.size() && a.get(i).isJsonArray()) {
				JsonArray d = a.get(i).getAsJsonArray();
				for (int k = 0; k < 4 && k < d.size(); k++) {
					if (d.get(k).isJsonPrimitive() && d.get(k).getAsJsonPrimitive().isNumber()) {
						out[i][k] = d.get(k).getAsFloat();
					}
				}
			}
		}
		return out;
	}

	public static Map<String, MaterialExtra> materialExtras(JsonObject materials) {
		Map<String, MaterialExtra> out = new HashMap<>();
		if (materials == null) {
			return out;
		}
		Map<String, float[][]> colors = baseColors(materials);
		for (Map.Entry<String, JsonElement> e : materials.entrySet()) {
			if (!e.getValue().isJsonObject()) {
				continue;
			}
			JsonObject m = e.getValue().getAsJsonObject();
			boolean aoUv1 = m.has("aoUv1") && m.get("aoUv1").getAsBoolean();
			String file = null;
			float strength = 0, su = 1, sv = 1;
			boolean uv1 = false;
			int detailLayer = 0;
			if (m.has("detail") && m.get("detail").isJsonObject()) {
				JsonObject d = m.getAsJsonObject("detail");
				file = d.has("file") && d.get("file").isJsonPrimitive() ? d.get("file").getAsString() : null;
				strength = d.has("strength") ? d.get("strength").getAsFloat() : 1;
				detailLayer = d.has("layer") && d.get("layer").isJsonPrimitive() ? d.get("layer").getAsInt() : 0;
				if (d.has("scale") && d.get("scale").isJsonArray() && d.getAsJsonArray("scale").size() >= 2) {
					su = d.getAsJsonArray("scale").get(0).getAsFloat();
					sv = d.getAsJsonArray("scale").get(1).getAsFloat();
				}
				uv1 = d.has("uv1") && d.get("uv1").getAsBoolean();
			}
			boolean[] palette = null;
			if (m.has("paletteBaseColor") && m.get("paletteBaseColor").isJsonArray()) {
				JsonArray p = m.getAsJsonArray("paletteBaseColor");
				palette = new boolean[p.size()];
				for (int i = 0; i < palette.length; i++) {
					palette[i] = p.get(i).isJsonPrimitive() && p.get(i).getAsBoolean();
				}
			}
			out.put(e.getKey(), new MaterialExtra(colors.get(e.getKey()), aoUv1, file, strength, su, sv, uv1, palette, detailLayer, layers(m)));
		}
		return out;
	}

	/** Flexbody mesh name -> node indices it follows. */
	public static Map<String, int[]> groups(JsonObject g) {
		Map<String, int[]> out = new HashMap<>();
		if (g == null) {
			return out;
		}
		for (Map.Entry<String, JsonElement> e : g.entrySet()) {
			if (!e.getValue().isJsonArray()) {
				continue;
			}
			JsonArray a = e.getValue().getAsJsonArray();
			int[] ids = new int[a.size()];
			for (int i = 0; i < ids.length; i++) {
				ids[i] = a.get(i).getAsInt();
			}
			out.put(e.getKey(), ids);
		}
		return out;
	}
}
