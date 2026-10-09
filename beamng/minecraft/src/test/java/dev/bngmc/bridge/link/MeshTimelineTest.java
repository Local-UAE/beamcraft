package dev.bngmc.bridge.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.bngmc.bridge.coords.V3;
import org.junit.jupiter.api.Test;

class MeshTimelineTest {
	/** A one-node car at x = 10 * k sent at sender time 100 * k ms, node offset (k, 0, 0) cm. */
	private static VehicleMeshes.Mesh snap(int k, double rxMs) {
		return new VehicleMeshes.Mesh(7, 1, new V3(10 * k, 0, 0), new int[] {k, 0, 0}, rxMs, 100.0 * k, new V3(0, 1, 0), new V3(0, 0, 1));
	}

	@Test
	void blendsBetweenTheTwoSnapshotsAroundTheRequestedTime() {
		MeshTimeline t = new MeshTimeline();
		t.add(snap(1, 1000));
		t.add(snap(2, 1100));
		MeshTimeline.Pose p = t.sample(150);
		assertNotNull(p);
		assertEquals(15.0, p.pos().x(), 1e-9);
		float[] nodes = new float[3];
		p.nodesMinecraftAxes(nodes);
		assertEquals(0.015F, nodes[0], 1e-6);   // 1.5 cm, in metres
	}

	@Test
	void neverRunsAheadOfTheNewestSnapshot() {
		MeshTimeline t = new MeshTimeline();
		t.add(snap(1, 1000));
		t.add(snap(2, 1100));
		assertEquals(20.0, t.sample(500).pos().x(), 1e-9);
		assertEquals(10.0, t.sample(0).pos().x(), 1e-9);
	}

	@Test
	void keepsOnlyTheLatestFewSnapshots() {
		MeshTimeline t = new MeshTimeline();
		for (int k = 0; k < 20; k++) {
			t.add(snap(k, 1000 + 10 * k));
		}
		assertEquals(MeshTimeline.KEEP, t.size());
		assertEquals(190.0, t.newest().pos().x(), 1e-9);
	}

	@Test
	void ignoresSnapshotsThatArriveOutOfOrder() {
		MeshTimeline t = new MeshTimeline();
		t.add(snap(2, 1000));
		t.add(snap(1, 1001));
		assertEquals(1, t.size());
	}

	@Test
	void aShapeChangeIsNotBlendedAcross() {
		MeshTimeline t = new MeshTimeline();
		t.add(snap(1, 1000));
		t.add(new VehicleMeshes.Mesh(7, 1, new V3(20, 0, 0), new int[] {2, 0, 0, 5, 5, 5}, 1100, 200, new V3(0, 1, 0), new V3(0, 0, 1)));
		MeshTimeline.Pose p = t.sample(150);
		assertEquals(20.0, p.pos().x(), 1e-9);     // the newer one, whole
		assertEquals(2, p.nodeCount());
	}

	@Test
	void emptyTimelineHasNoPose() {
		assertNull(new MeshTimeline().sample(0));
	}

	@Test
	void senderClockOffsetFollowsTheFastestArrival() {
		MeshTimeline.Clock c = new MeshTimeline.Clock();
		c.observe(5000, 100);   // arrived 4900 ms "after" it was sent (clocks differ)
		c.observe(5112, 200);   // a late one: 4912
		c.observe(5195, 300);   // an early one: 4895
		assertEquals(4895, c.offsetMs(), 0.5);
		assertEquals(305, c.senderTimeAt(5200), 0.5);
	}
}
