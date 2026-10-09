package dev.bngmc.bridge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cars under a shader pack (ShaderCompat). A pack renders the world into its own buffers and
 * by the end of the frame has rewritten the depth buffer, so the cars can neither go among its
 * entities (painted over) nor on the finished picture with a depth test (seen: hidden
 * everywhere). Instead, when the entities are drawn: a target of our own, its depth a copy of the
 * pack's depth so far (terrain and entities), its colour cleared; the cars are drawn into it with
 * their own shader, depth-tested against the world; and once the pack has finished the frame, that
 * colour goes over it (premultiplied alpha). The cars keep their own lighting, not the pack's.
 */
final class ShaderCarTarget {
	private ShaderCarTarget() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static int fbo, color, depth, width, height, depthFormat;
	/**
	 * The pack's depth texture the world's depth was copied from. By the time the cars go over the
	 * frame the pack has drawn the hand (and glass, water) into it in front of what was there: where
	 * it is nearer than the car, the car stays behind (the car painted over Jas's hand, 2026-10-05).
	 */
	private static int packDepth;
	private static int program = 0, vao, uDebug = -1;
	/** Dev (native shaderdebug N): 1 tints the overlay red to show it lands, 2 draws the cars without the world's depth. */
	static int debug;
	private static boolean pending, failed, blitChecked;
	private static int prevFbo, prevReadFbo;
	private static int skips;   // frames in a row the pack's framebuffer couldn't be used
	private static final int[] PREV_VIEWPORT = new int[4];

