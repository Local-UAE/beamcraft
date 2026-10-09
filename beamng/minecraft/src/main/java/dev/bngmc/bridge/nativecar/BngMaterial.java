package dev.bngmc.bridge.nativecar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.function.IntUnaryOperator;

/**
 * A BeamNG material as the glTF exporter records it in extras.bngMaterial (util/export.lua,
 * _exportMaterial), completed with the live values from the side file (withSetup). BeamNG's v1.5
 * materials stack up to four layers; a car body is typically layer 1 = a base texture (e.g. a black
 * roof skin) and layer 2 = the paint: instanceDiffuse on, a colour palette mask whose red/green/blue
 * pick the car's paint colours 1/2/3, and an opacity mask saying where the paint covers the base.
 * Each layer has its own maps and factors, blended by the upper layer's opacity
 * (shaders/common/material/shadergen/defaultMat.hlsl:212-424). Per-layer fields are arrays; texture
 * slots come as "&lt;map&gt;Index" either as an array (one per layer) or as an object keyed by the
 * 1-based layer. Image indices here are glTF image indices. The bottom layer's opacity (factor and
 * map) is the material's own, how see-through its glass is; legacy: a v1 material, whose glass takes
 * its alpha from the colour and the base map instead. Pure Java, unit-tested.
 */
