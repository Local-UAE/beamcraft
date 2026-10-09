package dev.bngmc.bridge.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CrashDamageTest {
	@Test
	void hardBrakingIsHarmless() {
		assertEquals(0f, CrashDamage.damageFor(6.0));   // 6 m/s in 50 ms is hard braking, not a crash
	}

	@Test
	void wallHitAtSpeedKills() {
		assertEquals(25.5f, CrashDamage.damageFor(25.0), 1e-5);
	}
}
