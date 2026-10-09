package dev.bngmc.bridge;

import java.util.Locale;

/** Raw-axis mapping and pedal input for Logitech G29/G920/G923 wheels. */
public final class WheelInputs {
	private static final float STEERING_DEADZONE = 0.02F;
	private static final double PEDAL_DEADZONE = 0.025;
	public static final int SHIFT_UP_BUTTON = 4;
	public static final int SHIFT_DOWN_BUTTON = 5;
	public static final int SHIFTER_FIRST_BUTTON = 12;
	public static final int SHIFTER_LAST_BUTTON = 18;

	private DriveInputs latest = DriveInputs.RELEASED;
	private float rawSteering;
	private float rawThrottle;
	private float rawBrake;
	private float rawClutch;

	public static boolean isSupportedDevice(String name) {
		if (name == null) {
			return false;
		}
		String normalized = name.toLowerCase(Locale.ROOT);
		return normalized.contains("g29") || normalized.contains("g920") || normalized.contains("g923");
	}

	/**
	 * The Windows G923 mapping is steering, accelerator, brake and clutch on axes 0, 1, 2 and 3.
	 * Its gas and brake axes rest at +1 and move toward -1 as their pedal is pressed.
	 */
	public DriveInputs update(float steeringAxis, float throttleAxis, float brakeAxis, float clutchAxis) {
		rawSteering = steeringAxis;
		rawThrottle = throttleAxis;
		rawBrake = brakeAxis;
		rawClutch = clutchAxis;
		latest = new DriveInputs(
			pedal(throttleAxis),
			pedal(brakeAxis),
			steering(steeringAxis),
			clutch(clutchAxis),
			0,
			DriveInputs.FILTER_GAMEPAD
		);
		return latest;
	}

	public boolean active() {
		return latest.throttle() > 0 || latest.brake() > 0 || latest.steering() != 0 || latest.clutch() > 0;
	}

	public static int gearForButton(int button) {
		return switch (button) {
			case 12 -> 1;
			case 13 -> 2;
			case 14 -> 3;
			case 15 -> 4;
			case 16 -> 5;
			case 17 -> 6;
			case 18 -> -1;
			default -> 0;
		};
	}

	public String rawAxes() {
		return String.format(Locale.ROOT, "axes steer %+.2f gas %+.2f brake %+.2f clutch %+.2f",
			rawSteering, rawThrottle, rawBrake, rawClutch);
	}

	private static double steering(float axis) {
		if (!Float.isFinite(axis)) {
			return 0;
		}
		double value = Math.max(-1, Math.min(1, axis));
		double magnitude = Math.abs(value);
		if (magnitude <= STEERING_DEADZONE) {
			return 0;
		}
		return Math.copySign((magnitude - STEERING_DEADZONE) / (1 - STEERING_DEADZONE), value);
	}

	private static double pedal(float axis) {
		if (!Float.isFinite(axis)) {
			return 0;
		}
		double input = (1 - Math.max(-1, Math.min(1, axis))) / 2;
		return input < PEDAL_DEADZONE ? 0 : (input - PEDAL_DEADZONE) / (1 - PEDAL_DEADZONE);
	}

	private static double clutch(float axis) {
		if (!Float.isFinite(axis)) {
			return 0;
		}
		double input = Math.min(1, Math.abs(axis));
		return input < PEDAL_DEADZONE ? 0 : (input - PEDAL_DEADZONE) / (1 - PEDAL_DEADZONE);
	}
}
