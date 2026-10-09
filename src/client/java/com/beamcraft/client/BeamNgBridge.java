package com.beamcraft.client;

import com.beamcraft.BeamCraftMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class BeamNgBridge {
	private static final int PORT = 23514;
	private static final ScheduledExecutorService POLLER = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "BeamCraft-BeamNG-poller");
		thread.setDaemon(true);
		return thread;
	});
	private static volatile State state = State.offline();
	private static volatile boolean previouslyConnected;

	private BeamNgBridge() {
	}

	static void start() {
		POLLER.scheduleWithFixedDelay(BeamNgBridge::poll, 0, 500, TimeUnit.MILLISECONDS);
	}

	static State getState() {
		return state;
	}

	private static void poll() {
		try {
			byte[] response = requestStatus();
			JsonObject root = JsonParser.parseString(new String(response, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("error")) {
				throw new IllegalStateException(root.get("error").getAsString());
			}

			boolean vehicleActive = root.has("connected") && root.get("connected").getAsBoolean();
			if (!vehicleActive) {
				state = State.noVehicle();
				logConnectionTransition(true, "Bridge is reachable; waiting for an active BeamNG vehicle.");
				return;
			}

			JsonObject vehicle = root.getAsJsonObject("vehicle");
			state = new State(
				true,
				true,
				readString(vehicle, "name", "BeamNG vehicle"),
				readNumber(vehicle, "speedMps"),
				readNumber(vehicle, "x"),
				readNumber(vehicle, "y"),
				readNumber(vehicle, "z")
			);
			logConnectionTransition(true, "Live vehicle telemetry received.");
		} catch (Exception exception) {
			state = State.offline();
			logConnectionTransition(false, exception.getMessage());
		}
	}

	private static byte[] requestStatus() throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
			socket.setSoTimeout(500);
			socket.getOutputStream().write((
				"GET /v1/status HTTP/1.1\r\n" +
				"Host: 127.0.0.1\r\n" +
				"Connection: close\r\n\r\n"
			).getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();

			ByteArrayOutputStream response = new ByteArrayOutputStream();
			socket.getInputStream().transferTo(response);
			if (response.size() == 0) {
				throw new IOException("BeamNG bridge returned an empty response.");
			}
			return response.toByteArray();
		}
	}

	private static String readString(JsonObject object, String key, String fallback) {
		return object.has(key) && !object.get(key).isJsonNull()
			? object.get(key).getAsString()
			: fallback;
	}

	private static double readNumber(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive()
			? object.get(key).getAsDouble()
			: 0.0;
	}

	private static void logConnectionTransition(boolean connected, String detail) {
		if (connected != previouslyConnected) {
			previouslyConnected = connected;
			if (connected) {
				BeamCraftMod.LOGGER.info("BeamNG bridge connected: {}", detail);
			} else {
				BeamCraftMod.LOGGER.warn("BeamNG bridge disconnected: {}", detail);
			}
		}
	}

	static final class State {
		final boolean connected;
		final boolean vehicleActive;
		final String vehicleName;
		final double speedMetersPerSecond;
		final double x;
		final double y;
		final double z;

		private State(boolean connected, boolean vehicleActive, String vehicleName, double speedMetersPerSecond, double x, double y, double z) {
			this.connected = connected;
			this.vehicleActive = vehicleActive;
			this.vehicleName = vehicleName;
			this.speedMetersPerSecond = speedMetersPerSecond;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		private static State noVehicle() {
			return new State(true, false, "", 0.0, 0.0, 0.0, 0.0);
		}

		private static State offline() {
			return new State(false, false, "", 0.0, 0.0, 0.0, 0.0);
		}
	}
}
