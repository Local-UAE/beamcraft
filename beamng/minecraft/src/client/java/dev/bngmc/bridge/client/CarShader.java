package dev.bngmc.bridge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import dev.bngmc.bridge.nativecar.BngMaterial;
import dev.bngmc.bridge.nativecar.NativeExport;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The shader Minecraft draws BeamNG cars with (NativeCars). The fragment shader follows BeamNG's
 * own material pipeline (shaders/common/material/shadergen/defaultMat.hlsl:212-424): each layer
 * has a base colour, metallic, roughness, AO, clear coat and normal from its factors and maps; a
 * palette mask paints it with the car's three paints and their metallic, roughness and clear coat
 * (shadergen.h.hlsl:163-192); each upper layer is blended over the ones below by its opacity. Up to
 * two layers are drawn (BeamNG allows four; car bodies use one or two). Lighting is done in linear
 * colour like BeamNG's: Minecraft's sun and sky as the light, a GGX sun highlight, the sky and
 * ground as a reflection that blurs with roughness, and a clear coat on top.
 *
 * <p>The vertex shader bends the car: each vertex arrives as its position and normal in the frame
 * of three nodes (FlexBinding) and is rebuilt in the frame those nodes span now, exactly what
 * FlexBinding.deform does on the CPU (FlexBindingTest checks the two agree).
 *
 * <p>Texture units: 8 per layer for layers 0 and 1 (units 0-15, map slots in BngMaterial order),
 * 16 the detail map, 17 the node positions. Units 12 and up are beyond what Minecraft's
 * GlStateManager tracks, so everything here binds with plain GL calls; NativeCars saves and
 * restores the units around the cars.
 */
final class CarShader {
	private CarShader() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	static final int LAYERS = 2;
	static final int MAPS = BngMaterial.MAP_KEYS.length;
	static final int DETAIL_UNIT = LAYERS * MAPS;
	/** Units the fragment shader samples (2D textures): the layers' maps and the detail map. */
	static final int UNITS = DETAIL_UNIT + 1;
	/** The unit the car's node positions are bound to, as a buffer texture. */
	static final int NODE_UNIT = UNITS;

	private static int program;
	private static int uProj, uModelView, uSun, uSunColor, uSky, uHorizon, uGround, uAmbient, uScale, uCarBase;
	private static int uLayerCount, uLBase, uLFactors, uLMisc, uLMaps, uLUv1, uLFlags, uPaint, uPaintData, uDetail, uDetailUv1, uSurface, uDebug;
	private static int white, black;

