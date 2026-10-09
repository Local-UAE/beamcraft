package dev.bngmc.bridge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.entity.BngVehicleEntity;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import dev.bngmc.bridge.link.Wire;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * BeamNG's vehicle triggers from Minecraft: look at a door handle (or the bonnet, the boot, a switch
 * in the cabin) and use it, as clicking it does in BeamNG. Each tick near a car the eye's ray goes to
 * BeamNG (TRIGGER_AIM), which tests it against the car's trigger boxes (triggers.lua) and answers
 * with the one hit (TRIGGER_HIT); that one is outlined, its action named under the crosshair, and
 * Minecraft's use key (right-click, a controller's use button) fires it (TRIGGER_USE) instead of
 * whatever the use key would have done. In the driver's seat: right-click or the D-pad left (the
 * use button there is the brake).
 */
public final class CarTriggers {
	private CarTriggers() {
	}

	/** m from the eye. */
	private static final double REACH_M = 3.0;
	/** Blocks: cars this close get asked about. */
	private static final double NEAR_BLOCKS = 12.0;
	/** A hit older than this is gone (the answer to every tick's ray, so a few ticks). */
	private static final long HIT_MAX_AGE_MS = 300;
	private static final String[] ACTIONS = {"action0", "action1", "action2"};

	/** A trigger BeamNG says the ray hits, in Minecraft coordinates (centre, unit axes, half sizes in blocks). */
	record Hit(long vid, JsonElement tr, int action, String title, V3 centre, V3[] axes, double[] half, double rxMs) {
	}

	private static volatile Hit hit;
	private static int seq;
	private static Hit pressed;
	private static boolean padWasDown;
	/** The use key was physically down in a frame since the last tick (a tap can be over by the tick). */
	private static boolean useSeen;
	/**
	 * Handles are looked for and outlined only while this is on (J on foot): the yellow boxes on every
	 * car were in the way (Jas, 2026-10-03), and with it off right-click on a car gets in as before.
	 */
	private static boolean shown;
	/** Blocks: the outline is at most this far from its middle each way, a marker rather than the whole box. */
	private static final double MAX_DRAWN_HALF = 0.15;

	/** J on foot: handles shown or hidden. Returns the new state. */
	public static boolean toggleShown() {
		shown = !shown;
		hit = null;
		return shown;
	}

	public static void install(BngLink link) {
		link.onMessage(Protocol.TRIGGER_HIT, CarTriggers::onHit);
	}

	/** I/O thread. */
	private static void onHit(Wire.Envelope env) {
		JsonObject b = env.body();
		CrossoverCoords.Region region = BngWorld.region();
		if (region == null || !b.has("found") || !b.get("found").getAsBoolean()) {
			hit = null;
			return;
		}
		try {
			JsonObject actions = b.getAsJsonObject("actions");
			int action = -1;
			String title = "";
			for (int i = 0; i < ACTIONS.length && action < 0; i++) {
				if (actions != null && actions.has(ACTIONS[i])) {
					action = i;
					title = actions.get(ACTIONS[i]).getAsString();
				}
			}
			if (action < 0) {
				hit = null;
				return;
			}
			V3 centre = CrossoverCoords.canonicalToMinecraftPosition(CrossoverCoords.beamngToCanonicalPosition(vec(b.getAsJsonArray("c"))), region);
			JsonArray a = b.getAsJsonArray("a"), h = b.getAsJsonArray("h");
			V3[] axes = new V3[3];
			double[] half = new double[3];
			for (int i = 0; i < 3; i++) {
				axes[i] = CrossoverCoords.canonicalToMinecraftDirection(CrossoverCoords.beamngToCanonicalDirection(vec(a.get(i).getAsJsonArray())));
				half[i] = h.get(i).getAsDouble() * region.scale();
			}
			hit = new Hit(b.get("vid").getAsLong(), b.get("tr"), action, title, centre, axes, half, BngLink.nowMs());
		} catch (RuntimeException e) {
			hit = null;   // a malformed answer: no trigger rather than a broken tick
		}
	}

	private static V3 vec(JsonArray a) {
		return new V3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
	}

	/** The trigger under the crosshair now, or null. */
	static Hit hovered(Minecraft mc) {
		Hit h = hit;
		return h != null && mc.screen == null && BngLink.nowMs() - h.rxMs() < HIT_MAX_AGE_MS ? h : null;
	}

	/**
	 * Start of the client tick, before Minecraft handles its keys: the use key on a trigger fires it
	 * and is taken from Minecraft (a click on the car would otherwise get the player in, and a held
	 * key repeats its use). Then this tick's ray goes to BeamNG.
	 */
	public static void startTick(Minecraft mc) {
		boolean on = shown && mc.player != null && BngWorld.isHostWorld() && BngLink.get().connected();
		Hit h = on ? hovered(mc) : null;
		boolean seated = on && HostDrive.seated(mc);
		boolean click = false;
		if (h != null) {
			while (mc.options.keyUse.consumeClick()) {
				click = true;
			}
			if (click) {
				mc.options.keyUse.setDown(false);
			}
			// seated, the use key may be the controller's brake trigger: only a real mouse/key press counts
			click &= !seated || useSeen || useKeyDown(mc);
		}
		useSeen = false;
		boolean pad = seated && HostDrive.padButton(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT);
		if (pressed == null && h != null && (click || pad && !padWasDown)) {
			pressed = h;
			use(h, true);
		} else if (pressed != null && !click && !useKeyDown(mc) && !pad) {
			use(pressed, false);   // the held state follows the button, not the hit: a flickering hit doesn't press twice
			pressed = null;
		}
		padWasDown = pad;
		if (on) {
			aim(mc);
		} else {
			hit = null;
		}
	}

