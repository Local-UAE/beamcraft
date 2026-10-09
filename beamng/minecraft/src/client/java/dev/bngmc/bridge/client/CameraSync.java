package dev.bngmc.bridge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.Quat;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.BngState;
import dev.bngmc.bridge.link.Protocol;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Keeps the two cameras identical (adapted from upstream's CameraSync).
 * <ul>
 *   <li>DRIVE: BeamNG renders from Minecraft's camera. Every Minecraft frame, right after
 *   Camera.setup, the eye position, look and up vectors and the vertical FOV go to BeamNG in
 *   canonical coordinates. BeamNG's camera filter applies the newest one each frame.</li>
 *   <li>FOLLOW: Minecraft renders from BeamNG's camera (a check of the coordinate mapping).</li>
 *   <li>OFF: each game has its own camera.</li>
 * </ul>
 */
public final class CameraSync {
	private CameraSync() {
	}

	public enum Mode {
		DRIVE,
		FOLLOW,
		OFF
	}

	public interface CameraAccess {
		void bngbridge$setPosition(Vec3 pos);

		void bngbridge$setRotation(float yaw, float pitch);
	}

	/** Upper bound on camera messages: BeamNG reads the newest each frame, more is wasted. */
	private static final long MIN_SEND_INTERVAL_NS = 1_000_000_000L / 240;

	private static Mode mode = Mode.DRIVE;
	private static boolean driving;
	private static long cseq;
	private static long lastSendNs;
	private static float fov = 70.0F;
	// What was sent last, for the HUD.
	private static volatile V3 lastPos = V3.ZERO;
	private static volatile V3 lastFwd = new V3(0, 1, 0);

	public static Mode mode() {
		return mode;
	}

	public static Mode cycleMode() {
		mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
		return mode;
	}

	public static void setMode(Mode m) {
		mode = m;
	}

	/** True while Minecraft drives BeamNG's camera this frame. */
	public static boolean driving() {
		return driving;
	}

	/** True while the FOV and view effects must be locked so both projections stay identical. */
	public static boolean locked() {
		return mode != Mode.OFF && ControlSwitch.minecraftMode() && BngLink.get().connected() && BngWorld.region() != null;
	}

	public static float fov() {
		return fov;
	}

	public static long cseq() {
		return cseq;
	}

	public static V3 lastPos() {
		return lastPos;
	}

	public static V3 lastFwd() {
		return lastFwd;
	}

	/** Camera.setup TAIL, render thread, once per frame. */
	public static void afterCameraSetup(Camera camera) {
		Minecraft mc = Minecraft.getInstance();
		BngLink link = BngLink.get();
		CrossoverCoords.Region region = BngWorld.region();
		fov = mc.options.fov().get();
		NativeCars.beginFrame();
		HostDrive.applyChaseCamera(camera);
		boolean ready = ControlSwitch.minecraftMode() && link.connected() && region != null && BngWorld.isLinkedWorld() && mc.player != null
			&& region.containsMcX(mc.player.getX()) && BngWorld.recallServed() > 0;
		if (!ready || mode == Mode.OFF) {
			release(link);
			return;
		}
		if (mode == Mode.FOLLOW) {
			release(link);
			follow(camera, link, region);
			return;
		}
		long now = System.nanoTime();
		if (now - lastSendNs < MIN_SEND_INTERVAL_NS) {
			return;
		}
		lastSendNs = now;
		Vec3 p = camera.getPosition();
		Vector3f f = camera.getLookVector();
		Vector3f u = camera.getUpVector();
		V3 pos = CrossoverCoords.minecraftToCanonicalPosition(new V3(p.x, p.y, p.z), region);
		V3 fwd = CrossoverCoords.minecraftToCanonicalDirection(new V3(f.x(), f.y(), f.z()));
		V3 up = CrossoverCoords.minecraftToCanonicalDirection(new V3(u.x(), u.y(), u.z()));
		JsonObject msg = new JsonObject();
		msg.addProperty("cseq", ++cseq);
		msg.add("pos", arr(pos));
		msg.add("fwd", arr(fwd));
		msg.add("up", arr(up));
		msg.addProperty("fovV", fov);
		driving = link.send(Protocol.CAMERA, msg);
		lastPos = pos;
		lastFwd = fwd;
	}

	private static void follow(Camera camera, BngLink link, CrossoverCoords.Region region) {
		var env = link.latest(Protocol.STATE);
		if (env == null) {
			return;
		}
		BngState st = BngState.parse(env, BngLink.nowMs());
		if (st.camera() == null || st.camera().pos() == null) {
			return;
		}
		V3 mc = CrossoverCoords.canonicalToMinecraftPosition(st.camera().pos(), region);
		Quat q = CrossoverCoords.beamngToCanonicalRotation(st.camera().rot());
		double[] yp = CrossoverCoords.canonicalCameraToMinecraft(q);
		CameraAccess access = (CameraAccess) camera;
		access.bngbridge$setRotation((float) yp[0], (float) yp[1]);
		access.bngbridge$setPosition(new Vec3(mc.x(), mc.y(), mc.z()));
		if (!Double.isNaN(st.camera().fovDeg()) && st.camera().fovDeg() > 1) {
			fov = (float) st.camera().fovDeg();
		}
	}

	private static void release(BngLink link) {
		if (driving) {
			link.send(Protocol.CAMERA_RELEASE, null);
			driving = false;
		}
	}

	private static JsonArray arr(V3 v) {
		JsonArray a = new JsonArray();
		a.add(v.x());
		a.add(v.y());
		a.add(v.z());
		return a;
	}
}
