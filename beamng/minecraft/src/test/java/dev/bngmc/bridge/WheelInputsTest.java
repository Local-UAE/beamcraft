package dev.bngmc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WheelInputsTest {
	@Test
	void recognizesLogitechG29Family() {
		assertTrue(WheelInputs.isSupportedDevice("Logitech G29 Driving Force Racing Wheel USB"));
		assertTrue(WheelInputs.isSupportedDevice("Logitech G920"));
		assertTrue(WheelInputs.isSupportedDevice("Logitech G923 TRUEFORCE"));
		assertFalse(WheelInputs.isSupportedDevice("Xbox Wireless Controller"));
		assertFalse(WheelInputs.isSupportedDevice(null));
	}

	@Test
	void mapsReleasedAndPressedPedalsFromTheirWindowsAxisRanges() {
		WheelInputs wheel = new WheelInputs();
		DriveInputs in = wheel.update(0, 1, 1, 0);
		assertEquals(0, in.throttle(), 1e-6);
		assertEquals(0, in.brake(), 1e-6);
		assertFalse(wheel.active());
		in = wheel.update(0, -1, -1, 0);
		assertEquals(1, in.throttle(), 1e-6);
		assertEquals(1, in.brake(), 1e-6);
		assertTrue(wheel.active());
		assertEquals(DriveInputs.FILTER_GAMEPAD, in.filter());
	}

	@Test
	void keepsGasAndBrakeIndependent() {
		WheelInputs wheel = new WheelInputs();
		DriveInputs in = wheel.update(0, -1, 1, 0);
		assertEquals(1, in.throttle(), 1e-6);
		assertEquals(0, in.brake(), 1e-6);
		in = wheel.update(0, 1, -1, 0);
		assertEquals(0, in.throttle(), 1e-6);
		assertEquals(1, in.brake(), 1e-6);
	}

	@Test
	void appliesPedalDeadzoneAndClamping() {
		WheelInputs wheel = new WheelInputs();
		assertEquals(0, wheel.update(0, 0.95F, 1, 0).throttle(), 1e-6);
		assertEquals((0.5 - 0.025) / 0.975, wheel.update(0, 0, 1, 0).throttle(), 1e-6);
		assertEquals(1, wheel.update(0, -1, 1, 0).throttle(), 1e-6);
		assertEquals(0, wheel.update(0, Float.NaN, 1, 0).throttle(), 1e-6);
	}

	@Test
	void mapsSteeringWithDeadzoneAndClamping() {
		WheelInputs wheel = new WheelInputs();
		assertEquals(0, wheel.update(0.01F, -1, 1, 0).steering(), 1e-6);
		assertEquals(1, wheel.update(1, -1, 1, 0).steering(), 1e-6);
		assertEquals(-1, wheel.update(-1, -1, 1, 0).steering(), 1e-6);
		assertEquals(0, wheel.update(Float.NaN, -1, 1, 0).steering(), 1e-6);
	}

	@Test
	void identifiesG923PaddleShifterButtons() {
		assertEquals(4, WheelInputs.SHIFT_UP_BUTTON);
		assertEquals(5, WheelInputs.SHIFT_DOWN_BUTTON);
	}

	@Test
	void mapsLogitechHShifterButtonsToGears() {
		assertEquals(1, WheelInputs.gearForButton(12));
		assertEquals(2, WheelInputs.gearForButton(13));
		assertEquals(3, WheelInputs.gearForButton(14));
		assertEquals(4, WheelInputs.gearForButton(15));
		assertEquals(5, WheelInputs.gearForButton(16));
		assertEquals(6, WheelInputs.gearForButton(17));
		assertEquals(-1, WheelInputs.gearForButton(18));
		assertEquals(0, WheelInputs.gearForButton(11));
	}
}