public record BngMaterial(String name, Layer[] layerData, int aoImage, int metalImage, float metallic, float clearCoat, boolean translucent,
						  float alphaRef, boolean invisible, boolean doubleSided, boolean legacy) {
	/** Map slots of a layer, in the order of MAP_KEYS. */
	public static final int BASE = 0, PALETTE = 1, OPACITY = 2, METALLIC = 3, ROUGHNESS = 4, AO = 5, CLEAR_COAT = 6, NORMAL = 7;
	/** Names of the map slots in the side file (carexport.lua MAPS). */
	public static final String[] MAP_KEYS = {"base", "palette", "opacity", "metallic", "roughness", "ao", "clearCoat", "normal"};
	/** The exporter's names of the map slots (util/export.lua _exportMaterial). */
	private static final String[] GLTF_MAPS = {"baseColorMap", "colorPaletteMap", "opacityMap", "metallicMap", "roughnessMap", "ambientOcclusionMap",
		"clearCoatMap", "normalMap"};
	private static final String[] GLTF_UV1 = {"diffuseMapUseUV", "colorPaletteMapUseUV", "opacityMapUseUV", "metallicMapUseUV", "roughnessMapUseUV",
		"ambientOcclusionMapUseUV", "clearCoatMapUseUV", "normalMapUseUV"};
	/** What a palette layer takes from the paint (the editor's palette checkboxes), in this order. */
	public static final int P_BASE = 0, P_ROUGHNESS = 1, P_METALLIC = 2, P_CLEAR_COAT = 3, P_CLEAR_COAT_ROUGHNESS = 4;
	public static final String[] PALETTE_KEYS = {"base", "roughness", "metallic", "clearCoat", "clearCoatRoughness"};
	/** Factor slots of a layer. */
	public static final int F_METALLIC = 0, F_ROUGHNESS = 1, F_CLEAR_COAT = 2, F_CLEAR_COAT_ROUGHNESS = 3, F_OPACITY = 4, F_NORMAL_STRENGTH = 5;

	/**
	 * One layer: an image per map slot (-1 when absent), which slots use UV1, the base colour and
	 * factors, and what the paint changes. How BeamNG paints a layer (defaultMat.hlsl:249-266):
	 * with a palette mask, the mask picks the paint where the palette switches say; without one,
	 * instanceDiffuse paints the whole layer with paint 1. baseMissing: the layer names a base map
	 * the export doesn't carry (BeamNG couldn't load it either; an empty texture samples black).
	 */
	public record Layer(int[] maps, boolean[] uv1, float[] baseColor, float[] factors, boolean instanceDiffuse, boolean[] palette,
						boolean baseMissing) {
		public int baseImage() {
			return maps[BASE];
		}

		public int paletteImage() {
			return maps[PALETTE];
		}

		public int opacityImage() {
			return maps[OPACITY];
		}

		public boolean paletteUv1() {
			return uv1[PALETTE];
		}

		public boolean paletteBaseColor() {
			return palette[P_BASE];
		}

		public boolean painted() {
			return paletteImage() >= 0 ? paletteBaseColor() : instanceDiffuse;
		}

		Layer withBaseColor(float[] c) {
			return new Layer(maps, uv1, c, factors, instanceDiffuse, palette, baseMissing);
		}

		Layer withPaletteBaseColor(boolean on) {
			boolean[] p = palette.clone();
			p[P_BASE] = on;
			return new Layer(maps, uv1, baseColor, factors, instanceDiffuse, p, baseMissing);
		}

		/** This layer with BeamNG's live values (NaN factors and a missing base colour keep the current ones). */
		Layer withSetup(NativeExport.LayerSetup s) {
			float[] f = factors.clone();
			float[] live = {s.metallic(), s.roughness(), s.clearCoat(), s.clearCoatRoughness(), s.opacity(), s.normalStrength()};
			for (int i = 0; i < f.length; i++) {
				if (!Float.isNaN(live[i])) {
					f[i] = live[i];
				}
			}
			// a map named "@..." is one BeamNG draws itself (a number plate, a screen): not missing, the
			// layer's own colour stands in for it
			boolean missing = s.maps()[BASE] != null && !s.maps()[BASE].isEmpty() && !s.maps()[BASE].startsWith("@") && maps[BASE] < 0;
			return new Layer(maps, s.uv1().clone(), s.baseColor() != null ? s.baseColor() : baseColor, f, s.instanceDiffuse(), s.palette().clone(),
				missing);
		}
	}

	public int layers() {
		return layerData.length;
	}

	public Layer layer(int i) {
		return layerData[i];
	}

	/** The same material with these per-layer base colours (null entries keep the current one). */
	public BngMaterial withBaseColors(float[][] colors) {
		Layer[] out = layerData.clone();
		for (int i = 0; i < out.length && i < colors.length; i++) {
			if (colors[i] != null) {
				out[i] = out[i].withBaseColor(colors[i]);
			}
		}
		return new BngMaterial(name, out, aoImage, metalImage, metallic, clearCoat, translucent, alphaRef, invisible, doubleSided, legacy);
	}

	/** The same material with BeamNG's per-layer paletteBaseColor (read from the game, side file). */
	public BngMaterial withPaletteBaseColor(boolean[] on) {
		Layer[] out = layerData.clone();
		for (int i = 0; i < out.length && i < on.length; i++) {
			out[i] = out[i].withPaletteBaseColor(on[i]);
		}
		return new BngMaterial(name, out, aoImage, metalImage, metallic, clearCoat, translucent, alphaRef, invisible, doubleSided, legacy);
	}

	/** The same material with every layer as BeamNG renders it (side file "layers"). */
	public BngMaterial withSetup(NativeExport.LayerSetup[] setups) {
		Layer[] out = layerData.clone();
		for (int i = 0; i < out.length && i < setups.length; i++) {
			if (setups[i] != null) {
				out[i] = out[i].withSetup(setups[i]);
			}
		}
		return new BngMaterial(name, out, aoImage, metalImage, metallic, clearCoat, translucent, alphaRef, invisible, doubleSided, legacy);
	}

	/** Index of the layer that carries a palette mask, or -1. */
	public int paletteLayer() {
		for (int i = 0; i < layerData.length; i++) {
			if (layerData[i].paletteImage() >= 0) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * @param ex           extras.bngMaterial, or null
	 * @param gltfBaseImage image of the glTF material's baseColorTexture, or -1 (used for old-style materials)
	 * @param textureImage glTF texture index -> image index
	 */
	public static BngMaterial parse(String name, JsonObject ex, int gltfBaseImage, IntUnaryOperator textureImage) {
		boolean invisible = "invis".equalsIgnoreCase(name);
		if (ex == null || ex.size() == 0) {
			int[] maps = noMaps();
			maps[BASE] = gltfBaseImage;
			return new BngMaterial(name, new Layer[] {plainLayer(maps, white(), false)}, -1, -1, 0, 0, false, 0, invisible, true, true);
		}
		float version = num(ex.get("version"), 1);
		int layers = version >= 1.5F ? Math.max(1, Math.min(4, (int) num(ex.get("activeLayers"), 1))) : 1;
		Layer[] out = new Layer[layers];
		for (int l = 0; l < layers; l++) {
			int[] maps = noMaps();
			boolean[] uv1 = new boolean[MAP_KEYS.length];
			for (int s = 0; s < maps.length; s++) {
				maps[s] = image(ex, GLTF_MAPS[s] + "Index", GLTF_MAPS[s], l, textureImage);
				uv1[s] = "1".equals(perLayerStr(ex.get(GLTF_UV1[s]), l));
			}
			if (version < 1.5F) {
				maps[BASE] = gltfBaseImage;
			}
			// BeamNG's own defaults until the side file says otherwise (the palette switches aren't
			// exported; paletteBaseColor is on unless set, the Moonhawk's body sets nothing and is painted)
			float[] factors = {perLayer(ex.get("metallicFactor"), l, 0), perLayer(ex.get("roughnessFactor"), l, 1),
				perLayer(ex.get("clearCoatFactor"), l, 0), perLayer(ex.get("clearCoatRoughnessFactor"), l, 0), perLayer(ex.get("opacityFactor"), l, 1),
				perLayer(ex.get("normalMapStrength"), l, 1)};
			boolean[] palette = new boolean[PALETTE_KEYS.length];
			java.util.Arrays.fill(palette, true);
			out[l] = new Layer(maps, uv1, color(ex.get("baseColorFactor"), l), factors, bool(ex.get("instanceDiffuse"), l), palette, false);
		}
		float metallic = 0, clear = 0;
		for (int l = 0; l < layers; l++) {
			metallic = Math.max(metallic, out[l].factors()[F_METALLIC]);
			clear = Math.max(clear, out[l].factors()[F_CLEAR_COAT]);
		}
		// "translucent" with the blend op "None" is drawn solid: BeamNG's material editor shows that pair as
		// alpha blend mode None (editor/materialEditor.lua:2349). The Gallardo mod marks its body paint so,
		// and drawn as glass it came out see-through
		String blendOp = str(ex.get("translucentBlendOp"));
		boolean translucent = "1".equals(str(ex.get("translucent"))) && !"None".equalsIgnoreCase(blendOp);
		float alphaRef = num(ex.get("alphaRef"), 0) / 255F;
		// BeamNG's own flag (the exporter's glTF doubleSided is true for every material, util/export.lua:270
		// against :800); single-sided faces are culled from behind, as BeamNG does: from inside a seat's
		// headrest, where the M2's driver camera sits, it is invisible
		boolean doubleSided = !"0".equals(str(ex.get("doubleSided")));
		return new BngMaterial(name, out, out[0].maps()[AO], out[0].maps()[METALLIC], metallic, clear, translucent, alphaRef, invisible, doubleSided,
			version < 1.5F);
	}

	private static int[] noMaps() {
		int[] m = new int[MAP_KEYS.length];
		java.util.Arrays.fill(m, -1);
		return m;
	}

	private static Layer plainLayer(int[] maps, float[] baseColor, boolean instanceDiffuse) {
		boolean[] palette = new boolean[PALETTE_KEYS.length];
		java.util.Arrays.fill(palette, true);
		return new Layer(maps, new boolean[MAP_KEYS.length], baseColor, new float[] {0, 1, 0, 0, 1, 1}, instanceDiffuse, palette, false);
	}

	private static float[] white() {
		return new float[] {1, 1, 1, 1};
	}

	/** Texture slot of layer l: "&lt;map&gt;Index" as [t0, t1...] or {"1": t0, "2": t1}, if "&lt;map&gt;"[l] names a file. */
	private static int image(JsonObject ex, String indexKey, String mapKey, int l, IntUnaryOperator textureImage) {
		JsonElement maps = ex.get(mapKey);
		if (maps != null && maps.isJsonArray() && (l >= maps.getAsJsonArray().size() || str(maps.getAsJsonArray().get(l)).isEmpty())) {
			return -1;
		}
		JsonElement idx = ex.get(indexKey);
		JsonElement t = null;
		if (idx != null && idx.isJsonArray() && l < idx.getAsJsonArray().size()) {
			t = idx.getAsJsonArray().get(l);
		} else if (idx != null && idx.isJsonObject()) {
			t = idx.getAsJsonObject().get(String.valueOf(l + 1));
		}
		if (t == null || !t.isJsonPrimitive() || !t.getAsJsonPrimitive().isNumber()) {
			return -1;
		}
		return textureImage.applyAsInt(t.getAsInt());
	}

	private static String perLayerStr(JsonElement e, int l) {
		if (e != null && e.isJsonArray() && l < e.getAsJsonArray().size()) {
			JsonElement v = e.getAsJsonArray().get(l);
			return v.isJsonPrimitive() ? v.getAsString() : "";
		}
		return "";
	}

	private static boolean bool(JsonElement e, int l) {
		if (e != null && e.isJsonArray() && l < e.getAsJsonArray().size()) {
			JsonElement v = e.getAsJsonArray().get(l);
			return v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
		}
		return false;
	}

	private static float perLayer(JsonElement e, int l, float def) {
		if (e != null && e.isJsonArray() && l < e.getAsJsonArray().size()) {
			return num(e.getAsJsonArray().get(l), def);
		}
		return def;
	}

	private static float[] color(JsonElement e, int l) {
		if (e != null && e.isJsonArray() && l < e.getAsJsonArray().size() && e.getAsJsonArray().get(l).isJsonArray()) {
			JsonArray c = e.getAsJsonArray().get(l).getAsJsonArray();
			float[] out = white();
			for (int i = 0; i < 4 && i < c.size(); i++) {
				out[i] = num(c.get(i), 1);
			}
			return out;
		}
		return white();
	}

	private static float num(JsonElement e, float def) {
		if (e == null || !e.isJsonPrimitive()) {
			return def;
		}
		try {
			return e.getAsFloat();
		} catch (NumberFormatException ex) {
			return def;
		}
	}

	private static String str(JsonElement e) {
		return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
	}
}
