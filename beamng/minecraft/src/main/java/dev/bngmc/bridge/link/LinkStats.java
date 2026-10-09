package dev.bngmc.bridge.link;

import java.util.concurrent.atomic.AtomicLong;

/** Counters for one link, updated by the I/O thread and read by the HUD/logs. */
public final class LinkStats {
	public final AtomicLong rxMsgs = new AtomicLong();
	public final AtomicLong rxBytes = new AtomicLong();
	public final AtomicLong txMsgs = new AtomicLong();
	public final AtomicLong txBytes = new AtomicLong();
	/** Messages BeamNG sent us that never arrived (gaps in its sequence numbers). */
	public final AtomicLong dropped = new AtomicLong();
	/** Messages that arrived out of order or from an old session and were discarded. */
	public final AtomicLong stale = new AtomicLong();
	public final AtomicLong bad = new AtomicLong();
	public final AtomicLong reconnects = new AtomicLong();
	/** BeamNG freezes longer than the link's timeout that the session was kept through (BngLink.expectStall). */
	public final AtomicLong stallsHeld = new AtomicLong();
	private volatile long longestStallMs;

	private volatile double rttMs = -1;
	private volatile double rxMsgRate, rxByteRate, txMsgRate, txByteRate, stateRate;
	private long windowStartMs;
	private long winRxMsgs, winRxBytes, winTxMsgs, winTxBytes, winStates;
	private final AtomicLong states = new AtomicLong();

	public void noteRtt(double ms) {
		double r = rttMs;
		rttMs = r < 0 ? ms : r * 0.8 + ms * 0.2;
	}

	public void noteState() {
		states.incrementAndGet();
	}

	void noteStallHeld(long ms) {
		stallsHeld.incrementAndGet();
		longestStallMs = Math.max(longestStallMs, ms);
	}

	public long longestStallMs() {
		return longestStallMs;
	}

	public double rttMs() {
		return rttMs;
	}

	public void resetRtt() {
		rttMs = -1;
	}

	/** Called by the I/O thread; recomputes per-second rates once a second. */
	void tick(long nowMs) {
		if (windowStartMs == 0) {
			windowStartMs = nowMs;
			snapshot();
			return;
		}
		long dt = nowMs - windowStartMs;
		if (dt < 1000) {
			return;
		}
		double s = dt / 1000.0;
		rxMsgRate = (rxMsgs.get() - winRxMsgs) / s;
		rxByteRate = (rxBytes.get() - winRxBytes) / s;
		txMsgRate = (txMsgs.get() - winTxMsgs) / s;
		txByteRate = (txBytes.get() - winTxBytes) / s;
		stateRate = (states.get() - winStates) / s;
		windowStartMs = nowMs;
		snapshot();
	}

	private void snapshot() {
		winRxMsgs = rxMsgs.get();
		winRxBytes = rxBytes.get();
		winTxMsgs = txMsgs.get();
		winTxBytes = txBytes.get();
		winStates = states.get();
	}

	public double rxMsgRate() {
		return rxMsgRate;
	}

	public double rxByteRate() {
		return rxByteRate;
	}

	public double txMsgRate() {
		return txMsgRate;
	}

	public double txByteRate() {
		return txByteRate;
	}

	public double stateRate() {
		return stateRate;
	}
}
