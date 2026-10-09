package dev.bngmc.bridge.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.bngmc.bridge.WheelInputs;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * The rest of the driver's controls while sitting in a BeamNG car (HostDrive does throttle, brake,
 * clutch, steering and handbrake): VEHICLE_ACTION messages that run BeamNG's own bindings (bridge.lua).
 * All keys are rebindable under Controls > "BeamNG car". Controller: RB/LB shift up/down,
 * X gearbox mode, Y reset, A starter, View (Back) recover (hold, rewinds), D-pad up lights,
 * left stick click horn, right stick click gets out (Minecraft's sneak, as Shift does).
 */
public final class CarControls {
	private CarControls() {
	}

	private static final String CATEGORY = "category.bngbridge.car";

	/** One control: a key and a controller button, sent as a press and (if held) a release. */
	private record Control(String action, KeyMapping key, int padButton, int wheelButton, boolean hold) {
	}

	private static Control[] controls;
	private static boolean[] wasDown;
	private static int selectedWheelGear;
	private static final int EXIT_SNEAK_TICKS = 3;   // sneak held this long gets the player out
	private static boolean exitWasDown;
	private static int exitTicks;
	private static boolean viewWasDown;

	public static void register() {
		controls = new Control[] {
			control("shift_up", GLFW.GLFW_KEY_X, GLFW.GLFW_GAMEPAD_BUTTON_X, true),   // Square on a PlayStation pad (Jas)
			control("shift_down", GLFW.GLFW_KEY_Z, GLFW.GLFW_GAMEPAD_BUTTON_A, true),   // Cross
			control("gearbox_mode", GLFW.GLFW_KEY_M, GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER, false),
			control("reset", GLFW.GLFW_KEY_R, GLFW.GLFW_GAMEPAD_BUTTON_Y, false),
			control("recover", GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_GAMEPAD_BUTTON_BACK, true),
			control("starter", GLFW.GLFW_KEY_V, GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER, true),
			control("horn", GLFW.GLFW_KEY_H, GLFW.GLFW_GAMEPAD_BUTTON_LEFT_THUMB, true),
			control("lights", GLFW.GLFW_KEY_N, GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP, false),
			control("esc_mode", GLFW.GLFW_KEY_G, GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT, false),
		};
		wasDown = new boolean[controls.length];
	}

	private static Control control(String action, int key, int padButton, boolean hold) {
		KeyMapping k = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bngbridge.car." + action, InputConstants.Type.KEYSYM, key, CATEGORY));
		int wheelButton = switch (action) {
			case "shift_up" -> WheelInputs.SHIFT_UP_BUTTON;
			case "shift_down" -> WheelInputs.SHIFT_DOWN_BUTTON;
			default -> -1;
		};
		return new Control(action, k, padButton, wheelButton, hold);
	}

	/**
	 * Every frame, after HostDrive read the controller: presses and releases while seated; anything
	 * held is released when getting out or opening a screen.
	 */
	public static void frame(Minecraft mc) {
		if (controls == null) {
			return;
		}
		boolean seated = HostDrive.seated(mc) && mc.screen == null;
		updateWheelGear(seated ? HostDrive.wheelGear() : 0);
		boolean exit = seated && HostDrive.padButton(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_THUMB);
		if (exit && !exitWasDown) {
			mc.options.keyShift.setDown(true);
			exitTicks = EXIT_SNEAK_TICKS;
		}
		exitWasDown = exit;
		// D-pad down: the next perspective, as F5 does on the keyboard (HostDrive)
		boolean view = seated && HostDrive.padButton(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
		if (view && !viewWasDown) {
			mc.options.setCameraType(mc.options.getCameraType().cycle());
		}
		viewWasDown = view;
		for (int i = 0; i < controls.length; i++) {
			Control c = controls[i];
			boolean down = seated && (keyDown(mc, c.key()) || HostDrive.padButton(c.padButton())
				|| HostDrive.wheelButton(c.wheelButton()));
			if (down && !wasDown[i]) {
				send(c.action(), true);
			} else if (!down && wasDown[i] && c.hold()) {
				send(c.action(), false);
			}
			wasDown[i] = down;
		}
	}

	private static void updateWheelGear(int gear) {
		if (gear == selectedWheelGear) {
			return;
		}
		if (selectedWheelGear != 0) {
			send(gearAction(selectedWheelGear), false);
		}
		if (gear != 0) {
			send(gearAction(gear), true);
		}
		selectedWheelGear = gear;
	}

	private static String gearAction(int gear) {
		return gear < 0 ? "gear_reverse" : "gear_" + gear;
	}

	/**
	 * The bound key's own state. KeyMapping.isDown() would miss keys vanilla also uses (X is "Load
	 * Hotbar Activator"): only one mapping per key gets the press.
	 */
	private static boolean keyDown(Minecraft mc, KeyMapping k) {
		InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(k);
		if (bound.getType() != InputConstants.Type.KEYSYM || bound.getValue() == InputConstants.UNKNOWN.getValue()) {
			return k.isDown();
		}
		return InputConstants.isKeyDown(mc.getWindow().getWindow(), bound.getValue());
	}

	/** Client tick: lets go of the sneak a right stick click pressed. */
	public static void tick(Minecraft mc) {
		if (exitTicks > 0 && --exitTicks == 0) {
			mc.options.keyShift.setDown(false);
		}
	}

	/** Leaving the world: no more frames run, so release anything held (horn, rewind) now. */
	public static void releaseAll() {
		if (controls != null) {
			for (int i = 0; i < controls.length; i++) {
				if (wasDown[i] && controls[i].hold()) {
					send(controls[i].action(), false);
				}
				wasDown[i] = false;
			}
		}
		updateWheelGear(0);
	}

	private static void send(String action, boolean down) {
		JsonObject msg = new JsonObject();
		msg.addProperty("action", action);
		msg.addProperty("down", down);
		BngLink.get().send(Protocol.VEHICLE_ACTION, msg);
	}

	/** The key an action is bound to now (rebindable under Controls), for the HUD's list. */
	public static String key(String action) {
		if (controls != null) {
			for (Control c : controls) {
				if (c.action().equals(action)) {
					return c.key().getTranslatedKeyMessage().getString();
				}
			}
		}
		return "?";
	}
}
