package dev.bngmc.bridge.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VehicleHitsTest {
	@Test
	void slowContactIsNotAHit() {
		assertEquals(0f, VehicleHits.damageFor(0.0));
		assertEquals(0f, VehicleHits.damageFor(VehicleHits.MIN_SPEED));
	}

	@Test
	void ridersOnTheRoofAreNotHit() {
		double roof = 101.75;
		assertTrue(VehicleHits.ridingOnRoof(roof + 0.001, roof));   // standing on the roof
		assertTrue(VehicleHits.ridingOnRoof(roof - 0.1, roof));     // the roof rose a little around the feet
		assertFalse(VehicleHits.ridingOnRoof(100.0, roof));         // standing on the ground beside the car
		assertFalse(VehicleHits.ridingOnRoof(roof - 0.5, roof));    // a mob halfway up the side
	}

	@Test
	void theDriverSittingInsideIsNotHitByTheirOwnCar() {
		double roof = -58.25;
		assertTrue(VehicleHits.spared(true, -59.7, roof));     // seated in this car (Minecraft-hosted world)
		assertTrue(VehicleHits.spared(false, roof, roof));     // on its roof
		assertFalse(VehicleHits.spared(false, -60.0, roof));   // a cow in its way
	}

	@Test
	void damageGrowsWithSpeed() {
		assertEquals(12f, VehicleHits.damageFor(15.0), 1e-6);   // 54 km/h: six hearts
		assertEquals(22f, VehicleHits.damageFor(25.0), 1e-6);   // 90 km/h: more than a full player (20)
	}
}
