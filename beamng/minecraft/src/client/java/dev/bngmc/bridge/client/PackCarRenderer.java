package dev.bngmc.bridge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.MeshTimeline;
import dev.bngmc.bridge.nativecar.BngMaterial;
import dev.bngmc.bridge.nativecar.CarModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * The cars under a shader pack, lit by the pack (docs/minecraft-host.md, "Shaders"): drawn as an
 * entity, from the car's entity renderer, with Minecraft's own entity shaders, which Iris replaces
 * with the pack's programs (MixinGameRenderer: getRendertypeEntityCutout*Shader -> the pack's
 * entities program, or its shadow program in the shadow pass). So the pack lights the car, it is in
 * the pack's shadow map, and torches and the sky light it as they light a pig.
 *
 * <p>The bending stays on the GPU: once a frame a transform feedback pass runs the car's own
 * bending (CarShader's vertex shader) and writes every triangle out in the vertex layout Iris gives
 * entity programs (IrisVertexFormats.ENTITY: Minecraft's NEW_ENTITY, then iris_Entity,
 * mc_midTexCoord and at_tangent, attribute locations 0-8 by that order, ShaderInstance), a torn
 * triangle collapsed to a point as CarShader's geometry shader hides it; then each material is
 * drawn from that buffer. Materials are simplified to one texture and one colour (BeamNG's paint
 * on painted parts): the pack does the lighting, BeamNG's layered paint and clear coat aren't
 * drawn under a pack. Normals are world-space in both passes (a vanilla entity's are turned by
 * the shadow pass's pose; only the position matters to a shadow map).
 */
final class PackCarRenderer {
	private PackCarRenderer() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static final int STRIDE = 56;
	private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");
	private static int program = 0, uScale, uLight, uOverlay, uTint;
	private static boolean failed;
	// Off by default: the pack shades the car matte, with no reflections (Jas: "the cars look
	// pastel", 2026-10-03). `native packpath` or -Dbngbridge.packLitCars=true turns it on.
	private static boolean on = Boolean.getBoolean("bngbridge.packLitCars");
	private static int mainLogs, shadowLogs;

	/** Dev switch: off draws the cars over the pack's finished frame instead (ShaderCarTarget); on retries after a failure. */
	static boolean toggle() {
		on = !on;
		failed = false;
		return on;
	}

	/** Whether cars under a pack are drawn this way (else NativeCars draws them over the frame). */
	static boolean active() {
		return on && !failed;
	}

	/**
	 * The car's entity renderer, a pack drawing (main or shadow pass): the car, if loaded. entityPose
	 * is the renderer's pose (the entity's render position, and the shadow view in the shadow pass);
	 * ex, ey, ez that render position. False if nothing was drawn.
	 */
	static boolean render(long id, Matrix4f entityPose, double ex, double ey, double ez, int packedLight) {
		if (!active() || (program == 0 && !build())) {
			return false;
		}
		NativeCars.Car car = NativeCars.readyCar(id);
		CrossoverCoords.Region region = BngWorld.region();
		Minecraft mc = Minecraft.getInstance();
		MeshTimeline.Pose pose = car != null ? NativeCars.pose(id) : null;
		if (pose == null || region == null || mc.level == null || pose.nodeCount() != car.nodeCount) {
			return false;
		}
		int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		int prevBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
		try {
			V3 base = CrossoverCoords.canonicalToMinecraftPosition(pose.pos(), region);
			if (car.packFrame != NativeCars.frameNo() || car.packLight != packedLight) {
				bend(car, pose, region, packedLight);
				car.packFrame = NativeCars.frameNo();
				car.packLight = packedLight;
			}
			Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(entityPose)
				.translate((float) (base.x() - ex), (float) (base.y() - ey), (float) (base.z() - ez));
			boolean shadow = ShaderCompat.shadowPass();
			if (shadow ? shadowLogs++ < 2 : mainLogs++ < 2) {
				LOG.info("Car {} through the pack's entity program (shadow pass {}): model-view {}, projection {}", id, shadow,
					modelView, RenderSystem.getProjectionMatrix());
			}
			draw(car, modelView, mc);
			if (!shadow) {
				car.packDrawnFrame = NativeCars.frameNo();
			}
			return true;
		} catch (RuntimeException e) {
			failed = true;
			LOG.warn("Cars can't be drawn through the shader pack; drawing them over the frame instead", e);
			return false;
		} finally {
			GL30.glBindVertexArray(prevVao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuf);
			BufferUploader.invalidate();
		}
	}

	/**
	 * The transform feedback pass: the bent car into car.packVbo. Plain GL, the program and texture
	 * unit put back as they were: Iris skips a program bind it believes is already made.
	 */
	private static void bend(NativeCars.Car car, MeshTimeline.Pose pose, CrossoverCoords.Region region, int packedLight) {
		if (car.packVbo == 0) {
			create(car);
		}
		pose.nodesMinecraftAxes(car.nodes);
		NativeCars.uploadNodes(car);
		int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		int prevActive = GlStateManager._getActiveTexture();
		boolean feeding = false;
		try {
			GL20.glUseProgram(program);
			GL20.glUniform1f(uScale, (float) region.scale());
			GL30.glUniform1ui(uLight, packedLight);
			GL30.glUniform1ui(uOverlay, OverlayTexture.NO_OVERLAY);
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + CarShader.NODE_UNIT);
			GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, car.nodeTexture);
			GL30.glBindVertexArray(car.vao);
			GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, car.packVbo);
			GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
			GL30.glBeginTransformFeedback(GL11.GL_TRIANGLES);
			feeding = true;
			// draws are contiguous from index 0 (NativeCars.upload), so the output lines up with them
			for (int[] d : car.draws) {
				GL30.glUniform1ui(uTint, colour(car, material(car.mesh, d[2])));
				GL11.glDrawElements(GL11.GL_TRIANGLES, d[1], GL11.GL_UNSIGNED_INT, (long) d[0] * 4);
			}
		} finally {
			if (feeding) {
				GL30.glEndTransformFeedback();
			}
			GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
			GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
			GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
			GL13.glActiveTexture(prevActive);
			GL20.glUseProgram(prevProgram);
		}
	}

	/** Each material from the bent buffer, with the entity shader the pack put in Minecraft's place. */
	private static void draw(NativeCars.Car car, Matrix4f modelView, Minecraft mc) {
		CarModel m = car.mesh;
		mc.gameRenderer.overlayTexture().setupOverlayColor();
		mc.gameRenderer.lightTexture().turnOnLightLayer();
		int white = mc.getTextureManager().getTexture(WHITE).getId();
		// the state the materials change, put back after through RenderSystem (Iris tracks it there)
		boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), blend = GL11.glIsEnabled(GL11.GL_BLEND);
		boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
		int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
		int srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		try {
			for (int[] d : car.draws) {
				CarModel.Material mat = material(m, d[2]);
				boolean glass = mat != null && mat.glass();
				boolean twoSided = mat == null || mat.bng().doubleSided();
				Supplier<ShaderInstance> shader = glass ? GameRenderer::getRendertypeEntityTranslucentShader
					: twoSided ? GameRenderer::getRendertypeEntityCutoutNoCullShader : GameRenderer::getRendertypeEntityCutoutShader;
				RenderSystem.setShader(shader);
				ShaderInstance sh = RenderSystem.getShader();
				if (sh == null) {
					continue;
				}
				int tex = texture(car, mat);
				RenderSystem.setShaderTexture(0, tex != 0 ? tex : white);
				if (twoSided || glass) {
					RenderSystem.disableCull();
				} else {
					RenderSystem.enableCull();
				}
				if (glass) {
					RenderSystem.enableBlend();
					RenderSystem.defaultBlendFunc();
				} else {
					RenderSystem.disableBlend();
				}
				sh.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, RenderSystem.getProjectionMatrix(), mc.getWindow());
				sh.apply();
				GL30.glBindVertexArray(car.packVao);
				GL11.glDrawArrays(GL11.GL_TRIANGLES, d[0], d[1]);
				sh.clear();
			}
		} finally {
			if (cull) {
				RenderSystem.enableCull();
			} else {
				RenderSystem.disableCull();
			}
			if (blend) {
				RenderSystem.enableBlend();
			} else {
				RenderSystem.disableBlend();
			}
			RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
			if (depthTest) {
				RenderSystem.enableDepthTest();
			} else {
				RenderSystem.disableDepthTest();
			}
			RenderSystem.depthFunc(depthFunc);
			mc.gameRenderer.lightTexture().turnOffLightLayer();
			mc.gameRenderer.overlayTexture().teardownOverlayColor();
		}
	}

	private static CarModel.Material material(CarModel m, int index) {
		return index >= 0 && index < m.materials.size() ? m.materials.get(index) : null;
	}

	/** The layer painted with the car's paint (instanceDiffuse, or a palette mask taking the base colour), or -1. */
	private static int paintLayer(BngMaterial b) {
		for (int l = b.layers() - 1; l >= 0; l--) {
			if (b.layer(l).painted()) {
				return l;
			}
		}
		return -1;
	}

	/** The texture the pack sees: the painted layer's base map, else the first layer's; 0 for white. */
	private static int texture(NativeCars.Car car, CarModel.Material mat) {
		if (mat == null || car.textures == null) {
			return 0;
		}
		BngMaterial b = mat.bng();
		int image = mat.image();
		if (b.layers() > 0) {
			int p = paintLayer(b);
			image = b.layer(p >= 0 ? p : 0).baseImage();
		}
		return image >= 0 && image < car.textures.length ? car.textures[image] : 0;
	}

	/** The material's colour, sRGB, as the vertex colour (RGBA bytes): the paint on painted parts. */
	private static int colour(NativeCars.Car car, CarModel.Material mat) {
		float r = 1, g = 1, bl = 1, a = 1;
		if (mat != null) {
			BngMaterial b = mat.bng();
			int p = b.layers() > 0 ? paintLayer(b) : -1;
			float[] c = b.layers() > 0 ? b.layer(0).baseColor() : mat.color();   // glTF base colour factor: linear
			if (p >= 0) {
				r = car.paint[0][0];
				g = car.paint[0][1];
				bl = car.paint[0][2];
			} else if (c != null && c.length >= 3) {
				r = srgb(c[0]);
				g = srgb(c[1]);
				bl = srgb(c[2]);
				a = c.length > 3 ? c[3] : 1;
			}
			if (mat.glass() && b.layers() > 0 && !b.legacy()) {
				a = b.layer(0).factors()[BngMaterial.F_OPACITY];   // BeamNG's glass opacity (CarShader)
			}
		}
		return byteOf(r) | byteOf(g) << 8 | byteOf(bl) << 16 | byteOf(a) << 24;
	}

	private static float srgb(float linear) {
		return (float) Math.pow(Math.max(0, linear), 1 / 2.2);
	}

	private static int byteOf(float v) {
		return Math.round(Math.max(0, Math.min(1, v)) * 255) & 255;
	}

	/** The car's output buffer (one vertex per index) and its vertex array, attributes as IrisVertexFormats.ENTITY. */
	private static void create(NativeCars.Car car) {
		car.packVbo = GL15.glGenBuffers();
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, car.packVbo);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) car.indexCount * STRIDE, GL15.GL_STREAM_COPY);
		car.packVao = GL30.glGenVertexArrays();
		GL30.glBindVertexArray(car.packVao);
		// as VertexFormatElement.Usage sets each up: COLOR and NORMAL normalised, UV integer when
		// not float, GENERIC (Iris's three) converted to float unnormalised
		attrib(0, 3, GL11.GL_FLOAT, false, 0);         // Position
		attrib(1, 4, GL11.GL_UNSIGNED_BYTE, true, 12);  // Color
		attrib(2, 2, GL11.GL_FLOAT, false, 16);         // UV0
		GL20.glEnableVertexAttribArray(3);
		GL30.glVertexAttribIPointer(3, 2, GL11.GL_SHORT, STRIDE, 24);   // UV1: overlay
		GL20.glEnableVertexAttribArray(4);
		GL30.glVertexAttribIPointer(4, 2, GL11.GL_SHORT, STRIDE, 28);   // UV2: light
		attrib(5, 3, GL11.GL_BYTE, true, 32);           // Normal
		attrib(6, 3, GL11.GL_SHORT, false, 36);         // iris_Entity: entity, block entity, item
		attrib(7, 2, GL11.GL_FLOAT, false, 44);         // mc_midTexCoord
		attrib(8, 4, GL11.GL_BYTE, false, 52);          // at_tangent (NormI8: x 127)
		GL30.glBindVertexArray(0);
		LOG.info("Car {} ({}) set up for the shader pack: {} vertices, {} MB", car.id, car.model, car.indexCount,
			car.indexCount * (long) STRIDE / (1 << 20));
	}

	private static void attrib(int index, int size, int type, boolean normalized, int offset) {
		GL20.glEnableVertexAttribArray(index);
		GL20.glVertexAttribPointer(index, size, type, normalized, STRIDE, offset);
	}

	static void release(NativeCars.Car car) {
		if (car.packVao != 0) {
			GL30.glDeleteVertexArrays(car.packVao);
			car.packVao = 0;
		}
		if (car.packVbo != 0) {
			GL15.glDeleteBuffers(car.packVbo);
			car.packVbo = 0;
		}
		car.packFrame = -1;
	}

	private static boolean build() {
		if (failed) {
			return false;
		}
		int v = shader(GL20.GL_VERTEX_SHADER, VERTEX), g = shader(GL32.GL_GEOMETRY_SHADER, GEOMETRY);
		if (v == 0 || g == 0) {
			return fail("the bending shader didn't compile");
		}
		int p = GL20.glCreateProgram();
		GL20.glAttachShader(p, v);
		GL20.glAttachShader(p, g);
		String[] in = {"Local", "LocalN", "UV0", "UV1", "Rest", "Frame", "Sky"};   // the car VAO's attributes (NativeCars)
		for (int i = 0; i < in.length; i++) {
			GL20.glBindAttribLocation(p, i, in[i]);
		}
		GL30.glTransformFeedbackVaryings(p, new CharSequence[] {"tfPos", "tfColor", "tfUv", "tfOverlay", "tfLight", "tfNormal", "tfEntity",
			"tfMid", "tfTangent"}, GL30.GL_INTERLEAVED_ATTRIBS);
		GL20.glLinkProgram(p);
		GL20.glDeleteShader(v);
		GL20.glDeleteShader(g);
		if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
			String log = GL20.glGetProgramInfoLog(p);
			GL20.glDeleteProgram(p);
			return fail("the bending shader didn't link: " + log);
		}
		int prev = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		GL20.glUseProgram(p);
		GL20.glUniform1i(GL20.glGetUniformLocation(p, "Nodes"), CarShader.NODE_UNIT);
		GL20.glUseProgram(prev);
		uScale = GL20.glGetUniformLocation(p, "Scale");
		uLight = GL20.glGetUniformLocation(p, "Light");
		uOverlay = GL20.glGetUniformLocation(p, "Overlay");
		uTint = GL20.glGetUniformLocation(p, "Tint");
		program = p;
		LOG.info("Cars under shader packs: drawn as entities, bent by transform feedback");
		return true;
	}

	private static int shader(int type, String src) {
		int s = GL20.glCreateShader(type);
		GL20.glShaderSource(s, src);
		GL20.glCompileShader(s);
		if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
			LOG.warn("Car bending shader: {}", GL20.glGetShaderInfoLog(s));
			GL20.glDeleteShader(s);
			return 0;
		}
		return s;
	}

	private static boolean fail(String why) {
		failed = true;
		LOG.warn("Cars can't be lit by the shader pack ({}); drawing them over the frame instead", why);
		return false;
	}

	// CarShader's bending, with the car at the origin (positions in blocks around its base point)
	private static final String VERTEX = """
		#version 150
		in vec3 Local;
		in vec3 LocalN;
		in vec2 UV0;
		in vec3 Rest;
		in ivec3 Frame;
		uniform samplerBuffer Nodes;
		uniform float Scale;
		out vec3 vPos;
		out vec3 vNormal;
		out vec2 vUv;
		out vec3 vRest;
		void main() {
		    vec3 o = texelFetch(Nodes, Frame.x).xyz;
		    vec3 p = o + Local;
		    vec3 n = LocalN;
		    if (Frame.y >= 0) {
		        vec3 a = texelFetch(Nodes, Frame.y).xyz - o;
		        vec3 b = texelFetch(Nodes, Frame.z).xyz - o;
		        vec3 t1 = a / max(length(a), 1e-9);
		        vec3 c = cross(t1, b);
		        vec3 t3 = c / max(length(c), 1e-9);
		        vec3 t2 = cross(t3, t1);
		        p = o + Local.x * t1 + Local.y * t2 + Local.z * t3;
		        n = LocalN.x * t1 + LocalN.y * t2 + LocalN.z * t3;
		    }
		    vPos = p * Scale;
		    vNormal = n;
		    vUv = UV0;
		    vRest = Rest;
		    gl_Position = vec4(0.0);
		}
		""";

	// One triangle in, one out in entity layout; a torn one (CarShader's rule) collapsed to a point.
	// Tangents from the triangle's UVs, as Iris computes them for an entity quad (NormalHelper).
	private static final String GEOMETRY = """
		#version 150
		layout(triangles) in;
		layout(triangle_strip, max_vertices = 3) out;
		in vec3 vPos[];
		in vec3 vNormal[];
		in vec2 vUv[];
		in vec3 vRest[];
		uniform float Scale;
		uniform uint Tint;
		uniform uint Light;
		uniform uint Overlay;
		out vec3 tfPos;
		flat out uint tfColor;
		out vec2 tfUv;
		flat out uint tfOverlay;
		flat out uint tfLight;
		flat out uint tfNormal;
		flat out uvec2 tfEntity;
		out vec2 tfMid;
		flat out uint tfTangent;
		uint packI8(vec4 v) {
		    ivec4 q = ivec4(round(clamp(v, -1.0, 1.0) * 127.0));
		    return uint(q.x & 255) | (uint(q.y & 255) << 8u) | (uint(q.z & 255) << 16u) | (uint(q.w & 255) << 24u);
		}
		void main() {
		    bool torn = false;
		    for (int i = 0; i < 3; i++) {
		        int j = (i + 1) % 3;
		        if (distance(vPos[i], vPos[j]) > Scale * (2.0 * distance(vRest[i], vRest[j]) + 0.1)) torn = true;
		    }
		    vec3 e1 = vPos[1] - vPos[0], e2 = vPos[2] - vPos[0];
		    vec2 d1 = vUv[1] - vUv[0], d2 = vUv[2] - vUv[0];
		    float r = d1.x * d2.y - d2.x * d1.y;
		    bool uvOk = abs(r) > 1e-12;
		    vec3 t = uvOk ? (e1 * d2.y - e2 * d1.y) / r : e1;
		    vec3 b = uvOk ? (e2 * d1.x - e1 * d2.x) / r : e2;
		    vec2 mid = (vUv[0] + vUv[1] + vUv[2]) / 3.0;
		    for (int i = 0; i < 3; i++) {
		        float ln = length(vNormal[i]);
		        vec3 n = ln > 1e-9 ? vNormal[i] / ln : vec3(0.0, 1.0, 0.0);
		        vec3 ti = t - n * dot(n, t);
		        if (length(ti) < 1e-9) ti = cross(n, abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0));
		        ti = normalize(ti);
		        float w = dot(cross(n, ti), b) < 0.0 ? -1.0 : 1.0;
		        tfPos = torn ? vPos[0] : vPos[i];
		        tfColor = Tint;
		        tfUv = vUv[i];
		        tfOverlay = Overlay;
		        tfLight = Light;
		        tfNormal = packI8(vec4(n, 0.0));
		        tfEntity = uvec2(0xFFFFFFFFu, 0x0000FFFFu);   // shorts -1, -1, -1: no id in the pack's entity.properties
		        tfMid = mid;
		        tfTangent = packI8(vec4(ti, w));
		        gl_Position = vec4(0.0);
		        EmitVertex();
		    }
		    EndPrimitive();
		}
		""";
}
