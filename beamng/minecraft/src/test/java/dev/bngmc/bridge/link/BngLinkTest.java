package dev.bngmc.bridge.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.function.BooleanSupplier;

/** Runs BngLink against a minimal fake BeamNG on loopback. */
class BngLinkTest {
	private FakeBeamng bng;
	private BngLink link;

	@BeforeEach
	void setUp() throws Exception {
		bng = new FakeBeamng(1000);
		link = new BngLink(bng.port());
	}

	@AfterEach
	void tearDown() throws Exception {
		link.stop();
		bng.close();
	}

	@Test
	void connectsReceivesStateAndMeasuresRtt() throws Exception {
		link.start("test");
		assertTrue(waitFor(link::connected, 3000), "no WELCOME");
		assertEquals(1000, link.sessionId());
		for (int i = 0; i < 5; i++) {
			bng.sendState(i);
		}
		assertTrue(waitFor(() -> link.latest(Protocol.STATE) != null, 2000));
		assertTrue(waitFor(() -> link.stats().rttMs() >= 0, 2000), "no RTT from ping/pong");
		BngState s = BngState.parse(link.latest(Protocol.STATE), BngLink.nowMs());
		assertEquals("smallgrid", s.level());
		assertNotNull(s.vehicle());
		assertEquals(12.0, s.vehicle().pos().x(), 1e-9);
	}

	@Test
	void countsDroppedMessagesAndDiscardsOldOnes() throws Exception {
		link.start("test");
		assertTrue(waitFor(link::connected, 3000));
		long droppedBefore = link.stats().dropped.get();
		bng.sendState(1);
		bng.skipSeq(3);           // three messages "lost"
		bng.sendState(2);
		assertTrue(waitFor(() -> link.stats().dropped.get() >= droppedBefore + 3, 2000),
			"dropped=" + link.stats().dropped.get() + ", before=" + droppedBefore);
		bng.sendRawSeq(1, 0);     // a late duplicate from earlier in the session
		assertTrue(waitFor(() -> link.stats().stale.get() >= 1, 2000));
		assertEquals(2.0, link.latest(Protocol.STATE).body().get("n").getAsDouble());
	}

	@Test
	void reconnectsWhenBeamngRestarts() throws Exception {
		link.start("test");
		assertTrue(waitFor(link::connected, 3000));
		long first = link.sessionId();
		bng.restart(2000);        // new session ids; our next ping gets "unknown_session"
		assertTrue(waitFor(() -> link.connected() && link.sessionId() == 2000, 4000), "did not reconnect");
		assertNotEquals(first, link.sessionId());
		assertEquals(1, link.stats().reconnects.get());
	}

	@Test
	void timesOutWhenBeamngGoesSilent() throws Exception {
		link.start("test");
		assertTrue(waitFor(link::connected, 3000));
		bng.mute(true);
		assertTrue(waitFor(() -> !link.connected(), Protocol.PEER_TIMEOUT_MS + 2000), "still connected");
		bng.mute(false);
		assertTrue(waitFor(link::connected, 3000), "did not come back");
	}

	@Test
	void keepsTheSessionThroughAnExpectedStall() throws Exception {
		link = new BngLink(bng.port(), 300);
		link.start("test");
		assertTrue(waitFor(link::connected, 3000));
		assertTrue(waitFor(link::responsive, 2000));
		link.expectStall("exporting a car", 4000);
		bng.mute(true);           // BeamNG frozen in the export: nothing in, nothing out
		Thread.sleep(1500);       // five times the timeout
		assertTrue(link.connected(), "dropped the session during an expected stall");
		assertFalse(link.responsive(), "a silent BeamNG counted as responsive");
		assertEquals("exporting a car", link.stallReason());
		bng.mute(false);
		assertTrue(waitFor(link::responsive, 2000), "not responsive after the stall");
		assertEquals(0, link.stats().reconnects.get());
		assertEquals(1, link.stats().stallsHeld.get());
		assertTrue(link.stats().longestStallMs() >= 1000, "longest " + link.stats().longestStallMs());
	}

