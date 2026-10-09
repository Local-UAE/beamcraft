package dev.bngmc.bridge.client;

import dev.bngmc.bridge.link.BngLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who gets the keyboard and mouse (adapted from upstream's F8 switch). The key is F4 here: BeamNG
 * binds F8 (dropCameraAtPlayer) and F7 (dropPlayerAtCameraNoReset, which moves your car) in
 * settings/inputmaps/keyboard.json, while F4 is free in both games.
 * <ul>
 *   <li>MINECRAFT: Minecraft has focus and drives BeamNG's camera.</li>
 *   <li>BEAMNG (F4 in Minecraft): Minecraft pauses, releases the mouse, hides its overlay window
 *   and hands BeamNG its own camera back, so BeamNG's menus, map, vehicle selector and driving
 *   all work normally. F4 again, pressed in BeamNG, comes back.</li>
 * </ul>
 * Minecraft notices the key in BeamNG by polling the key state while BeamNG's window is in
 * front (GetAsyncKeyState); it never installs a keyboard hook. The return key is
 * -Dbngbridge.returnKey (a Windows virtual-key code, default 0x73 = F4).
 */
public final class ControlSwitch {
	private ControlSwitch() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static final int RETURN_VK = Integer.decode(System.getProperty("bngbridge.returnKey", "0x73"));

	public enum Mode {
		MINECRAFT,
		BEAMNG
	}

	private static Mode mode = Mode.MINECRAFT;
	/** The return key must be seen released before a press counts (it's still down right after the switch). */
	private static boolean armed;
	private static boolean hidden;
	/** The player got into a BeamNG car: coming back puts Steve beside that car. */
	private static boolean inVehicle;

	/** Right-click on a car's proxy (BngVehicleEntity.enterHook): BeamNG drives that car. */
	public static void enterVehicle(Minecraft mc, long bngId) {
		com.google.gson.JsonObject msg = new com.google.gson.JsonObject();
		msg.addProperty("id", bngId);
		if (!BngLink.get().send(dev.bngmc.bridge.link.Protocol.ENTER_VEHICLE, msg)) {
			LOG.warn("Can't get into BeamNG vehicle {}: BeamNG not connected", bngId);
			return;
		}
		inVehicle = true;
		CarRide.startRiding(bngId);
		LOG.info("Getting into BeamNG vehicle {}", bngId);
		if (!toBeamng(mc)) {
			inVehicle = false;   // BeamNG can't take the controls: stay out of the car
			CarRide.stopRiding();
		}
	}

	public static Mode mode() {
		return mode;
	}

	public static boolean minecraftMode() {
		return mode == Mode.MINECRAFT;
	}

	/** The switch key in Minecraft. */
	public static boolean toBeamng(Minecraft mc) {
		if (mode == Mode.BEAMNG) {
			return true;
		}
		long bng = Overlay.beamngHwnd() != 0 ? Overlay.beamngHwnd() : Win32.findBeamngWindow();
		if (bng == 0) {
			LOG.warn("Control switch: BeamNG's window not found, staying in Minecraft");
			return false;
		}
		mode = Mode.BEAMNG;
		armed = false;
		BngLink.get().send(dev.bngmc.bridge.link.Protocol.CAMERA_RELEASE, null);
		if (mc.level != null && mc.screen == null && !inVehicle) {
			mc.pauseGame(false);   // riding in a car, the Minecraft world keeps running (crashes hurt)
		}
		mc.mouseHandler.releaseMouse();
		if (Overlay.active()) {   // side by side, the window stays where it is
			GLFW.glfwHideWindow(mc.getWindow().getWindow());
			hidden = true;
		}
		boolean front = Win32.bringToFront(bng);
		LOG.info("Control -> BeamNG (window in front: {}); press F4 in BeamNG to come back", front);
		return true;
	}

	/** Back to Minecraft (the return key seen in BeamNG). */
	public static void toMinecraft(Minecraft mc) {
		if (mode == Mode.MINECRAFT) {
			if (inVehicle) {   // never leave Steve stuck on a car
				inVehicle = false;
				CarRide.stopRiding();
			}
			return;
		}
		mode = Mode.MINECRAFT;
		long w = mc.getWindow().getWindow();
		if (hidden) {
			GLFW.glfwShowWindow(w);
			hidden = false;
		}
		GLFW.glfwFocusWindow(w);
		boolean front = Win32.bringToFront(Win32.glfwHwnd(w));
		if (mc.screen instanceof PauseScreen) {
			mc.setScreen(null);
		}
		if (inVehicle) {
			inVehicle = false;
			CarRide.stopRiding();
			dev.bngmc.bridge.BngWorld.requestRecall();   // out of the car: Steve stands beside it
		}
		LOG.info("Control -> Minecraft (window in front: {})", front);
	}

	/** Client tick: watch for the return key while BeamNG has the controls. */
	public static void tick(Minecraft mc) {
		if (mode != Mode.BEAMNG) {
			return;
		}
		boolean down = Win32.keyDown(RETURN_VK);
		if (!down) {
			armed = true;
			return;
		}
		long bng = Overlay.beamngHwnd();
		if (armed && (bng == 0 || Win32.foregroundWindow() == bng)) {
			toMinecraft(mc);
		}
	}
}
