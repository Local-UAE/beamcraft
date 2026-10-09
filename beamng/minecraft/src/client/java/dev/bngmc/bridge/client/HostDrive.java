package dev.bngmc.bridge.client;

import com.google.gson.JsonObject;
import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.DriveInputs;
import dev.bngmc.bridge.WheelInputs;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.entity.BngVehicleEntity;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.MeshTimeline;
import dev.bngmc.bridge.link.Protocol;
import dev.bngmc.bridge.link.Vehicles;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Driving a BeamNG car from a Minecraft-hosted world (docs/minecraft-host.md). Right-clicking a
 * car seats Steve in its proxy (BngVehicleEntity, vanilla riding: sneak gets out) and makes it
 * BeamNG's player car. While seated, Minecraft keeps the keyboard and reads any game controller,
 * and sends VEHICLE_DRIVE every frame (at most 60 Hz) with a ttl, so BeamNG lets go of the
 * controls if Minecraft stops sending. Keys: W throttle, S brake (reverse when stopped), A/D steer,
 * Space handbrake. Controller: right/left trigger, left stick, B handbrake. Steve isn't drawn.
 *
 * <p>Perspectives, cycled with Minecraft's own F5 (controller: D-pad down, CarControls): third
 * person is a chase camera behind the car (mouse to look around, it swings back behind a moving
 * car); first person is the driver's seat, where BeamNG's own driver camera is (NativeCars.driverEye,
 * the view turning with the car and the mouse looking around inside it); third person front looks
 * back at the car from ahead of it.
 */
public final class HostDrive {
	private HostDrive() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static final long SEND_INTERVAL_NS = 1_000_000_000L / 60;
	private static final double TTL_S = 0.3;
	private static final long MOUSE_IDLE_MS = 1200;       // then the camera swings back behind the car
	private static final double FOLLOW_DEG_PER_S = 120;
	private static final double FOLLOW_MIN_SPEED = 2.0;   // m/s

	private static boolean wasSeated;
	private static CameraType savedCamera;
	/** The perspective last used in a car: getting in again starts there. */
	private static CameraType carCamera = CameraType.THIRD_PERSON_BACK;
	private static CameraType lastType;
	private static float lastCarYaw = Float.NaN, lastCarPitch = Float.NaN;
	private static boolean handHidden;
	private static long lastSendNs;
	private static long lastMouseMs;
	private static float lastYaw, lastPitch;
	private static long lastFrameNs;
	private static final DriveInputs.Pad PAD = new DriveInputs.Pad();
	private static final GLFWGamepadState PAD_STATE = GLFWGamepadState.create();
	private static WheelInputs wheelInputs = new WheelInputs();
	private static String wheelGuid = "";
	private static String wheelWarningGuid = "";
	private static ByteBuffer wheelButtons;
	private static volatile DriveInputs lastSent = DriveInputs.RELEASED;
	private static volatile String lastSource = "-";

	public static boolean seated(Minecraft mc) {
		return BngWorld.isHostWorld() && mc.player != null && mc.player.getVehicle() instanceof BngVehicleEntity;
	}

	/** Steve is inside the car: don't draw him (his head would stick out through the roof). */
	public static boolean hidden(Entity e) {
		Minecraft mc = Minecraft.getInstance();
		return e == mc.player && seated(mc);
	}

	public static String describe() {
		DriveInputs d = lastSent;
		String controls = String.format("%s throttle %.2f brake %.2f clutch %.2f steer %+.2f hb %.0f",
			lastSource, d.throttle(), d.brake(), d.clutch(), d.steering(), d.parkingbrake());
		if (wheelGuid.isEmpty()) {
			return controls;
		}
		int gear = wheelGear();
		String gearLabel = gear < 0 ? "R" : gear == 0 ? "N" : Integer.toString(gear);
		return controls + "   Logitech wheel ready   H " + gearLabel + "   " + wheelInputs.rawAxes();
	}

	/** Right-click on a car's proxy (the server seats the player): it becomes BeamNG's player car. */
	public static void enter(Minecraft mc, long bngId) {
		JsonObject msg = new JsonObject();
		msg.addProperty("id", bngId);
		BngLink.get().send(Protocol.ENTER_VEHICLE, msg);
		LOG.info("Getting into BeamNG car {}", bngId);
	}

