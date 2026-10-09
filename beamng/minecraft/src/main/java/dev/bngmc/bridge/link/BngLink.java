package dev.bngmc.bridge.link;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Minecraft's end of the control link to BeamNG (UDP, 127.0.0.1). One daemon I/O thread
 * receives, keeps the newest message of each type ("latest state wins"), resends HELLO while
 * disconnected and pings while connected. Game threads only read snapshots and send; nothing
 * here ever blocks the render thread or the server thread.
 */
public final class BngLink {
	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static final BngLink INSTANCE = new BngLink(Integer.getInteger("bngbridge.port", Protocol.DEFAULT_PORT));

	public static BngLink get() {
		return INSTANCE;
	}

	private final LinkStats stats = new LinkStats();
	private final Map<String, Wire.Envelope> latest = new ConcurrentHashMap<>();
	private final Map<String, Long> lastSeqByType = new ConcurrentHashMap<>();
	private final Map<String, java.util.List<Consumer<Wire.Envelope>>> listeners = new ConcurrentHashMap<>();
	private final Object sendLock = new Object();

	private volatile DatagramSocket socket;
	private volatile InetSocketAddress peer;
	private volatile boolean connected;
	private volatile long sessionId;
	private volatile long lastRxMs;
	private volatile Wire.Envelope welcome;
	private volatile String clientName = "minecraft";
	private boolean everConnected;
	private long txSeq;
	private long lastPeerSeq = -1;
	private long lastHelloMs;
	private long lastPingMs;
	private long lastLogMs;
	private Thread thread;
	private volatile boolean stopping;
	private final int port;
	private final long peerTimeoutMs;
	// A BeamNG freeze we asked for (a car export, a spawn) is expected until then: the session is
	// kept through it instead of dropped after peerTimeoutMs (expectStall)
	private volatile long holdUntilMs;
	private volatile String holdWhy = "";
	private boolean holding;

	/** Package-private for tests; the game uses {@link #get()}. */
	BngLink(int port) {
		this(port, Protocol.PEER_TIMEOUT_MS);
	}

	/** Tests: a shorter silence timeout. */
	BngLink(int port, long peerTimeoutMs) {
		this.port = port;
		this.peerTimeoutMs = peerTimeoutMs;
	}

	/** Starts the I/O thread once; later calls do nothing. */
	public synchronized void start(String name) {
		if (thread != null) {
			return;
		}
		clientName = name;
		peer = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
		thread = new Thread(this::run, "bngbridge-link");
		thread.setDaemon(true);
		thread.start();
		LOG.info("BeamNG link: talking to {}:{} as '{}'", Protocol.HOST, port, name);
	}

	public boolean connected() {
		return connected;
	}

	public long sessionId() {
		return sessionId;
	}

	public LinkStats stats() {
		return stats;
	}

	/** WELCOME of the current session (BeamNG version, level...), or null. */
	public Wire.Envelope welcome() {
		return welcome;
	}

	/** Newest message of a type from the current session, or null. */
	public Wire.Envelope latest(String type) {
		return connected ? latest.get(type) : null;
	}

	/** Milliseconds since anything arrived from BeamNG. */
	public long msSinceRx() {
		return lastRxMs == 0 ? Long.MAX_VALUE : (long) nowMs() - lastRxMs;
	}

	/**
	 * BeamNG is about to stand still for up to ms (why: what it is doing, for the log and the HUD):
	 * a car export holds its game thread 17-29 s for big mods, a spawn a few seconds more (measured
	 * 2026-10-03). Until then silence doesn't end the session. Dropping it used to make every big car
	 * a reconnect, with the car picker saying "BeamNG isn't connected" meanwhile.
	 */
	public void expectStall(String why, long ms) {
		long until = (long) nowMs() + ms;
		if (until > holdUntilMs) {
			holdUntilMs = until;
			holdWhy = why;
		}
	}

