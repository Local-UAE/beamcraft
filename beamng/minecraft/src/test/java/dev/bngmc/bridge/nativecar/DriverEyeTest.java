package dev.bngmc.bridge.nativecar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class DriverEyeTest {
	// A car facing south (+z) in Minecraft axes: ref node 0 at the origin, back node 1 behind it,
	// left node 2 on the car's left (east, +x), the driver camera node 3 up and to the left.
	private static final float[] NODES = {0, 0, 0, 0, 0, -1, 1, 0, 0, 0.4F, 1.1F, -0.3F, 0, 1, 0};

	private static NativeExport.DriverView view(String json) {
		return NativeExport.driverView(JsonParser.parseString("{\"camera\":" + json + "}").getAsJsonObject());
	}

	@Test
	void theEyeSitsOnTheDriverNodeAndLooksAlongTheCar() {
		DriverEye.Eye e = DriverEye.of(view("{\"driver\":3,\"ref\":0,\"back\":1,\"left\":2}"), NODES);
		assertArrayEquals(new float[] {0.4F, 1.1F, -0.3F}, e.pos(), 1e-5F);
		assertArrayEquals(new float[] {0, 0, 1}, e.forward(), 1e-5F);
		assertArrayEquals(new float[] {0, 1, 0}, e.up(), 1e-5F);
		assertEquals(0F, e.yaw(), 1e-3F);
		assertEquals(0F, e.pitch(), 1e-3F);
	}

	@Test
	void theViewTurnsWithTheCar() {
		// the same car turned to face east (+x): Minecraft yaw -90
		float[] turned = new float[NODES.length];
		for (int i = 0; i < NODES.length; i += 3) {
			turned[i] = NODES[i + 2];
			turned[i + 1] = NODES[i + 1];
			turned[i + 2] = -NODES[i];
		}
		DriverEye.Eye e = DriverEye.of(view("{\"driver\":3,\"ref\":0,\"back\":1,\"left\":2}"), turned);
		assertEquals(-90F, e.yaw(), 1e-3F);
		assertArrayEquals(new float[] {0, 1, 0}, e.up(), 1e-5F);
	}

	@Test
	void anOffsetMovesTheEyeAlongTheCar() {
		// BeamNG: nodePos + dir * x - camLeft * y + camUp * z (camLeft = ref - left points right here)
		DriverEye.Eye e = DriverEye.of(view("{\"driver\":3,\"ref\":0,\"back\":1,\"left\":2,\"offset\":[0.5,0.2,0.1]}"), NODES);
		assertArrayEquals(new float[] {0.4F + 0.2F, 1.2F, -0.3F + 0.5F}, e.pos(), 1e-5F);
	}

	@Test
	void aCameraWithItsOwnUpNodeUsesIt() {
		DriverEye.Eye e = DriverEye.of(view("{\"driver\":3,\"ref\":0,\"back\":1,\"left\":2,\"idUp\":4,\"idBack\":1,\"idRef\":0}"), NODES);
		assertArrayEquals(new float[] {0, 0, 1}, e.forward(), 1e-5F);
		assertArrayEquals(new float[] {0, 1, 0}, e.up(), 1e-5F);
	}

	@Test
	void withoutADriverCameraTheEyeIsTheRefNode() {
		DriverEye.Eye e = DriverEye.of(view("{\"ref\":0,\"back\":1,\"left\":2}"), NODES);
		assertArrayEquals(new float[] {0, 0, 0}, e.pos(), 1e-5F);
	}

	@Test
	void missingNodesGiveNoEye() {
		assertNull(DriverEye.of(view("{\"driver\":3,\"ref\":0,\"back\":99,\"left\":2}"), NODES));
		assertNull(NativeExport.driverView(JsonParser.parseString("{}").getAsJsonObject()));
	}
}