	/** Entity time, a pack drawing: our target bound, the world's depth copied in. False: draw nothing. */
	static boolean begin() {
		if (failed) {
			return false;
		}
		prevFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
			GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
		if (type != GL11.GL_TEXTURE) {
			return skip("the pack's framebuffer " + prevFbo + " has no depth texture (" + type + ")");
		}
		int src = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
			GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, src);
		int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
		int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
		int fmt = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
		if (w <= 0 || h <= 0) {
			return skip("the pack's depth texture " + src + " is " + w + "x" + h);   // a minimised window
		}
		packDepth = src;
		skips = 0;
		if (fbo == 0 || w != width || h != height || fmt != depthFormat) {
			if (!create(w, h, fmt)) {
				return false;
			}
		}
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, PREV_VIEWPORT);
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevFbo);
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
		boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), scissorOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		GL11.glDepthMask(true);   // a pack may hold the mask off during its passes
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
		GL11.glDepthMask(depthMask);
		if (scissorOn) {
			GL11.glEnable(GL11.GL_SCISSOR_TEST);
		}
		if (debug == 2) {
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
			boolean mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
			GL11.glDepthMask(true);
			GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
			GL11.glDepthMask(mask);
		}
		if (!blitChecked) {
			blitChecked = true;
			LOG.info("Car target: depth copied from framebuffer {} (depth texture {}), GL error {}", prevFbo, src, GL11.glGetError());
		}
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		GL11.glViewport(0, 0, w, h);
		boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
			java.nio.ByteBuffer mask = st.malloc(4);
			java.nio.FloatBuffer clear = st.mallocFloat(4);
			GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
			GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
			GL11.glDisable(GL11.GL_SCISSOR_TEST);
			GL11.glColorMask(true, true, true, true);
			GL11.glClearColor(0, 0, 0, 0);
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
			// put back: GlStateManager remembers the clear colour and mask it last set and skips setting them again
			GL11.glClearColor(clear.get(0), clear.get(1), clear.get(2), clear.get(3));
			GL11.glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
		}
		if (scissor) {
			GL11.glEnable(GL11.GL_SCISSOR_TEST);
		}
		return true;   // NativeCars sets (and puts back) the rest with plain GL
	}

	/** After the cars: the pack's framebuffers and viewport back. */
	static void end(boolean drew) {
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevFbo);
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
		GL11.glViewport(PREV_VIEWPORT[0], PREV_VIEWPORT[1], PREV_VIEWPORT[2], PREV_VIEWPORT[3]);
		pending = drew;
	}

	/** The pack has finished the frame (main framebuffer bound): the cars over it. */
	static void composite(int mainFbo, int w, int h) {
		if (!pending || failed) {
			return;
		}
		pending = false;
		if (program == 0 && !buildProgram()) {
			return;
		}
		int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		int prevActive = GlStateManager._getActiveTexture();
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, PREV_VIEWPORT);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
		GL11.glViewport(0, 0, w, h);
		// plain GL, put back after: the pack's and GlStateManager's records of this state stay true
		boolean blendWas = GL11.glIsEnabled(GL11.GL_BLEND), depthWas = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		int[] prevBlend = {GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
			GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)};
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glEnable(GL11.GL_BLEND);
		GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		boolean[] mask = colorMask();
		GL11.glColorMask(true, true, true, true);
		GL20.glUseProgram(program);
		GL20.glUniform1i(uDebug, debug);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
		GL13.glActiveTexture(GL13.GL_TEXTURE1);
		int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
		GL13.glActiveTexture(GL13.GL_TEXTURE2);
		int prevTex2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		boolean havePack = packDepth != 0 && GL11.glIsTexture(packDepth);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, havePack ? packDepth : depth);
		GL30.glBindVertexArray(vao);
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
		GL30.glBindVertexArray(prevVao);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2);
		GL13.glActiveTexture(GL13.GL_TEXTURE1);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
		GL13.glActiveTexture(prevActive);
		GL20.glUseProgram(prevProgram);
		GL14.glBlendFuncSeparate(prevBlend[0], prevBlend[1], prevBlend[2], prevBlend[3]);
		setCap(GL11.GL_BLEND, blendWas);
		setCap(GL11.GL_DEPTH_TEST, depthWas);
		GL11.glColorMask(mask[0], mask[1], mask[2], mask[3]);
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
		GL11.glViewport(PREV_VIEWPORT[0], PREV_VIEWPORT[1], PREV_VIEWPORT[2], PREV_VIEWPORT[3]);
	}

	/** The pack went off: nothing left to put over a frame, and the two screen-sized textures go. */
	static void release() {
		pending = false;
		delete();
	}

	private static boolean[] colorMask() {
		try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
			java.nio.ByteBuffer m = st.malloc(4);
			GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, m);
			return new boolean[] {m.get(0) != 0, m.get(1) != 0, m.get(2) != 0, m.get(3) != 0};
		}
	}

	private static boolean create(int w, int h, int fmt) {
		delete();
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		color = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		boolean stencil = fmt == GL30.GL_DEPTH24_STENCIL8 || fmt == GL30.GL_DEPTH32F_STENCIL8;
		depth = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
		if (stencil) {
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, fmt, w, h, 0, GL30.GL_DEPTH_STENCIL,
				fmt == GL30.GL_DEPTH24_STENCIL8 ? GL30.GL_UNSIGNED_INT_24_8 : GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV, (java.nio.ByteBuffer) null);
		} else {
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, fmt, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
		}
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
		int prev = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		fbo = GL30.glGenFramebuffers();
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, stencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D,
			depth, 0);
		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			delete();
			return fail("our car target isn't complete (" + Integer.toHexString(status) + ") for depth format " + Integer.toHexString(fmt));
		}
		width = w;
		height = h;
		depthFormat = fmt;
		LOG.info("Car target for shader packs: {}x{}, depth format 0x{}", w, h, Integer.toHexString(fmt));
		return true;
	}

	private static void delete() {
		if (fbo != 0) {
			GL30.glDeleteFramebuffers(fbo);
			GL11.glDeleteTextures(color);
			GL11.glDeleteTextures(depth);
			fbo = color = depth = 0;
		}
	}

	private static boolean buildProgram() {
		int v = shader(GL20.GL_VERTEX_SHADER, """
			#version 150
			out vec2 uv;
			void main() {
			    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);   // one triangle over the screen
			    uv = p;
			    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
			}
			""");
		int f = shader(GL20.GL_FRAGMENT_SHADER, """
			#version 150
			uniform sampler2D Cars;
			uniform sampler2D CarDepth;    // the world's depth at entity time with the cars in it
			uniform sampler2D PackDepth;   // the pack's depth now: the hand, glass and water drawn since
			uniform int Debug;
			in vec2 uv;
			out vec4 fragColor;
			void main() {
			    vec4 c = texture(Cars, uv);
			    if (c.a > 0.0 && Debug != 2 && texture(PackDepth, uv).r < texture(CarDepth, uv).r - 1e-6) discard;
			    fragColor = Debug == 1 ? mix(vec4(0.4, 0.0, 0.0, 0.4), vec4(c.rgb, 1.0), c.a) : c;
			}
			""");
		if (v == 0 || f == 0) {
			return fail("the overlay shader didn't compile");
		}
		int p = GL20.glCreateProgram();
		GL20.glAttachShader(p, v);
		GL20.glAttachShader(p, f);
		GL20.glLinkProgram(p);
		if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
			return fail("the overlay shader didn't link: " + GL20.glGetProgramInfoLog(p));
		}
		int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		GL20.glUseProgram(p);
		GL20.glUniform1i(GL20.glGetUniformLocation(p, "Cars"), 0);
		GL20.glUniform1i(GL20.glGetUniformLocation(p, "CarDepth"), 1);
		GL20.glUniform1i(GL20.glGetUniformLocation(p, "PackDepth"), 2);
		uDebug = GL20.glGetUniformLocation(p, "Debug");
		GL20.glUseProgram(prevProgram);
		vao = GL30.glGenVertexArrays();
		program = p;
		return true;
	}

	private static int shader(int type, String src) {
		int s = GL20.glCreateShader(type);
		GL20.glShaderSource(s, src);
		GL20.glCompileShader(s);
		if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
			LOG.warn("Shader pack car overlay: {}", GL20.glGetShaderInfoLog(s));
			return 0;
		}
		return s;
	}

	private static void setCap(int cap, boolean on) {
		if (on) {
			GL11.glEnable(cap);
		} else {
			GL11.glDisable(cap);
		}
	}

	/** This frame can't be used; after a few hundred in a row, give up (logged once). */
	private static boolean skip(String why) {
		if (++skips == 300) {
			LOG.warn("Cars under this shader pack skipped for 300 frames: {}", why);
		}
		return false;
	}

	private static boolean fail(String why) {
		failed = true;
		LOG.warn("Cars can't be drawn under this shader pack: {}", why);
		return false;
	}
}