	/** Once per frame (WorldRenderEvents.START), render thread. */
	public static void onFrame(Minecraft mc) {
		boolean seated = seated(mc);
		if (seated != wasSeated) {
			wasSeated = seated;
			if (seated) {
				savedCamera = mc.options.getCameraType();
				mc.options.setCameraType(carCamera);
				lastYaw = mc.player.getYRot();
				lastPitch = mc.player.getXRot();
				lastMouseMs = 0;
				lastType = null;
			} else {
				send(new DriveInputs(0, 0, 0, 0, 1, DriveInputs.FILTER_GAMEPAD), false);   // park it
				carCamera = mc.options.getCameraType();
				if (savedCamera != null) {
					mc.options.setCameraType(savedCamera);
				}
				LOG.info("Out of the BeamNG car");
			}
		}
		// no Minecraft hand or held item in front of the driver's view
		boolean hide = seated && mc.options.getCameraType() == CameraType.FIRST_PERSON;
		if (hide != handHidden) {
			handHidden = hide;
			mc.gameRenderer.setRenderHand(!hide);
		}
		if (!seated) {
			return;
		}
		long now = System.nanoTime();
		double dt = lastFrameNs == 0 ? 0 : Math.min(0.1, (now - lastFrameNs) / 1e9);
		lastFrameNs = now;
		DriveInputs pad = readPad();   // every frame: the right stick turns the camera smoothly
		lookWithRightStick(mc, dt);
		followHeading(mc, dt);
		if (now - lastSendNs < SEND_INTERVAL_NS) {
			return;
		}
		lastSendNs = now;
		DriveInputs wheel = readWheel();
		if (wheel != null && wheelInputs.active()) {
			lastSource = "wheel";
			send(wheel, true);
		} else if (pad != null && PAD.active()) {
			lastSource = "pad";
			send(pad, true);
		} else {
			boolean typing = mc.screen != null;
			lastSource = "keys";
			send(typing ? new DriveInputs(0, 0, 0, 0, 0, DriveInputs.FILTER_KEYBOARD)
				: DriveInputs.fromKeys(mc.options.keyUp.isDown(), mc.options.keyDown.isDown(), mc.options.keyLeft.isDown(),
				mc.options.keyRight.isDown(), mc.options.keyJump.isDown()), true);
		}
	}

	private static boolean padPresent;

	/** A controller button as read this frame (false without a controller). */
	public static boolean padButton(int button) {
		return padPresent && PAD_STATE.buttons(button) == GLFW.GLFW_PRESS;
	}

	public static boolean wheelButton(int button) {
		return wheelButtons != null && button >= 0 && button < wheelButtons.limit()
			&& wheelButtons.get(button) == GLFW.GLFW_PRESS;
	}

	/** Current G29/G920/G923 H-shifter gate position; zero means neutral. */
	public static int wheelGear() {
		int gear = 0;
		for (int button = WheelInputs.SHIFTER_FIRST_BUTTON; button <= WheelInputs.SHIFTER_LAST_BUTTON; button++) {
			if (!wheelButton(button)) {
				continue;
			}
			if (gear != 0) {
				return 0;
			}
			gear = WheelInputs.gearForButton(button);
		}
		return gear;
	}