	/** Every frame (WorldRenderEvents.START): notes a use key press too short to be down at the tick. */
	public static void frame(Minecraft mc) {
		useSeen |= mc.screen == null && useKeyDown(mc);
	}

	/** The use key's own state (its press was taken from Minecraft, so KeyMapping.isDown() is false). */
	private static boolean useKeyDown(Minecraft mc) {
		InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mc.options.keyUse);
		if (key.getValue() < 0) {
			return false;   // unbound
		}
		long window = mc.getWindow().getWindow();
		if (key.getType() == InputConstants.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
		}
		return key.getType() == InputConstants.Type.KEYSYM && InputConstants.isKeyDown(window, key.getValue());
	}

	private static void use(Hit h, boolean down) {
		JsonObject msg = new JsonObject();
		msg.addProperty("vid", h.vid());
		msg.add("tr", h.tr());
		msg.addProperty("action", h.action());
		msg.addProperty("down", down);
		BngLink.get().send(Protocol.TRIGGER_USE, msg);
	}

	/** The eye's ray to BeamNG (the camera's in first person, else the player's own), if a car is near; else no trigger. */
	private static void aim(Minecraft mc) {
		CrossoverCoords.Region region = BngWorld.region();
		if (region == null || mc.level == null
			|| mc.level.getEntitiesOfClass(BngVehicleEntity.class, mc.player.getBoundingBox().inflate(NEAR_BLOCKS)).isEmpty()) {
			hit = null;
			return;
		}
		Vec3 p;
		Vector3f look;
		if (mc.options.getCameraType().isFirstPerson()) {
			Camera camera = mc.gameRenderer.getMainCamera();   // seated: the driver's eye in BeamNG's car
			p = camera.getPosition();
			look = camera.getLookVector();
		} else {
			p = mc.player.getEyePosition();
			look = mc.player.getViewVector(1.0F).toVector3f();
		}
		V3 o = CrossoverCoords.canonicalToBeamngPosition(CrossoverCoords.minecraftToCanonicalPosition(new V3(p.x, p.y, p.z), region));
		V3 d = CrossoverCoords.canonicalToBeamngDirection(CrossoverCoords.minecraftToCanonicalDirection(new V3(look.x(), look.y(), look.z())));
		JsonObject msg = new JsonObject();
		msg.add("o", array(o));
		msg.add("d", array(d));
		msg.addProperty("reach", REACH_M);
		msg.addProperty("n", ++seq);
		BngLink.get().send(Protocol.TRIGGER_AIM, msg);
	}

	private static JsonArray array(V3 v) {
		JsonArray a = new JsonArray();
		a.add(v.x());
		a.add(v.y());
		a.add(v.z());
		return a;
	}

	/** WorldRenderEvents.AFTER_ENTITIES: the hovered trigger's box, outlined. */
	public static void render(WorldRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		Hit h = hovered(mc);
		if (h == null || ctx.consumers() == null) {
			return;
		}
		Vec3 cam = ctx.camera().getPosition();
		V3[] c = new V3[8];
		for (int i = 0; i < 8; i++) {
			double sx = ((i & 1) == 0 ? -1 : 1) * Math.min(1, MAX_DRAWN_HALF / Math.max(1e-6, h.half()[0])),
				sy = ((i & 2) == 0 ? -1 : 1) * Math.min(1, MAX_DRAWN_HALF / Math.max(1e-6, h.half()[1])),
				sz = ((i & 4) == 0 ? -1 : 1) * Math.min(1, MAX_DRAWN_HALF / Math.max(1e-6, h.half()[2]));
			c[i] = new V3(
				h.centre().x() - cam.x + sx * h.half()[0] * h.axes()[0].x() + sy * h.half()[1] * h.axes()[1].x() + sz * h.half()[2] * h.axes()[2].x(),
				h.centre().y() - cam.y + sx * h.half()[0] * h.axes()[0].y() + sy * h.half()[1] * h.axes()[1].y() + sz * h.half()[2] * h.axes()[2].y(),
				h.centre().z() - cam.z + sx * h.half()[0] * h.axes()[0].z() + sy * h.half()[1] * h.axes()[1].z() + sz * h.half()[2] * h.axes()[2].z());
		}
		PoseStack.Pose pose = new PoseStack().last();
		VertexConsumer lines = ctx.consumers().getBuffer(RenderType.lines());
		int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] e : edges) {
			V3 a = c[e[0]], b = c[e[1]];
			float nx = (float) (b.x() - a.x()), ny = (float) (b.y() - a.y()), nz = (float) (b.z() - a.z());
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1e-6F) {
				continue;
			}
			lines.addVertex(pose, (float) a.x(), (float) a.y(), (float) a.z()).setColor(1F, 0.85F, 0.2F, 1F).setNormal(pose, nx / len, ny / len, nz / len);
			lines.addVertex(pose, (float) b.x(), (float) b.y(), (float) b.z()).setColor(1F, 0.85F, 0.2F, 1F).setNormal(pose, nx / len, ny / len, nz / len);
		}
	}

	/** HUD: what the use key does to the trigger under the crosshair. */
	public static void renderHud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		Hit h = hovered(mc);
		if (h == null || mc.options.hideGui) {
			return;
		}
		String key = mc.options.keyUse.getTranslatedKeyMessage().getString();
		String text = (HostDrive.seated(mc) ? key + " / D-pad left" : key) + "   " + h.title();
		int w = mc.font.width(text), x = (g.guiWidth() - w) / 2, y = g.guiHeight() / 2 + 14;
		g.fill(x - 4, y - 3, x + w + 4, y + 11, 0x90000000);
		g.drawString(mc.font, text, x, y, 0xFFFFD84D, true);
	}
}
