package dev.bngmc.bridge.client;

import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.BridgeSettings;
import dev.bngmc.bridge.TerrainManager;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.BngState;
import dev.bngmc.bridge.link.LinkStats;
import dev.bngmc.bridge.link.Protocol;
import dev.bngmc.bridge.link.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** The crossover's status display (F9 toggles). Also used by the "status" dev command. */
public final class StatusHud {
	private StatusHud() {
	}

	/** On or off as the settings say (BridgeSettings.statusHud), so F9 is remembered. */
	public static boolean visible() {
		return BridgeSettings.get().statusHud();
	}

	public static boolean toggle() {
		BridgeSettings s = BridgeSettings.get();
		BridgeSettings.set(s.withStatusHud(!s.statusHud()));
		return !s.statusHud();
	}

	public static void render(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (!visible() || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		int y = 4;
		for (String line : lines(mc)) {
			int color = line.startsWith("===") ? 0xFFE080 : (line.contains("NO") && line.startsWith("BEAMNG CONNECTED") ? 0xFF6060 : 0xFFFFFF);
			g.drawString(mc.font, line, 4, y, color, true);
			y += 10;
		}
	}

	public static List<String> lines(Minecraft mc) {
		List<String> out = new ArrayList<>();
		BngLink link = BngLink.get();
		LinkStats s = link.stats();
		Wire.Envelope env = link.latest(Protocol.STATE);
		BngState st = env != null ? BngState.parse(env, BngLink.nowMs()) : null;
		Wire.Envelope w = link.welcome();
		out.add("BEAMNG CONNECTED: " + (link.connected() ? "YES" : "NO") + (link.connected() ? "  session " + link.sessionId() : ""));
		out.add("=== BEAMNG ===");
		if (w != null) {
			out.add("version: " + str(w.body(), "bngVersion") + "   protocol: v" + Protocol.VERSION);
		}
		if (st != null) {
			out.add(String.format("map: %s   fps: %.0f   paused: %s", st.level(), st.fps(), st.paused()));
			BngState.Vehicle v = st.vehicle();
			if (v != null) {
				out.add(String.format("vehicle: %d %s   speed: %.1f m/s (%.0f km/h)", v.id(), v.model(), v.speed(), v.speed() * 3.6));
				out.add("BeamNG position: " + fmt(v.pos()));
			}
			if (st.camera() != null) {
				out.add(String.format("camera: %s %s fov %.1f  %s", st.camera().mode(), fmt(st.camera().pos()), st.camera().fovDeg(),
					st.camera().overridden() ? "DRIVEN BY MINECRAFT (seq " + st.camera().appliedSeq() + ")" : "own"));
			}
		}
		out.add("=== BRIDGE ===");
		out.add(String.format("round-trip latency: %s   state rate: %.1f Hz   last state %d ms ago", s.rttMs() < 0 ? "-" : String.format("%.2f ms", s.rttMs()),
			s.stateRate(), Math.min(link.msSinceRx(), 99999)));
		out.add(String.format("rx %.0f msg/s %.1f KiB/s   tx %.0f msg/s %.1f KiB/s", s.rxMsgRate(), s.rxByteRate() / 1024, s.txMsgRate(),
			s.txByteRate() / 1024));
		out.add(String.format("dropped %d   stale %d   bad %d   reconnects %d   stalls held %d (longest %.1f s)", s.dropped.get(), s.stale.get(),
			s.bad.get(), s.reconnects.get(), s.stallsHeld.get(), s.longestStallMs() / 1000.0));
		if (link.connected() && !link.responsive()) {
			String why = link.stallReason();
			out.add(String.format("BEAMNG BUSY: no data for %.1f s%s, session kept", link.msSinceRx() / 1000.0, why.isEmpty() ? "" : " (" + why + ")"));
		}
		out.add("terrain: " + TerrainManager.describe() + (TerrainHold.holding() ? "   HOLDING (ground not sampled yet)" : ""));
		out.add("blocks mirrored in BeamNG as collision cubes: " + dev.bngmc.bridge.BlockSync.count());
		out.add(String.format("vehicles: %d in BeamNG data (%.0f ms old), %d Minecraft proxies", dev.bngmc.bridge.link.Vehicles.latest().size(),
			Math.min(dev.bngmc.bridge.link.Vehicles.ageMs(), 99999), dev.bngmc.bridge.entity.VehicleBridge.count()));
		out.add("=== MINECRAFT ===");
		out.add(String.format("fps: %d   camera sync: %s%s   fov %.0f   control: %s", mc.getFps(), CameraSync.mode(), CameraSync.driving() ? " (sending)" : "",
			CameraSync.fov(), ControlSwitch.mode()));
		Win32.Rect r0 = Overlay.appliedRect();
		out.add("overlay: " + (Overlay.active() ? "ON" : "off") + (Overlay.enabled() ? "" : " (F6 disabled)")
			+ (Overlay.transparentWindow() ? "" : " (no transparent framebuffer)")
			+ (r0 != null ? String.format("   glued to BeamNG %dx%d at %d,%d", r0.w(), r0.h(), r0.x(), r0.y()) : "")
			+ (Overlay.beamngHwnd() == 0 ? "   BeamNG window not found" : ""));
		CrossoverCoords.Region r = BngWorld.region();
		if (mc.player != null) {
			out.add(String.format("player MC: %.2f %.2f %.2f   yaw %.1f pitch %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ(),
				mc.player.getYRot(), mc.player.getXRot()));
			if (r != null) {
				V3 c = CrossoverCoords.minecraftToCanonicalPosition(new V3(mc.player.getX(), mc.player.getY(), mc.player.getZ()), r);
				out.add("player canonical (BeamNG): " + fmt(c) + "   region " + r.index());
			}
		}
		out.add("camera sent: " + fmt(CameraSync.lastPos()) + " dir " + fmt(CameraSync.lastFwd()) + "  seq " + CameraSync.cseq());
		if (BngWorld.isHostWorld()) {
			out.add("=== MINECRAFT HOSTS ===");
			out.add("car cutouts: " + (CarCutouts.enabled() ? CarCutouts.describe() : "off") + "   shapes from BeamNG: "
				+ dev.bngmc.bridge.link.VehicleMeshes.meshes().size());
			out.add("driving: " + (HostDrive.seated(mc) ? HostDrive.describe() : "not in a car"));
			out.add("native cars: " + NativeCars.describe());
			out.add("terrain: " + dev.bngmc.bridge.TerrainSync.describe());
		}
		return out;
	}

	private static String fmt(V3 v) {
		return v == null ? "-" : String.format("(%.2f, %.2f, %.2f)", v.x(), v.y(), v.z());
	}

	private static String str(com.google.gson.JsonObject o, String k) {
		return o.has(k) ? o.get(k).getAsString() : "?";
	}
}
