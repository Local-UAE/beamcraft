package dev.bngmc.bridge.coords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CrossoverCoordsTest {
	private static final double EPS = 1e-9;
	private static final CrossoverCoords.Region R0 = CrossoverCoords.Region.of(0);
	private static final CrossoverCoords.Region R3 = CrossoverCoords.Region.of(3);

	private static void assertV(V3 expected, V3 actual) {
		assertTrue(expected.approx(actual, 1e-9), () -> "expected " + expected + " but was " + actual);
	}

	@Test
	void originMapsToOrigin() {
		assertV(V3.ZERO, CrossoverCoords.minecraftToCanonicalPosition(V3.ZERO, R0));
		assertV(V3.ZERO, CrossoverCoords.canonicalToMinecraftPosition(V3.ZERO, R0));
	}

	@Test
	void minecraftAxesMapToCanonicalAxes() {
		// +X east stays east, +Y up becomes +Z, +Z south becomes -Y (north is +Y).
		assertV(new V3(1, 0, 0), CrossoverCoords.minecraftToCanonicalDirection(new V3(1, 0, 0)));
		assertV(new V3(0, 0, 1), CrossoverCoords.minecraftToCanonicalDirection(new V3(0, 1, 0)));
		assertV(new V3(0, -1, 0), CrossoverCoords.minecraftToCanonicalDirection(new V3(0, 0, 1)));
	}

	@Test
	void canonicalAxesMapToMinecraftAxes() {
		assertV(new V3(1, 0, 0), CrossoverCoords.canonicalToMinecraftDirection(new V3(1, 0, 0)));
		assertV(new V3(0, 0, -1), CrossoverCoords.canonicalToMinecraftDirection(new V3(0, 1, 0)));
		assertV(new V3(0, 1, 0), CrossoverCoords.canonicalToMinecraftDirection(new V3(0, 0, 1)));
	}

	@Test
	void mappingIsAProperRotation() {
		// Handedness is preserved: images of X and Y cross to the image of Z.
		V3 x = CrossoverCoords.minecraftToCanonicalDirection(new V3(1, 0, 0));
		V3 y = CrossoverCoords.minecraftToCanonicalDirection(new V3(0, 1, 0));
		V3 z = CrossoverCoords.minecraftToCanonicalDirection(new V3(0, 0, 1));
		assertV(z, x.cross(y));
	}

	@Test
	void regionZeroIsBeamngMetresWithAltitudeAsY() {
		// BeamNG (12, 34, 56) = MC (12, 56, -34) in region 0.
		V3 mc = CrossoverCoords.canonicalToMinecraftPosition(new V3(12, 34, 56), R0);
		assertV(new V3(12, 56, -34), mc);
	}

	@ParameterizedTest
	@CsvSource({"0,0,0", "1,2,3", "-1024.5,77.25,1999.75", "8000,-300,-8000"})
	void positionRoundTrips(double x, double y, double z) {
		V3 cx = new V3(x, y, z);
		for (CrossoverCoords.Region r : new CrossoverCoords.Region[] {R0, R3}) {
			V3 mc = CrossoverCoords.canonicalToMinecraftPosition(cx, r);
			assertV(cx, CrossoverCoords.minecraftToCanonicalPosition(mc, r));
			assertTrue(r.containsMcX(mc.x()));
		}
	}

	@Test
	void regionsAreOffsetAlongMinecraftX() {
		V3 mc = CrossoverCoords.canonicalToMinecraftPosition(V3.ZERO, R3);
		assertV(new V3(3 * CrossoverCoords.REGION_SPACING, 0, 0), mc);
		assertTrue(!R0.containsMcX(mc.x()));
	}

	@Test
	void velocityConversionRoundTrips() {
		V3 v = new V3(3, -4, 5);
		assertV(new V3(3, -5, -4), CrossoverCoords.minecraftToCanonicalVelocity(v));
		assertV(v, CrossoverCoords.canonicalToMinecraftVelocity(CrossoverCoords.minecraftToCanonicalVelocity(v)));
	}

	@Test
	void angularVelocityKeepsSpinDirection() {
		// Spinning about MC up (yaw rate) is spinning about CX up.
		assertV(new V3(0, 0, 2), CrossoverCoords.minecraftToCanonicalAngularVelocity(new V3(0, 2, 0)));
	}

	@ParameterizedTest
	@CsvSource({
		"0, 0, 0, -1, 0",     // yaw 0 looks south
		"90, 0, -1, 0, 0",    // west
		"180, 0, 0, 1, 0",    // north
		"-90, 0, 1, 0, 0",    // east
		"0, 90, 0, 0, -1",    // straight down
		"0, -90, 0, 0, 1"     // straight up
	})
	void minecraftLookMapsToCanonicalForward(double yaw, double pitch, double fx, double fy, double fz) {
		V3 f = CrossoverCoords.minecraftToCanonicalDirection(CrossoverCoords.minecraftLookVector(yaw, pitch));
		assertV(new V3(fx, fy, fz), f);
	}

	@Test
	void restCameraIsIdentity() {
		Quat q = CrossoverCoords.cameraQuat(new V3(0, 1, 0), new V3(0, 0, 1));
		assertTrue(q.sameRotation(Quat.IDENTITY, EPS));
	}

	@Test
	void ninetyDegreeTurnsMatchAxisAngle() {
		// Facing east = rest camera turned -90 degrees about +Z (clockwise seen from above).
		Quat east = CrossoverCoords.cameraQuat(new V3(1, 0, 0), new V3(0, 0, 1));
		assertTrue(east.sameRotation(Quat.axisAngle(new V3(0, 0, 1), -Math.PI / 2), EPS));
		// Looking up 90 degrees = +90 about +X.
		Quat up = CrossoverCoords.cameraQuat(new V3(0, 0, 1), new V3(0, -1, 0));
		assertTrue(up.sameRotation(Quat.axisAngle(new V3(1, 0, 0), Math.PI / 2), EPS));
	}

	@ParameterizedTest
	@CsvSource({"0,0", "45,10", "90,-30", "-135,60", "179,-89", "33.5,0.25"})
	void minecraftCameraRoundTrips(double yaw, double pitch) {
		Quat q = CrossoverCoords.minecraftCameraToCanonical(yaw, pitch);
		V3 fwd = CrossoverCoords.cameraForward(q);
		V3 expected = CrossoverCoords.minecraftToCanonicalDirection(CrossoverCoords.minecraftLookVector(yaw, pitch));
		assertV(expected, fwd);
		// No roll: the camera's right vector stays horizontal.
		assertEquals(0.0, CrossoverCoords.cameraRight(q).z(), EPS);
		double[] back = CrossoverCoords.canonicalCameraToMinecraft(q);
		assertEquals(0.0, Math.IEEEremainder(back[0] - yaw, 360.0), 1e-7);
		assertEquals(pitch, back[1], 1e-7);
	}

	@Test
	void headingIsMinecraftYawPlus180() {
		for (double yaw = -180; yaw < 180; yaw += 15) {
			V3 f = CrossoverCoords.minecraftToCanonicalDirection(CrossoverCoords.minecraftLookVector(yaw, 0));
			double expected = ((yaw + 180) % 360 + 360) % 360;
			assertEquals(0.0, Math.IEEEremainder(CrossoverCoords.heading(f) - expected, 360.0), 1e-7);
		}
	}

	@Test
	void minecraftRotationKeepsLocalAxes() {
		// An object turned 30 degrees about MC up, pitched 10 degrees: the world direction of
		// any local axis must agree after conversion.
		Quat qMc = Quat.axisAngle(new V3(0, 1, 0), Math.toRadians(30)).mul(Quat.axisAngle(new V3(1, 0, 0), Math.toRadians(10)));
		Quat qCx = CrossoverCoords.minecraftToCanonicalRotation(qMc);
		for (V3 local : new V3[] {new V3(1, 0, 0), new V3(0, 1, 0), new V3(0, 0, 1), new V3(0.3, -0.5, 0.8)}) {
			V3 viaMc = CrossoverCoords.minecraftToCanonicalDirection(qMc.rotate(local));
			assertV(viaMc, qCx.rotate(local));
		}
		assertTrue(CrossoverCoords.canonicalToMinecraftRotation(qCx).sameRotation(qMc, EPS));
	}

	@Test
	void mcToCxIsPlusNinetyAboutX() {
		assertTrue(CrossoverCoords.MC_TO_CX.sameRotation(Quat.axisAngle(new V3(1, 0, 0), Math.PI / 2), EPS));
	}

	@Test
	void fovConversionRoundTrips() {
		double h = CrossoverCoords.horizontalFovDeg(70.0, 16.0 / 9.0);
		assertEquals(102.45, h, 0.01);
		assertEquals(70.0, CrossoverCoords.verticalFovDeg(h, 16.0 / 9.0), 1e-9);
		assertEquals(70.0, CrossoverCoords.horizontalFovDeg(70.0, 1.0), 1e-9);
	}

	@Test
	void beamngQuaternionsAreConjugated() {
		// Values printed by the extension's self-test in BeamNG 0.39.4 (beamng.log, 2026-10-01).
		double h = Math.sqrt(0.5);
		Quat bngEast = new Quat(0, 0, h, h);   // quatFromDir(vec3(1,0,0), vec3(0,0,1))
		Quat cx = CrossoverCoords.beamngToCanonicalRotation(bngEast);
		assertTrue(cx.sameRotation(CrossoverCoords.cameraQuat(new V3(1, 0, 0), new V3(0, 0, 1)), EPS));
		assertV(new V3(1, 0, 0), CrossoverCoords.cameraForward(cx));
		assertV(new V3(0, -1, 0), CrossoverCoords.cameraRight(cx));
		// quatFromAxisAngle(Z, +90) * X gave (0, -1, 0) in BeamNG. Assuming it builds the usual
		// components (0, 0, sin 45, cos 45), the conjugate is a -90 turn, which does exactly that.
		Quat bngZ90 = Quat.axisAngle(new V3(0, 0, 1), Math.PI / 2);
		assertV(new V3(0, -1, 0), CrossoverCoords.beamngToCanonicalRotation(bngZ90).rotate(new V3(1, 0, 0)));
		assertTrue(CrossoverCoords.canonicalToBeamngRotation(cx).sameRotation(bngEast, EPS));
	}

	@Test
	void beamngToCanonicalIsIdentityForPositions() {
		V3 p = new V3(-512.25, 300.5, 72.0);
		assertV(p, CrossoverCoords.beamngToCanonicalPosition(p));
		assertV(p, CrossoverCoords.canonicalToBeamngPosition(p));
	}
}