	/**
	 * Connected and heard from within Protocol.RESPONSIVE_MS: BeamNG is running, not standing still
	 * in a car load. Messages that change something once (a spawn, block edits, terrain patches) wait
	 * for this, since datagrams sent to a frozen BeamNG pile up in its socket and the overflow is lost.
	 */
	public boolean responsive() {
		return connected && msSinceRx() < Protocol.RESPONSIVE_MS;
	}

	/** While connected but quiet: what BeamNG was asked to do that holds it still ("" if nothing was). */
	public String stallReason() {
		return (long) nowMs() < holdUntilMs ? holdWhy : "";
	}

	/** Called on the I/O thread for every accepted message of that type (keep it cheap). */
	public void onMessage(String type, Consumer<Wire.Envelope> listener) {
		listeners.computeIfAbsent(type, t -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(listener);
	}

	/** Sends a message if connected. Returns false if not connected, too big, or the send failed. */
	public boolean send(String type, JsonObject payload) {
		if (!connected) {
			return false;
		}
		return sendRaw(type, payload);
	}

	private boolean sendRaw(String type, JsonObject payload) {
		DatagramSocket s = socket;
		if (s == null) {
			return false;
		}
		synchronized (sendLock) {
			byte[] data = Wire.encode(type, sessionId, ++txSeq, nowMs(), payload);
			if (data.length > Protocol.MAX_TO_BEAMNG) {
				LOG.warn("Dropping {} message of {} bytes (limit {})", type, data.length, Protocol.MAX_TO_BEAMNG);
				return false;
			}
			try {
				s.send(new DatagramPacket(data, data.length, peer));
				stats.txMsgs.incrementAndGet();
				stats.txBytes.addAndGet(data.length);
				return true;
			} catch (IOException e) {
				return false;
			}
		}
	}

	public static double nowMs() {
		return System.nanoTime() / 1.0e6;
	}

	// -- I/O thread ---------------------------------------------------------------------------------

	private void run() {
		byte[] buf = new byte[Protocol.MAX_FROM_BEAMNG];
		DatagramPacket pkt = new DatagramPacket(buf, buf.length);
		while (!stopping) {
			try {
				if (socket == null) {
					DatagramSocket s = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
					s.setSoTimeout(20);
					s.setReceiveBufferSize(1 << 20);
					socket = s;
				}
				housekeeping();
				try {
					pkt.setLength(buf.length);
					socket.receive(pkt);
				} catch (SocketTimeoutException e) {
					continue;
				}
				handle(pkt);
			} catch (IOException | RuntimeException e) {
				// Windows reports ICMP "port unreachable" (BeamNG not listening) as an exception on the
				// next receive. That's normal while BeamNG is down; keep going.
				if (nowMs() - lastLogMs > 10000) {
					lastLogMs = (long) nowMs();
					LOG.debug("BeamNG link I/O: {}", e.toString());
				}
				sleepQuietly(50);
			}
		}
	}

	private void housekeeping() {
		long now = (long) nowMs();
		stats.tick(now);
		if (connected && msSinceRx() > peerTimeoutMs) {
			if (now < holdUntilMs) {
				if (!holding) {
					holding = true;
					LOG.info("BeamNG link: no data for {} ms while BeamNG is {}: keeping the session", msSinceRx(), holdWhy);
				}
			} else {
				LOG.warn("BeamNG link: no data for {} ms, reconnecting", msSinceRx());
				dropSession();
			}
		}
		if (!connected) {
			if (now - lastHelloMs >= Protocol.HELLO_INTERVAL_MS) {
				lastHelloMs = now;
				JsonObject p = new JsonObject();
				p.addProperty("client", clientName);
				p.addProperty("protocol", Protocol.VERSION);
				sessionId = 0;
				sendRaw(Protocol.HELLO, p);
			}
		} else if (now - lastPingMs >= Protocol.PING_INTERVAL_MS) {
			lastPingMs = now;
			sendRaw(Protocol.PING, null);
		}
	}

	private void dropSession() {
		holding = false;
		connected = false;
		sessionId = 0;
		welcome = null;
		latest.clear();
		lastSeqByType.clear();
		lastPeerSeq = -1;
		stats.resetRtt();
	}

	private void handle(DatagramPacket pkt) {
		stats.rxBytes.addAndGet(pkt.getLength());
		Wire.Envelope env;
		try {
			env = Wire.decode(pkt.getData(), pkt.getOffset(), pkt.getLength());
		} catch (Wire.BadMessage e) {
			stats.bad.incrementAndGet();
			return;
		}
		if (env.version() != Protocol.VERSION) {
			stats.bad.incrementAndGet();
			if (nowMs() - lastLogMs > 5000) {
				lastLogMs = (long) nowMs();
				LOG.warn("BeamNG speaks protocol v{}, we speak v{}; ignoring it", env.version(), Protocol.VERSION);
			}
			return;
		}
		stats.rxMsgs.incrementAndGet();
		if (Protocol.WELCOME.equals(env.type())) {
			if (connected && env.sid() == sessionId) {
				// BeamNG answered a repeated HELLO with the same session: nothing changes.
				welcome = env;
				lastRxMs = (long) nowMs();
				return;
			}
			dropSession();
			sessionId = env.sid();
			welcome = env;
			connected = true;
			lastPeerSeq = env.seq();
			lastRxMs = (long) nowMs();
			if (everConnected) {
				stats.reconnects.incrementAndGet();
			}
			everConnected = true;
			LOG.info("BeamNG link: connected, session {} ({})", env.sid(), env.body());
			dispatch(env);
			return;
		}
		if (Protocol.ERROR.equals(env.type()) && connected && env.sid() == sessionId && env.body().has("code")
			&& "unknown_session".equals(env.body().get("code").getAsString())) {
			LOG.info("BeamNG link: BeamNG forgot our session (restarted?), saying hello again");
			dropSession();
			return;
		}
		if (!connected || env.sid() != sessionId) {
			stats.stale.incrementAndGet();
			return;
		}
		long quietMs = (long) nowMs() - lastRxMs;
		if (quietMs > peerTimeoutMs) {
			stats.noteStallHeld(quietMs);
			LOG.info("BeamNG link: BeamNG answered again after {} s ({}), same session", String.format("%.1f", quietMs / 1000.0), holdWhy);
		}
		holding = false;
		lastRxMs = (long) nowMs();
		if (lastPeerSeq >= 0) {
			long gap = env.seq() - lastPeerSeq;
			if (gap > 1) {
				stats.dropped.addAndGet(gap - 1);
			} else if (gap <= 0) {
				stats.stale.incrementAndGet();
				return;  // duplicate or reordered: newer data already arrived
			}
		}
		lastPeerSeq = env.seq();
		if (Protocol.BYE.equals(env.type())) {
			LOG.info("BeamNG link: BeamNG closed the session ({}), reconnecting", env.body());
			dropSession();
			return;
		}
		lastSeqByType.put(env.type(), env.seq());
		if (Protocol.PONG.equals(env.type()) && env.body().has("echo")) {
			stats.noteRtt(nowMs() - env.body().get("echo").getAsDouble());
		}
		if (Protocol.STATE.equals(env.type())) {
			stats.noteState();
		}
		latest.put(env.type(), env);
		dispatch(env);
	}

	private void dispatch(Wire.Envelope env) {
		for (Consumer<Wire.Envelope> l : listeners.getOrDefault(env.type(), java.util.List.of())) {
			try {
				l.accept(env);
			} catch (RuntimeException e) {
				LOG.warn("BeamNG link: {} listener failed: {}", env.type(), e.toString());
			}
		}
	}

	/** Tests only: says goodbye and stops the I/O thread. */
	synchronized void stop() throws InterruptedException {
		if (thread == null) {
			return;
		}
		if (connected) {
			sendRaw(Protocol.BYE, null);
		}
		stopping = true;
		thread.join(2000);
		DatagramSocket s = socket;
		if (s != null) {
			s.close();
		}
	}

	private static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
