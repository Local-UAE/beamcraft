package dev.bngmc.bridge.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import dev.bngmc.bridge.link.VehicleMeshes;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Minecraft-hosted worlds (docs/minecraft-host.md): the real BeamNG cars show through Minecraft's
 * picture. BeamNG renders from Minecraft's camera in the window behind this one (CameraSync,
 * Overlay). Here, after the world is drawn, the frame is made fully opaque and every car's skin
 * (VehicleMeshes) is then written as transparent pixels wherever it is the nearest surface:
 * blocks and mobs in front of a car keep their pixels, the car covers what is behind it. The hand
 * and the HUD are drawn afterwards, on top. The final blit copies alpha to the window
 * (RenderTargetMixin), and Windows composites BeamNG's frame into the transparent pixels.
 */
public final class CarCutouts {
	private CarCutouts() {
	}

	private static final double RANGE = 250;               // m around the camera
	private static final double STALE_MS = 500;            // a car's shape older than this is dropped
	private static final long TRIS_RETRY_MS = 1000;
	private static final int GL_COLOR_BUFFER_BIT = 0x4000;

	private static boolean enabled = !Boolean.getBoolean("bngbridge.noCutouts");
	private static long subscribedSid = -1;
	private static final Map<Long, Long> trisAskedMs = new HashMap<>();
	private static volatile int lastCars;
	private static volatile int lastTriangles;

	public static boolean toggle() {
		enabled = !enabled;
		return enabled;
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Cars and triangles drawn last frame, for the status HUD. */
	public static String describe() {
		return lastCars + " cars, " + lastTriangles + " triangles";
	}

	private static boolean active() {
		return enabled && BngWorld.isHostWorld() && Overlay.active() && !NativeCars.active() && BngWorld.region() != null;
	}

	/** Client tick: keeps BeamNG's shape stream subscribed while it is needed, asks for lost triangles. */
	public static void tick(Minecraft mc) {
		BngLink link = BngLink.get();
		if (!link.connected()) {
			subscribedSid = -1;
			return;
		}
		boolean want = BngWorld.isHostWorld() && BngWorld.region() != null;
		if (want && subscribedSid != link.sessionId()) {
			JsonObject on = new JsonObject();
			on.addProperty("on", true);
			on.addProperty("range", RANGE);
			if (link.send(Protocol.VMESH_SUB, on)) {
				subscribedSid = link.sessionId();
			}
		} else if (!want && subscribedSid != -1) {
			JsonObject off = new JsonObject();
			off.addProperty("on", false);
			link.send(Protocol.VMESH_SUB, off);
			subscribedSid = -1;
			VehicleMeshes.clear();
		}
		double now = BngLink.nowMs();
		VehicleMeshes.prune(now, STALE_MS);
		long nowMs = System.currentTimeMillis();
		for (VehicleMeshes.Mesh m : VehicleMeshes.meshes().values()) {
			if (VehicleMeshes.needsTris(m, VehicleMeshes.tris(m.id())) && nowMs - trisAskedMs.getOrDefault(m.id(), 0L) > TRIS_RETRY_MS) {
				trisAskedMs.put(m.id(), nowMs);
				JsonObject req = new JsonObject();
				req.addProperty("id", m.id());
				link.send(Protocol.VTRIS_REQ, req);
			}
		}
	}

	/** WorldRenderEvents.LAST, render thread. */
	public static void render(WorldRenderContext ctx) {
		if (!active()) {
			lastCars = 0;
			lastTriangles = 0;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.getMainRenderTarget().bindWrite(false);

		// Everything Minecraft drew is opaque: the sky and translucent layers can leave alpha < 1.
		RenderSystem.colorMask(false, false, false, true);
		RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 1.0F);
		RenderSystem.clear(GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
		RenderSystem.colorMask(true, true, true, true);

		CrossoverCoords.Region region = BngWorld.region();
		Vec3 cam = ctx.camera().getPosition();
		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
		int cars = 0, tris = 0;
		double now = BngLink.nowMs();
		for (VehicleMeshes.Mesh m : VehicleMeshes.meshes().values()) {
			VehicleMeshes.Tris t = VehicleMeshes.tris(m.id());
			if (!VehicleMeshes.drawable(m, t) || now - m.rxMs() > STALE_MS) {   // e.g. BeamNG just went away
				continue;
			}
			float[] v = VehicleMeshes.cameraRelativeTriangles(m, t, region, cam.x, cam.y, cam.z);
			for (int i = 0; i + 2 < v.length; i += 3) {
				buf.addVertex(v[i], v[i + 1], v[i + 2]);
			}
			cars++;
			tris += v.length / 9;
		}
		lastCars = cars;
		lastTriangles = tris;
		MeshData mesh = buf.build();
		if (mesh == null) {
			return;
		}

		Matrix4fStack mv = RenderSystem.getModelViewStack();
		mv.pushMatrix();
		mv.identity();
		mv.mul(ctx.positionMatrix());
		RenderSystem.applyModelViewMatrix();
		RenderSystem.setShader(GameRenderer::getPositionShader);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		// Blend factors of zero write (0, 0, 0, 0) whatever the shader outputs: fully transparent,
		// premultiplied, so the window behind shows through unchanged.
		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ZERO,
			GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ZERO);
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(515);   // GL_LEQUAL
		RenderSystem.depthMask(false);
		RenderSystem.disableCull();
		BufferUploader.drawWithShader(mesh);
		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
		mv.popMatrix();
		RenderSystem.applyModelViewMatrix();
	}
}