	@Test
	void dropsTheSessionWhenAStallOutlastsItsHold() throws Exception {
		link = new BngLink(bng.port(), 300);
		link.start("test");
		assertTrue(waitFor(link::connected, 3000));
		link.expectStall("spawning a car", 600);
		bng.mute(true);
		assertTrue(waitFor(() -> !link.connected(), 3000), "kept a session BeamNG never came back to");
		bng.mute(false);
		assertTrue(waitFor(link::connected, 3000), "did not come back");
		assertEquals(1, link.stats().reconnects.get());
	}

	private static boolean waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (cond.getAsBoolean()) {
				return true;
			}
			Thread.sleep(10);
		}
		return cond.getAsBoolean();
	}

	/** Implements just enough of the BeamNG side: hello/welcome, ping/pong, unknown_session. */
	static final class FakeBeamng implements AutoCloseable {
		private final DatagramSocket sock;
		private final Thread thread;
		private volatile long sid;
		private volatile long seq;
		private volatile SocketAddress client;
		private volatile boolean muted;
		private volatile boolean running = true;

		FakeBeamng(long sid) throws Exception {
			this.sid = sid;
			sock = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			sock.setSoTimeout(20);
			thread = new Thread(this::run, "fake-beamng");
			thread.setDaemon(true);
			thread.start();
		}

		int port() {
			return sock.getLocalPort();
		}

		void restart(long newSid) {
			sid = newSid;
			client = null;
			seq = 0;
		}

		void mute(boolean m) {
			muted = m;
		}

		void skipSeq(int n) {
			seq += n;
		}

		void sendState(int n) throws Exception {
			sendRawSeq(n, ++seq);
		}

		void sendRawSeq(int n, long s) throws Exception {
			JsonObject p = new JsonObject();
			p.addProperty("level", "smallgrid");
			p.addProperty("n", n);
			JsonObject veh = new JsonObject();
			veh.addProperty("id", 7);
			JsonArray pos = new JsonArray();
			pos.add(12.0);
			pos.add(34.0);
			pos.add(56.0);
			veh.add("pos", pos);
			p.add("veh", veh);
			send(Protocol.STATE, s, p);
		}

		private void send(String type, long s, JsonObject p) throws Exception {
			SocketAddress c = client;
			if (c == null || muted) {
				return;
			}
			byte[] d = Wire.encode(type, sid, s, 0, p);
			sock.send(new DatagramPacket(d, d.length, c));
		}

		private void run() {
			byte[] buf = new byte[65535];
			while (running) {
				try {
					DatagramPacket pkt = new DatagramPacket(buf, buf.length);
					sock.receive(pkt);
					if (muted) {
						continue;
					}
					Wire.Envelope env = Wire.decode(pkt.getData(), 0, pkt.getLength());
					if (Protocol.HELLO.equals(env.type())) {
						client = pkt.getSocketAddress();
						seq = 0;
						send(Protocol.WELCOME, ++seq, null);
					} else if (env.sid() != sid || client == null) {
						JsonObject p = new JsonObject();
						p.addProperty("code", "unknown_session");
						byte[] d = Wire.encode(Protocol.ERROR, env.sid(), 0, 0, p);
						sock.send(new DatagramPacket(d, d.length, pkt.getSocketAddress()));
					} else if (Protocol.PING.equals(env.type())) {
						JsonObject p = new JsonObject();
						p.addProperty("echo", env.ts());
						send(Protocol.PONG, ++seq, p);
					}
				} catch (SocketTimeoutException e) {
					// loop
				} catch (Exception e) {
					if (!running) {
						return;
					}
				}
			}
		}

		@Override
		public void close() throws Exception {
			running = false;
			thread.join(1000);
			sock.close();
		}
	}
}
