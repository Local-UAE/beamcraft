package dev.bngmc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DriveInputsTest {
	@Test
	void keysUseBeamngsKeyboardFilter() {
		DriveInputs in = DriveInputs.fromKeys(true, false, false, true, false);
		assertEquals(1.0, in.throttle());
		assertEquals(0.0, in.brake());
		assertEquals(1.0, in.steering());          // BeamNG: +1 steers right
		assertEquals(0.0, in.clutch());
		assertEquals(0.0, in.parkingbrake());
		assertEquals(DriveInputs.FILTER_KEYBOARD, in.filter());
	}

	@Test
	void leftAndRightTogetherCancel() {
		assertEquals(0.0, DriveInputs.fromKeys(false, false, true, true, false).steering());
		assertEquals(-1.0, DriveInputs.fromKeys(false, false, true, false, false).steering());
	}

	@Test
	void triggersMapFromMinusOneToZeroToOne() {
		DriveInputs.Pad pad = new DriveInputs.Pad();
		pad.update(0.0F, -1.0F, -1.0F, false);   // at rest: triggers report -1
		DriveInputs in = pad.update(0.0F, -1.0F, 1.0F, false);
		assertEquals(1.0, in.throttle(), 1e-6);
		assertEquals(0.0, in.brake(), 1e-6);
		in = pad.update(0.0F, 0.0F, -1.0F, false);
		assertEquals(0.5, in.brake(), 1e-6);
		assertEquals(DriveInputs.FILTER_GAMEPAD, in.filter());
	}

	@Test
	void triggersAreIgnoredUntilSeenAtRest() {
		// Some drivers report 0 (= half pressed) for a trigger that hasn't moved yet.
		DriveInputs.Pad pad = new DriveInputs.Pad();
		DriveInputs in = pad.update(0.0F, 0.0F, 0.0F, false);
		assertEquals(0.0, in.throttle());
		assertEquals(0.0, in.brake());
		assertFalse(pad.active());
	}

	@Test
	void stickHasADeadzoneAndStillReachesFullLock() {
		DriveInputs.Pad pad = new DriveInputs.Pad();
		pad.update(0.0F, -1.0F, -1.0F, false);
		assertEquals(0.0, pad.update(0.05F, -1.0F, -1.0F, false).steering());
		assertFalse(pad.active());
		assertEquals(-1.0, pad.update(-1.0F, -1.0F, -1.0F, false).steering(), 1e-6);
		assertTrue(pad.active());
		double half = pad.update(0.5F, -1.0F, -1.0F, false).steering();
		assertTrue(half > 0.4 && half < 0.5, "rescaled past the deadzone: " + half);
	}

	@Test
	void aBrokenAxisReadsAsZero() {
		DriveInputs.Pad pad = new DriveInputs.Pad();
		pad.update(0.0F, -1.0F, -1.0F, false);
		DriveInputs in = pad.update(Float.NaN, Float.NaN, Float.NaN, false);
		assertEquals(0.0, in.steering());
		assertEquals(0.0, in.throttle());
		assertEquals(0.0, in.brake());
	}

	@Test
	void handbrakeButtonCountsAsInput() {
		DriveInputs.Pad pad = new DriveInputs.Pad();
		assertEquals(1.0, pad.update(0.0F, -1.0F, -1.0F, true).parkingbrake());
		assertTrue(pad.active());
	}

	@Test
	void clutchPedalMapsToBeamngInput() {
		WheelInputs wheel = new WheelInputs();
		assertEquals(0.0, wheel.update(0, 1, 1, 0).clutch(), 1e-6);
		assertEquals(1.0, wheel.update(0, 1, 1, -1).clutch(), 1e-6);
		assertEquals(1.0, wheel.update(0, 1, 1, 1).clutch(), 1e-6);
		assertTrue(wheel.active());
	}
}
