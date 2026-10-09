package dev.bngmc.bridge.link;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/**
 * Datagram encoding. Version 1 is JSON (one object per datagram) with a fixed envelope:
 * {@code {"v":1,"t":type,"sid":session,"seq":n,"ts":senderMillis, ...payload}}.
 * A compact binary encoding can be added behind the same envelope later (see protocol.md).
 */
public final class Wire {
	private Wire() {
	}

	/** A decoded datagram. {@code body} is the whole object, envelope fields included. */
	public record Envelope(int version, String type, long sid, long seq, double ts, JsonObject body) {
	}

	public static final class BadMessage extends Exception {
		public BadMessage(String msg) {
			super(msg);
		}
	}

	public static byte[] encode(String type, long sid, long seq, double ts, JsonObject payload) {
		JsonObject o = new JsonObject();
		o.addProperty("v", Protocol.VERSION);
		o.addProperty("t", type);
		o.addProperty("sid", sid);
		o.addProperty("seq", seq);
		o.addProperty("ts", ts);
		if (payload != null) {
			for (var e : payload.entrySet()) {
				o.add(e.getKey(), e.getValue());
			}
		}
		return o.toString().getBytes(StandardCharsets.UTF_8);
	}

	public static Envelope decode(byte[] data, int off, int len) throws BadMessage {
		if (len <= 0 || data[off] != '{') {
			throw new BadMessage("not a JSON object");
		}
		JsonElement el;
		try {
			el = JsonParser.parseString(new String(data, off, len, StandardCharsets.UTF_8));
		} catch (RuntimeException e) {
			throw new BadMessage("malformed JSON: " + e.getMessage());
		}
		if (!el.isJsonObject()) {
			throw new BadMessage("not a JSON object");
		}
		JsonObject o = el.getAsJsonObject();
		if (!o.has("v") || !o.has("t")) {
			throw new BadMessage("missing envelope fields");
		}
		try {
			int v = o.get("v").getAsInt();
			String t = o.get("t").getAsString();
			long sid = o.has("sid") ? o.get("sid").getAsLong() : 0L;
			long seq = o.has("seq") ? o.get("seq").getAsLong() : 0L;
			double ts = o.has("ts") ? o.get("ts").getAsDouble() : 0.0;
			return new Envelope(v, t, sid, seq, ts, o);
		} catch (RuntimeException e) {
			throw new BadMessage("bad envelope: " + e.getMessage());
		}
	}
}