	private static DriveInputs readPad() {
		padPresent = false;
		for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
			if (GLFW.glfwJoystickIsGamepad(jid)
				&& !WheelInputs.isSupportedDevice(GLFW.glfwGetJoystickName(jid))
				&& GLFW.glfwGetGamepadState(jid, PAD_STATE)) {
				padPresent = true;
				return PAD.update(PAD_STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X), PAD_STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER),
					PAD_STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER), PAD_STATE.buttons(GLFW.GLFW_GAMEPAD_BUTTON_B) == GLFW.GLFW_PRESS);
			}
		}
		return null;
	}

	private static DriveInputs readWheel() {
		boolean foundWheel = false;
		wheelButtons = null;
		for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
			if (!GLFW.glfwJoystickPresent(jid)) {
				continue;
			}
			String name = GLFW.glfwGetJoystickName(jid);
			if (!WheelInputs.isSupportedDevice(name)) {
				continue;
			}
			foundWheel = true;
			String guid = GLFW.glfwGetJoystickGUID(jid);
			if (guid == null) {
				guid = jid + ":" + name;
			}
			FloatBuffer axes = GLFW.glfwGetJoystickAxes(jid);
			if (axes == null || axes.limit() < 4) {
				if (!guid.equals(wheelWarningGuid)) {
					LOG.warn("Logitech wheel {} exposes {} axes; G29/G920/G923 need at least 4", name,
						axes == null ? 0 : axes.limit());
					wheelWarningGuid = guid;
				}
				continue;
			}
			if (!guid.equals(wheelGuid)) {
				wheelGuid = guid;
				wheelInputs = new WheelInputs();
				LOG.info("Detected Logitech steering wheel {} with {} axes", name, axes.limit());
			}
			wheelWarningGuid = "";
			wheelButtons = GLFW.glfwGetJoystickButtons(jid);
			return wheelInputs.update(axes.get(0), axes.get(1), axes.get(2), axes.get(3));
		}
		if (foundWheel) {
			return null;
		}
		if (!wheelGuid.isEmpty()) {
			LOG.info("Logitech steering wheel disconnected");
			wheelGuid = "";
			wheelInputs = new WheelInputs();
		}
		wheelButtons = null;
		wheelWarningGuid = "";
		return null;
	}

	private static final float STICK_DEADZONE = 0.15F;
	private static final float STICK_DEG_PER_S = 200F;

	/**
	 * Controller: the right stick swings the camera around the car, as in BeamNG (Minecraft has no
	 * gamepad look of its own). The mouse keeps working for keyboard players.
	 */
	private static void lookWithRightStick(Minecraft mc, double dt) {
		if (!padPresent) {
			return;
		}
		float rx = PAD_STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X), ry = PAD_STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y);
		rx = Math.abs(rx) < STICK_DEADZONE ? 0 : rx;
		ry = Math.abs(ry) < STICK_DEADZONE ? 0 : ry;
		if (rx == 0 && ry == 0) {
			return;
		}
		float yaw = mc.player.getYRot() + (float) (rx * STICK_DEG_PER_S * dt);
		float pitch = Math.max(-40F, Math.min(70F, mc.player.getXRot() + (float) (ry * STICK_DEG_PER_S * 0.6 * dt)));
		mc.player.setYRot(yaw);
		mc.player.yRotO = yaw;
		mc.player.setXRot(pitch);
		mc.player.xRotO = pitch;
		lastMouseMs = System.currentTimeMillis();   // a deliberate look: don't swing back yet
		lastYaw = yaw;
		lastPitch = pitch;
	}

	private static void send(DriveInputs in, boolean withTtl) {
		JsonObject msg = new JsonObject();
		msg.addProperty("throttle", in.throttle());
		msg.addProperty("brake", in.brake());
		msg.addProperty("steering", in.steering());
		msg.addProperty("clutch", in.clutch());
		msg.addProperty("parkingbrake", in.parkingbrake());
		msg.addProperty("filter", in.filter());
		if (withTtl) {
			msg.addProperty("ttl", TTL_S);
		}
		BngLink.get().send(Protocol.VEHICLE_DRIVE, msg);
		lastSent = in;
	}

	/** Minecraft blocks per metre where the car is (bigger cars, bigger chase distance). */
	private static double scale() {
		return BngWorld.region() != null ? BngWorld.region().scale() : 1.0;
	}

	private static final double PIVOT_TIME = 0.035;   // s
	private static final double PIVOT_SNAP = 4.0;     // m: further than this jumps (teleport, reset)
	private static V3 pivot;
	private static long lastCamNs;

	/**
	 * Centre of the car this frame, Minecraft coordinates: the blended pose that is drawn (box
	 * centre offset from the 20 Hz vehicle data), or that data alone before any VMESH arrived.
	 */
	private static V3 carCentre(Minecraft mc, CrossoverCoords.Region region) {
		Vehicles.Info v = car(mc);
		if (v == null) {
			return null;
		}
		MeshTimeline.Pose pose = NativeCars.pose(v.id());
		if (pose == null) {
			return CrossoverCoords.canonicalToMinecraftPosition(v.centerAt(BngLink.nowMs()), region);
		}
		V3 offset = v.center().sub(v.pos());   // ref node -> box centre, changes slowly
		return CrossoverCoords.canonicalToMinecraftPosition(pose.pos().add(offset), region);
	}

	/** The car this player sits in, from BeamNG's data, or null. */
	private static Vehicles.Info car(Minecraft mc) {
		if (!(mc.player.getVehicle() instanceof BngVehicleEntity proxy)) {
			return null;
		}
		return Vehicles.latest().get(proxy.bngId());
	}

	/**
	 * First person: the view turns with the car, so looking around with the mouse is relative to the
	 * seat. Switching into it, the view starts looking straight ahead along the car.
	 */
	private static void turnWithCar(Minecraft mc) {
		Vehicles.Info v = car(mc);
		NativeCars.Eye eye = v != null ? NativeCars.driverEye(v.id()) : null;
		if (eye == null) {
			lastCarYaw = Float.NaN;
			return;
		}
		if (lastType != CameraType.FIRST_PERSON || Float.isNaN(lastCarYaw)) {
			setLook(mc, eye.yaw(), eye.pitch());
		} else {
			setLook(mc, mc.player.getYRot() + wrap(eye.yaw() - lastCarYaw),
				Math.max(-90F, Math.min(90F, mc.player.getXRot() + (eye.pitch() - lastCarPitch))));
		}
		lastCarYaw = eye.yaw();
		lastCarPitch = eye.pitch();
	}

	private static void setLook(Minecraft mc, float yaw, float pitch) {
		mc.player.setYRot(yaw);
		mc.player.yRotO = yaw;
		mc.player.setXRot(pitch);
		mc.player.xRotO = pitch;
		lastYaw = yaw;
		lastPitch = pitch;
	}

	/** With the mouse left alone, swing the view back behind the moving car. */
	private static void followHeading(Minecraft mc, double dt) {
		CameraType type = mc.options.getCameraType();
		if (type == CameraType.FIRST_PERSON) {
			turnWithCar(mc);
			lastType = type;
			return;
		}
		lastType = type;
		lastCarYaw = Float.NaN;
		float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
		long nowMs = System.currentTimeMillis();
		if (Math.abs(yaw - lastYaw) > 0.01F || Math.abs(pitch - lastPitch) > 0.01F) {
			lastMouseMs = nowMs;
		}
		Vehicles.Info v = car(mc);
		MeshTimeline.Pose pose = v != null ? NativeCars.pose(v.id()) : null;
		V3 heading = pose != null && pose.fwd() != null ? pose.fwd() : v != null ? v.fwd() : null;
		if (dev.bngmc.bridge.BridgeSettings.get().chaseFollows() && v != null && heading != null && v.vel() != null
			&& v.vel().length() > FOLLOW_MIN_SPEED && v.vel().length() < Vehicles.MAX_PLAUSIBLE_SPEED && nowMs - lastMouseMs > MOUSE_IDLE_MS) {
			V3 f = CrossoverCoords.canonicalToMinecraftDirection(heading);
			float target = (float) Math.toDegrees(Math.atan2(-f.x(), f.z()));
			float diff = wrap(target - yaw);
			float step = (float) (FOLLOW_DEG_PER_S * dt);
			yaw += Math.max(-step, Math.min(step, diff));
			mc.player.setYRot(yaw);
			mc.player.yRotO = yaw;
		}
		lastYaw = mc.player.getYRot();
		lastPitch = mc.player.getXRot();
	}

	private static float wrap(float deg) {
		deg %= 360.0F;
		if (deg >= 180.0F) {
			deg -= 360.0F;
		}
		if (deg < -180.0F) {
			deg += 360.0F;
		}
		return deg;
	}

	/**
	 * Camera.setup TAIL (via CameraSync, before the pose goes to BeamNG): the driver's seat in first
	 * person; otherwise look at the car from the chase distance (BridgeSettings) behind along the player's view direction,
	 * or from as far ahead of it, looking back, in third person front.
	 */
	public static void applyChaseCamera(Camera camera) {
		Minecraft mc = Minecraft.getInstance();
		CrossoverCoords.Region region = BngWorld.region();
		if (!seated(mc) || region == null) {
			pivot = null;
			return;
		}
		CameraType type = mc.options.getCameraType();
		if (type == CameraType.FIRST_PERSON) {
			Vehicles.Info v = car(mc);
			NativeCars.Eye eye = v != null ? NativeCars.driverEye(v.id()) : null;
			if (eye != null) {
				CameraSync.CameraAccess access = (CameraSync.CameraAccess) camera;
				access.bngbridge$setRotation(mc.player.getYRot(), mc.player.getXRot());
				access.bngbridge$setPosition(eye.pos());
				return;
			}
			// no driver's view for this car (not drawn natively yet): the chase camera
		}
		V3 c = carCentre(mc, region);
		if (c == null) {
			return;
		}
		// The pose is already smooth (blended snapshots); a short spring only takes the suspension's
		// shake out of the view.
		long now = System.nanoTime();
		double dt = lastCamNs == 0 ? 0 : Math.min(0.1, (now - lastCamNs) / 1e9);
		lastCamNs = now;
		if (pivot == null || pivot.sub(c).length() > PIVOT_SNAP * scale()) {
			pivot = c;
		} else {
			double k = 1 - Math.exp(-dt / PIVOT_TIME);
			pivot = pivot.add(c.sub(pivot).scale(k));
		}
		c = pivot;
		float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		Vec3 look = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
		// metres behind the car's centre and above it, from the settings (5.5 and 1.2 by default: 7 made the car small on screen)
		dev.bngmc.bridge.BridgeSettings settings = dev.bngmc.bridge.BridgeSettings.get();
		Vec3 pivot = new Vec3(c.x(), c.y() + settings.chaseHeight() * scale(), c.z());
		CameraSync.CameraAccess access = (CameraSync.CameraAccess) camera;
		if (type == CameraType.THIRD_PERSON_FRONT) {
			access.bngbridge$setRotation(yaw + 180F, -pitch);
			access.bngbridge$setPosition(pivot.add(look.scale(settings.chaseDistance() * scale())));
			return;
		}
		access.bngbridge$setRotation(yaw, pitch);
		access.bngbridge$setPosition(pivot.subtract(look.scale(settings.chaseDistance() * scale())));
	}
}