	static boolean ready() {
		if (program > 0) {
			return true;
		}
		if (program < 0) {
			return false;
		}
		LOG.info("Car shader on {} {} (OpenGL {})", GL11.glGetString(GL11.GL_VENDOR), GL11.glGetString(GL11.GL_RENDERER),
			GL11.glGetString(GL11.GL_VERSION));
		int v = compile(GL20.GL_VERTEX_SHADER, VERTEX), g = compile(org.lwjgl.opengl.GL32.GL_GEOMETRY_SHADER, GEOMETRY),
			f = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
		if (v == 0 || g == 0 || f == 0) {
			program = -1;
			return false;
		}
		int p = GL20.glCreateProgram();
		GL20.glAttachShader(p, v);
		GL20.glAttachShader(p, g);
		GL20.glAttachShader(p, f);
		GL20.glBindAttribLocation(p, 0, "Local");
		GL20.glBindAttribLocation(p, 1, "LocalN");
		GL20.glBindAttribLocation(p, 2, "UV0");
		GL20.glBindAttribLocation(p, 3, "UV1");
		GL20.glBindAttribLocation(p, 4, "Rest");
		GL20.glBindAttribLocation(p, 5, "Frame");
		GL20.glBindAttribLocation(p, 6, "Sky");
		GL20.glLinkProgram(p);
		if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
			LOG.warn("Car shader didn't link: {}", GL20.glGetProgramInfoLog(p));
			program = -1;
			return false;
		}
		uProj = GL20.glGetUniformLocation(p, "ProjMat");
		uModelView = GL20.glGetUniformLocation(p, "ModelViewMat");
		uSun = GL20.glGetUniformLocation(p, "Sun");
		uSunColor = GL20.glGetUniformLocation(p, "SunColor");
		uSky = GL20.glGetUniformLocation(p, "SkyColor");
		uHorizon = GL20.glGetUniformLocation(p, "HorizonColor");
		uGround = GL20.glGetUniformLocation(p, "GroundColor");
		uAmbient = GL20.glGetUniformLocation(p, "Ambient");
		uScale = GL20.glGetUniformLocation(p, "Scale");
		uCarBase = GL20.glGetUniformLocation(p, "CarBase");
		uLayerCount = GL20.glGetUniformLocation(p, "LayerCount");
		uLBase = GL20.glGetUniformLocation(p, "LBase");
		uLFactors = GL20.glGetUniformLocation(p, "LFactors");
		uLMisc = GL20.glGetUniformLocation(p, "LMisc");
		uLMaps = GL20.glGetUniformLocation(p, "LMaps");
		uLUv1 = GL20.glGetUniformLocation(p, "LUv1");
		uLFlags = GL20.glGetUniformLocation(p, "LFlags");
		uPaint = GL20.glGetUniformLocation(p, "Paint");
		uPaintData = GL20.glGetUniformLocation(p, "PaintData");
		uDetail = GL20.glGetUniformLocation(p, "DetailParams");
		uDetailUv1 = GL20.glGetUniformLocation(p, "DetailUv1");
		uSurface = GL20.glGetUniformLocation(p, "Surface");
		uDebug = GL20.glGetUniformLocation(p, "DebugView");
		int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		GL20.glUseProgram(p);   // plain GL: a shader pack's record of the bound program can skip a GlStateManager bind
		String[] slots = {"Base", "Palette", "Opacity", "Metallic", "Roughness", "Ao", "ClearCoat", "Normal"};
		for (int l = 0; l < LAYERS; l++) {
			for (int s = 0; s < MAPS; s++) {
				GL20.glUniform1i(GL20.glGetUniformLocation(p, "L" + l + slots[s]), l * MAPS + s);
			}
		}
		GL20.glUniform1i(GL20.glGetUniformLocation(p, "Detail"), DETAIL_UNIT);
		GL20.glUniform1i(GL20.glGetUniformLocation(p, "Nodes"), NODE_UNIT);
		GL20.glUseProgram(prevProgram);   // render() binds it inside its own save/restore
		// made now, through GlStateManager, while its idea of the bound unit is still true (material()
		// switches units with plain GL)
		white();
		black();
		program = p;
		return true;
	}

	static int program() {
		return program;
	}

	/** Per frame: matrices and light from Minecraft's sky (sun angle, sky colour, daylight). */
	static void begin(Matrix4f proj, Matrix4f modelView, ClientLevel level, Vec3 cam, float partialTick, float scale) {
		GL20.glUniform1f(uScale, scale);
		try (MemoryStack st = MemoryStack.stackPush()) {
			GL20.glUniformMatrix4fv(uProj, false, proj.get(st.mallocFloat(16)));
			GL20.glUniformMatrix4fv(uModelView, false, modelView.get(st.mallocFloat(16)));
		}
		double a = level.getSunAngle(partialTick);   // 0 = noon, sun straight up
		float sx = (float) -Math.sin(a), sy = (float) Math.cos(a), sz = 0.35F;
		float l = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
		float day = Math.max(0.15F, Math.min(1F, 1F - level.getSkyDarken(partialTick) / 11F));
		Vec3 sky = level.getSkyColor(cam, partialTick);
		GL20.glUniform3f(uSun, sx / l, sy / l, sz / l);
		GL20.glUniform3f(uSunColor, 1.05F * day, 1.0F * day, 0.92F * day);
		GL20.glUniform3f(uSky, (float) sky.x, (float) sky.y, (float) sky.z);
		GL20.glUniform3f(uHorizon, (float) (sky.x * 0.6 + 0.25 * day), (float) (sky.y * 0.6 + 0.25 * day), (float) (sky.z * 0.6 + 0.25 * day));
		GL20.glUniform3f(uGround, 0.16F * day, 0.20F * day, 0.11F * day);   // grass, as a reflection
		GL20.glUniform3f(uAmbient, 0.40F + 0.22F * day, 0.42F + 0.22F * day, 0.46F + 0.22F * day);   // from the sky above
	}

	private static int debugView;

	/**
	 * Dev command "native debug N": 0 the car as drawn; 1 the top layer's opacity (red: one layer
	 * only); 2 the blended base colour; 3 metallic, roughness, clear coat as red, green, blue;
	 * 4 the shading normal; 5 the baked sky visibility (SkyOcclusion).
	 */
	static void setDebugView(int view) {
		debugView = view;
	}

	/** Per car: where it is relative to the camera, in blocks (its nodes are metres around that point). */
	static void car(float x, float y, float z) {
		GL20.glUniform3f(uCarBase, x, y, z);
		GL20.glUniform1i(uDebug, debugView);
	}

	/** Per car: its three paints (sRGB) and their metallic, roughness, clear coat and clear coat roughness. */
	static void paints(float[][] paint, float[][] paintData) {
		try (MemoryStack st = MemoryStack.stackPush()) {
			FloatBuffer c = st.mallocFloat(9), d = st.mallocFloat(12);
			for (int i = 0; i < 3; i++) {
				c.put(paint[i][0]).put(paint[i][1]).put(paint[i][2]);
				d.put(paintData[i]);
			}
			GL20.glUniform3fv(uPaint, c.flip());
			GL20.glUniform4fv(uPaintData, d.flip());
		}
	}

	/**
	 * Per material: binds its layers' maps (white where absent, black for a base map BeamNG names
	 * but couldn't load) and sets the layer uniforms. textures: GL texture per glTF image (0 = not
	 * loaded); normalFormat: NormalFormat per glTF image.
	 */
	static void material(BngMaterial m, int[] textures, int[] normalFormat, boolean glass, NativeExport.MaterialExtra extra, int detailTexture) {
		int layers = Math.min(LAYERS, m.layers());
		try (MemoryStack st = MemoryStack.stackPush()) {
			FloatBuffer base = st.mallocFloat(4 * LAYERS), factors = st.mallocFloat(4 * LAYERS), misc = st.mallocFloat(4 * LAYERS);
			IntBuffer maps = st.mallocInt(LAYERS), uv1 = st.mallocInt(LAYERS), flags = st.mallocInt(LAYERS);
			for (int l = 0; l < LAYERS; l++) {
				BngMaterial.Layer layer = m.layer(Math.min(l, layers - 1));
				int present = 0, onUv1 = 0, normal = 0;
				for (int s = 0; s < MAPS; s++) {
					int image = layer.maps()[s];
					int t = image >= 0 && image < textures.length ? textures[image] : 0;
					if (s == BngMaterial.BASE && t == 0 && layer.baseMissing()) {
						t = black();
					}
					bind(l * MAPS + s, t != 0 ? t : white());
					if (t != 0) {
						present |= 1 << s;
					}
					if (layer.uv1()[s]) {
						onUv1 |= 1 << s;
					}
					if (s == BngMaterial.NORMAL && t != 0 && image < normalFormat.length) {
						normal = normalFormat[image];
					}
				}
				int f = layer.instanceDiffuse() ? 1 : 0;
				for (int p = 0; p < BngMaterial.PALETTE_KEYS.length; p++) {
					f |= layer.palette()[p] ? 2 << p : 0;
				}
				float[] c = layer.baseColor(), k = layer.factors();
				base.put(c[0]).put(c[1]).put(c[2]).put(c.length > 3 ? c[3] : 1F);
				factors.put(k[BngMaterial.F_METALLIC]).put(k[BngMaterial.F_ROUGHNESS]).put(k[BngMaterial.F_CLEAR_COAT]).put(k[BngMaterial.F_CLEAR_COAT_ROUGHNESS]);
				misc.put(k[BngMaterial.F_OPACITY]).put(k[BngMaterial.F_NORMAL_STRENGTH]).put(normal).put(0F);
				maps.put(present);
				uv1.put(onUv1);
				flags.put(f);
			}
			GL20.glUniform1i(uLayerCount, layers);
			GL20.glUniform4fv(uLBase, base.flip());
			GL20.glUniform4fv(uLFactors, factors.flip());
			GL20.glUniform4fv(uLMisc, misc.flip());
			GL20.glUniform1iv(uLMaps, maps.flip());
			GL20.glUniform1iv(uLUv1, uv1.flip());
			GL20.glUniform1iv(uLFlags, flags.flip());
		}
		boolean detail = extra != null && detailTexture != 0 && extra.detailStrength() > 0;
		bind(DETAIL_UNIT, detail ? detailTexture : white());
		GL20.glUniform4f(uDetail, detail ? extra.detailStrength() : 0F, extra != null ? extra.detailScaleU() : 1F, extra != null ? extra.detailScaleV() : 1F,
			extra != null ? extra.detailLayer() : 0F);
		GL20.glUniform1i(uDetailUv1, extra != null && extra.detailUv1() ? 1 : 0);
		GL20.glUniform4f(uSurface, glass ? 1F : 0F, glass ? 0F : m.alphaRef(), m.legacy() ? 1F : 0F, 0F);
	}

	/** Plain GL: units past 11 are outside GlStateManager's tracking (see the class note). */
	private static void bind(int unit, int texture) {
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
	}

	static int white() {
		if (white == 0) {
			white = solid(0xFFFFFFFF);
		}
		return white;
	}

	private static int black() {
		if (black == 0) {
			black = solid(0xFF000000);
		}
		return black;
	}

	/** A 1x1 texture (ABGR), made through GlStateManager on the current unit, which keeps its record right. */
	private static int solid(int abgr) {
		NativeImage img = new NativeImage(1, 1, false);
		img.setPixelRGBA(0, 0, abgr);
		int id = TextureUtil.generateTextureId();
		TextureUtil.prepareImage(id, 1, 1);
		img.upload(0, 0, 0, false);
		img.close();
		return id;
	}

	private static int compile(int type, String src) {
		int s = GL20.glCreateShader(type);
		GL20.glShaderSource(s, src);
		GL20.glCompileShader(s);
		if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
			LOG.warn("Car shader didn't compile: {}", GL20.glGetShaderInfoLog(s));
			return 0;
		}
		return s;
	}

	private static final String VERTEX = """
		#version 150
		in vec3 Local;    // the vertex in its node frame (metres)
		in vec3 LocalN;   // its normal in that frame
		in vec2 UV0;
		in vec2 UV1;
		in vec3 Rest;
		in ivec3 Frame;   // the frame's nodes; Frame.y < 0: the vertex only follows Frame.x
		in float Sky;     // the sky the vertex sees, 0-1 (SkyOcclusion)
		uniform samplerBuffer Nodes;   // node positions now: metres, Minecraft axes, around CarBase
		uniform vec3 CarBase;          // the car's position relative to the camera, blocks
		uniform float Scale;           // Minecraft blocks per metre
		uniform mat4 ModelViewMat;
		uniform mat4 ProjMat;
		out vec2 vUv0;
		out vec2 vUv1;
		out vec3 vNormal;
		out vec3 vWorldPos;
		out vec3 vRest;
		out float vSky;
		void main() {
		    vec3 o = texelFetch(Nodes, Frame.x).xyz;
		    vec3 p = o + Local;
		    vec3 n = LocalN;
		    if (Frame.y >= 0) {
		        // FlexBinding.basis: t1 along n0 -> n1, t3 normal to the nodes' plane, t2 = t3 x t1
		        vec3 a = texelFetch(Nodes, Frame.y).xyz - o;
		        vec3 b = texelFetch(Nodes, Frame.z).xyz - o;
		        vec3 t1 = a / max(length(a), 1e-9);
		        vec3 c = cross(t1, b);
		        vec3 t3 = c / max(length(c), 1e-9);
		        vec3 t2 = cross(t3, t1);
		        p = o + Local.x * t1 + Local.y * t2 + Local.z * t3;
		        n = LocalN.x * t1 + LocalN.y * t2 + LocalN.z * t3;
		    }
		    vec3 world = p * Scale + CarBase;   // camera-relative, Minecraft axes
		    gl_Position = ProjMat * ModelViewMat * vec4(world, 1.0);
		    vUv0 = UV0;
		    vUv1 = UV1;
		    vNormal = n;
		    vWorldPos = world;
		    vRest = Rest;
		    vSky = Sky;
		}
		""";

	/**
	 * Drops triangles torn far beyond their rest shape: when a part breaks off (a wheel, a door),
	 * triangles bound to nodes on both sides of the break would stretch into long strands. BeamNG
	 * hides a broken flexmesh the same way. Crumpling shortens edges and stays visible.
	 */
	private static final String GEOMETRY = """
		#version 150
		layout(triangles) in;
		layout(triangle_strip, max_vertices = 3) out;
		in vec2 vUv0[];
		in vec2 vUv1[];
		in vec3 vNormal[];
		in vec3 vWorldPos[];
		in vec3 vRest[];
		in float vSky[];
		uniform float Scale;   // Minecraft blocks per metre (rest positions are metres)
		out vec2 uv0;
		out vec2 uv1;
		out vec3 normal;
		out vec3 worldPos;
		out float sky;
		void main() {
		    for (int i = 0; i < 3; i++) {
		        int j = (i + 1) % 3;
		        if (distance(vWorldPos[i], vWorldPos[j]) > Scale * (2.0 * distance(vRest[i], vRest[j]) + 0.1)) return;
		    }
		    for (int i = 0; i < 3; i++) {
		        uv0 = vUv0[i];
		        uv1 = vUv1[i];
		        normal = vNormal[i];
		        worldPos = vWorldPos[i];
		        sky = vSky[i];
		        gl_Position = gl_in[i].gl_Position;
		        EmitVertex();
		    }
		    EndPrimitive();
		}
		""";

	private static final String FRAGMENT = """
		#version 150
		uniform sampler2D L0Base, L0Palette, L0Opacity, L0Metallic, L0Roughness, L0Ao, L0ClearCoat, L0Normal;
		uniform sampler2D L1Base, L1Palette, L1Opacity, L1Metallic, L1Roughness, L1Ao, L1ClearCoat, L1Normal;
		uniform sampler2D Detail;
		uniform int LayerCount;
		uniform vec4 LBase[2];       // base colour factor
		uniform vec4 LFactors[2];    // metallic, roughness, clear coat, clear coat roughness
		uniform vec4 LMisc[2];       // opacity, normal strength, normal format (NormalFormat), -
		uniform int LMaps[2];        // a bit per map slot that has a texture (BngMaterial order)
		uniform int LUv1[2];         // a bit per map slot on UV1
		uniform int LFlags[2];       // 1 instanceDiffuse; 2, 4, 8, 16, 32 palette base/roughness/metallic/clear coat/cc roughness
		uniform vec3 Paint[3];       // the car's paints, sRGB
		uniform vec4 PaintData[3];   // their metallic, roughness, clear coat, clear coat roughness
		uniform vec4 DetailParams;   // strength, scale u, scale v, layer
		uniform int DetailUv1;
		uniform vec4 Surface;        // glass (1) or opaque (0), alpha cutout, a v1 material (1)
		uniform vec3 Sun;
		uniform vec3 SunColor;
		uniform vec3 SkyColor;
		uniform vec3 HorizonColor;
		uniform vec3 GroundColor;
		uniform vec3 Ambient;
		uniform int DebugView;       // CarShader.setDebugView
		in vec2 uv0;
		in vec2 uv1;
		in vec3 normal;
		in vec3 worldPos;
		in float sky;
		out vec4 fragColor;

		struct Layer { vec3 base; float alpha; float mapAlpha; float opacity; float metallic; float roughness; float ao; float clearCoat; float ccRough; vec3 n; };

		vec3 lin(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
		bool has(int bits, int slot) { return (bits & (1 << slot)) != 0; }
		vec2 uvOf(int i, int slot) { return has(LUv1[i], slot) ? uv1 : uv0; }

		// One layer, step for step as defaultMat.hlsl processLayers (212-424) and getColorPalette
		// (shadergen.h.hlsl:163-192) build it.
		Layer layer(int i, sampler2D sBase, sampler2D sPal, sampler2D sOpa, sampler2D sMet, sampler2D sRough, sampler2D sAo, sampler2D sCc,
		            sampler2D sNrm) {
		    int maps = LMaps[i], flags = LFlags[i];
		    Layer o;
		    o.base = LBase[i].rgb;
		    o.alpha = LBase[i].a;
		    o.opacity = LMisc[i].x;
		    o.metallic = LFactors[i].x;
		    o.roughness = LFactors[i].y;
		    o.clearCoat = LFactors[i].z;
		    o.ccRough = LFactors[i].w;
		    o.ao = 1.0;
		    if (has(maps, 1)) {
		        // the mask's alpha is how much paint, its rgb (normalised) which of the three; the rest
		        // keeps the layer's own colour and data
		        vec4 s = texture(sPal, uvOf(i, 1));
		        float a = clamp(s.a, 0.0, 1.0);
		        vec3 pick = s.rgb / (s.r + s.g + s.b + 1e-10) * a;
		        vec3 col = pick.r * lin(Paint[0]) + pick.g * lin(Paint[1]) + pick.b * lin(Paint[2]) + (1.0 - a);
		        vec4 data = pick.r * PaintData[0] + pick.g * PaintData[1] + pick.b * PaintData[2] + (1.0 - a);
		        if ((flags & 2) != 0) o.base *= col;
		        if ((flags & 4) != 0) o.roughness *= data.y;
		        if ((flags & 8) != 0) o.metallic *= data.x;
		        if ((flags & 16) != 0) o.clearCoat *= data.z;
		        if ((flags & 32) != 0) o.ccRough *= data.w;
		    } else if ((flags & 1) != 0) {
		        o.base *= Paint[0];   // instanceDiffuse without a mask: paint 1 as it is (defaultMat.hlsl:251-252)
		    }
		    if (has(maps, 2)) o.opacity *= texture(sOpa, uvOf(i, 2)).r;
		    // without a base map BeamNG samples white and still blends the detail map in
		    // (defaultMat.hlsl:294-307): the Cadillac's rims are a 0.38 grey made black that way
		    vec4 b = has(maps, 0) ? texture(sBase, uvOf(i, 0)) : vec4(1.0);
		    vec3 c = b.rgb;
		    if (DetailParams.x > 0.0 && int(DetailParams.w + 0.5) == i) {
		        // blendDetail (defaultMat.hlsl:204): the detail map lifts or darkens around its middle grey
		        vec2 duv = (DetailUv1 == 1 ? uv1 : uv0) * DetailParams.yz;
		        c = clamp(c + (texture(Detail, duv).rgb * 2.0 - 1.0) * DetailParams.x, 0.0, 1.0);
		    }
		    o.base *= lin(c);
		    o.alpha *= b.a;
		    o.mapAlpha = b.a;
		    if (has(maps, 3)) o.metallic *= texture(sMet, uvOf(i, 3)).r;
		    if (has(maps, 4)) o.roughness *= texture(sRough, uvOf(i, 4)).r;
		    if (has(maps, 5)) o.ao *= texture(sAo, uvOf(i, 5)).r;
		    if (has(maps, 6)) o.clearCoat *= texture(sCc, uvOf(i, 6)).r;
		    o.n = vec3(0.0, 0.0, 1.0);
		    if (has(maps, 7)) {
		        vec4 t = texture(sNrm, uvOf(i, 7));
		        int format = int(LMisc[i].z + 0.5);
		        vec3 n;
		        if (format == 0) {
		            n = t.xyz * 2.0 - 1.0;
		        } else {
		            vec2 xy = (format == 2 ? t.ag : t.rg) * 2.0 - 1.0;
		            n = vec3(xy, sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0)));
		        }
		        n.xy *= LMisc[i].y;
		        o.n = normalize(n);
		    }
		    return o;
		}

		// The surface normal turned by a normal-map normal, in a tangent frame from screen derivatives
		// (the export has no tangents) of the position and of the UV set the normal map is on. The
		// derivatives are taken at the top of main(): inside a branch on a texture value they'd be undefined.
		vec3 dp1, dp2;
		vec2 duv1, duv2;
		void frameDerivatives() {
		    dp1 = dFdx(worldPos);
		    dp2 = dFdy(worldPos);
		    vec2 nuv = has(LMaps[0], 7) || LayerCount < 2 ? uvOf(0, 7) : uvOf(1, 7);
		    duv1 = dFdx(nuv);
		    duv2 = dFdy(nuv);
		}

		vec3 bump(vec3 N, vec3 t) {
		    vec3 p2 = cross(dp2, N), p1 = cross(N, dp1);
		    vec3 T = p2 * duv1.x + p1 * duv2.x;
		    vec3 B = p2 * duv1.y + p1 * duv2.y;
		    float s = max(dot(T, T), dot(B, B));
		    if (s < 1e-20) return N;
		    s = inversesqrt(s);
		    return normalize(mat3(T * s, B * s, N) * t);
		}

		// The sky and the ground as seen along r, blurred toward their average as the surface roughens.
		vec3 env(vec3 r, float rough) {
		    vec3 sky = lin(SkyColor), hor = lin(HorizonColor), gnd = lin(GroundColor);
		    vec3 sharp = r.y > 0.0 ? mix(hor, sky, sqrt(r.y)) : mix(hor, gnd, sqrt(-r.y));
		    vec3 soft = mix(mix(gnd, hor, 0.6), mix(hor, sky, 0.5), smoothstep(-0.6, 0.6, r.y));
		    return mix(sharp, soft, rough);
		}

		// How much of the environment a rough surface reflects, by angle (Karis' fit of the split-sum BRDF)
		vec3 envBrdf(vec3 f0, float rough, float nv) {
		    vec4 r = rough * vec4(-1.0, -0.0275, -0.572, 0.022) + vec4(1.0, 0.0425, 1.04, -0.04);
		    float a = min(r.x * r.x, exp2(-9.28 * nv)) * r.x + r.y;
		    vec2 ab = vec2(-1.04, 1.04) * a + r.zw;
		    return f0 * ab.x + ab.y;
		}

		float ggx(float nh, float rough) {
		    float a2 = rough * rough * rough * rough;
		    float d = nh * nh * (a2 - 1.0) + 1.0;
		    return a2 / (3.14159 * d * d);
		}

		// Minecraft's picture is low dynamic range: highlights roll off instead of clipping
		vec3 rolloff(vec3 c) {
		    return min(c, vec3(0.8)) + 0.2 * (1.0 - exp(-max(c - 0.8, vec3(0.0)) / 0.2));
		}

		void main() {
		    frameDerivatives();
		    Layer m = layer(0, L0Base, L0Palette, L0Opacity, L0Metallic, L0Roughness, L0Ao, L0ClearCoat, L0Normal);
		    if (Surface.y > 0.0 && m.alpha < Surface.y) discard;
		    float t = -1.0;
		    // How much of what's behind the glass it hides: BeamNG's material opacity, the bottom layer's
		    // factor and map with each layer above blended in by its own (defaultMat.hlsl:290-292); a v1
		    // material's colour and base map alpha. Without an opacity map the base map's alpha stands in.
		    float opacity = Surface.z > 0.5 ? m.alpha : m.opacity * (has(LMaps[0], 2) ? 1.0 : m.mapAlpha);
		    if (LayerCount > 1) {
		        Layer u = layer(1, L1Base, L1Palette, L1Opacity, L1Metallic, L1Roughness, L1Ao, L1ClearCoat, L1Normal);
		        t = u.opacity;
		        opacity = mix(opacity, u.opacity, u.opacity);
		        m.base = mix(m.base, u.base, t);
		        m.metallic = mix(m.metallic, u.metallic, t);
		        m.roughness = mix(m.roughness, u.roughness, t);
		        m.ao = mix(m.ao, u.ao, t);
		        m.clearCoat = mix(m.clearCoat, u.clearCoat, t);
		        m.ccRough = mix(m.ccRough, u.ccRough, t);
		        m.n = normalize(mix(m.n, u.n, t));
		    }

		    vec3 N = normalize(normal);
		    if (!gl_FrontFacing) N = -N;
		    vec3 n = m.n.z < 0.9999 ? bump(N, m.n) : N;
		    if (DebugView != 0) {
		        vec3 d = DebugView == 1 ? (t < 0.0 ? vec3(1.0, 0.0, 0.0) : vec3(t))
		            : DebugView == 2 ? pow(clamp(m.base, 0.0, 1.0), vec3(1.0 / 2.2))
		            : DebugView == 3 ? vec3(m.metallic, m.roughness, m.clearCoat)
		            : DebugView == 5 ? vec3(sky)
		            : n * 0.5 + 0.5;
		        fragColor = vec4(d, 1.0);
		        return;
		    }
		    // BeamNG's glass is premultiplied (defaultMat.hlsl:571-573): the colour scaled by the opacity,
		    // the reflection on top at full strength. The Cadillac's tinted side windows are opacity 1, as
		    // black as in BeamNG; its clear glass is 0.13-0.18 with no colour, a slight darkening.
		    opacity = clamp(opacity, 0.0, 1.0);
		    if (Surface.x > 0.0) m.base *= opacity;
		    vec3 v = normalize(-worldPos);
		    float nv = max(dot(n, v), 1e-4);
		    float metal = clamp(m.metallic, 0.0, 1.0);
		    float rough = clamp(m.roughness, 0.045, 1.0);
		    vec3 diffuse = m.base * (1.0 - metal);
		    vec3 f0 = mix(vec3(0.04), m.base, metal);

		    // the sky this point sees (SkyOcclusion): inside the cabin and the wheel wells little sky light,
		    // no direct sun and dimmer reflections, as BeamNG's shadows and ambient occlusion give
		    float occ = clamp(sky, 0.0, 1.0);
		    float sunVis = smoothstep(0.2, 0.65, occ);
		    vec3 sun = lin(SunColor) * sunVis;
		    float nl = max(dot(n, Sun), 0.0);
		    vec3 h = normalize(Sun + v);
		    float nh = max(dot(n, h), 0.0), vh = max(dot(v, h), 0.0);
		    vec3 f = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
		    float k = rough * rough * 0.5;
		    float vis = 0.25 / ((nl * (1.0 - k) + k) * (nv * (1.0 - k) + k));
		    vec3 sunSpec = min(ggx(nh, rough) * vis, 60.0) * f * nl;

		    // sky light from above, darker bounce light from the grass below
		    vec3 ambient = lin(mix(Ambient * vec3(0.55, 0.6, 0.5), Ambient, n.y * 0.5 + 0.5));
		    vec3 col = diffuse * (ambient * m.ao * occ + sun * nl) + sunSpec * sun
		        + envBrdf(f0, rough, nv) * env(reflect(-v, n), rough) * m.ao * occ;

		    if (m.clearCoat > 0.0) {
		        // a smooth lacquer over everything: it reflects with the geometric normal and dims what's under it
		        float cc = clamp(m.clearCoat, 0.0, 1.0);
		        float ccRough = clamp(m.ccRough, 0.03, 1.0);
		        float nvc = max(dot(N, v), 1e-4);
		        float fc = (0.04 + 0.96 * pow(1.0 - nvc, 5.0)) * cc;
		        float nlc = max(dot(N, Sun), 0.0), nhc = max(dot(N, h), 0.0);
		        float kc = ccRough * ccRough * 0.5;
		        float visc = 0.25 / ((nlc * (1.0 - kc) + kc) * (nvc * (1.0 - kc) + kc));
		        vec3 coat = env(reflect(-v, N), ccRough) * m.ao * occ + min(ggx(nhc, ccRough) * visc, 60.0) * nlc * sun;
		        col = col * (1.0 - fc) + coat * fc;
		    }
		    if (Surface.x > 0.0) {
		        // blended as SRC_ALPHA (a shader pack's car target wants it so): undo the premultiply, with a
		        // floor so a fully clear pane still shows its reflection
		        float a = max(opacity, 0.05);
		        fragColor = vec4(pow(rolloff(col / a), vec3(1.0 / 2.2)), a);
		    } else {
		        fragColor = vec4(pow(rolloff(col), vec3(1.0 / 2.2)), 1.0);
		    }
		}
		""";
}
