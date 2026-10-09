package dev.bngmc.bridge;

/**
 * Driver inputs for a BeamNG car, as sent in VEHICLE_DRIVE: throttle, brake, clutch and parking
 * brake 0..1, steering -1..1 (+1 = right, BeamNG's input.kbdSteer sends right minus left), and
 * the BeamNG input filter that should smooth them (lua/common/inputFilters.lua:7-11): keys get
 * BeamNG's keyboard ramping, a controller its gamepad filter.
 */
public record DriveInputs(double throttle, double brake, double steering, double clutch, double parkingbrake, int filter) {
	public static final int FILTER_KEYBOARD = 0;
	public static final int FILTER_GAMEPAD = 1;

	public static final DriveInputs RELEASED = new DriveInputs(0, 0, 0, 0, 0, FILTER_GAMEPAD);

	public static DriveInputs fromKeys(boolean up, boolean down, boolean left, boolean right, boolean handbrake) {
		return new DriveInputs(up ? 1 : 0, down ? 1 : 0, (right ? 1 : 0) - (left ? 1 : 0), 0,
			handbrake ? 1 : 0, FILTER_KEYBOARD);
	}

	/**
	 * A game controller read through GLFW's gamepad mapping: left stick X steers, right trigger is
	 * throttle, left trigger brake, a button the handbrake. Triggers report -1 at rest and 1 fully
	 * pressed; one that hasn't been seen at rest yet is ignored, because some drivers report 0
	 * (half pressed) until it first moves.
	 */
	public static final class Pad {
		static final float STICK_DEADZONE = 0.1F;
		static final float TRIGGER_DEADZONE = 0.05F;

		private boolean ltSeenAtRest, rtSeenAtRest;
		private boolean active;

		public DriveInputs update(float stickX, float leftTrigger, float rightTrigger, boolean handbrake) {
			stickX = Float.isNaN(stickX) ? 0.0F : stickX;
			leftTrigger = Float.isNaN(leftTrigger) ? -1.0F : leftTrigger;
			rightTrigger = Float.isNaN(rightTrigger) ? -1.0F : rightTrigger;
			ltSeenAtRest |= leftTrigger < -0.9F;
			rtSeenAtRest |= rightTrigger < -0.9F;
			double brake = ltSeenAtRest ? trigger(leftTrigger) : 0;
			double throttle = rtSeenAtRest ? trigger(rightTrigger) : 0;
			double steer = Math.abs(stickX) <= STICK_DEADZONE ? 0
				: Math.signum(stickX) * (Math.min(1.0, Math.abs(stickX)) - STICK_DEADZONE) / (1.0 - STICK_DEADZONE);
			active = steer != 0 || brake > 0 || throttle > 0 || handbrake;
			return new DriveInputs(throttle, brake, steer, 0, handbrake ? 1 : 0, FILTER_GAMEPAD);
		}

		/** Whether the last update had any input on it (so it, not the keyboard, should drive). */
		public boolean active() {
			return active;
		}

		private static double trigger(float v) {
			double t = (Math.max(-1.0F, Math.min(1.0F, v)) + 1.0) / 2.0;
			return t < TRIGGER_DEADZONE ? 0 : t;
		}
	}
}
