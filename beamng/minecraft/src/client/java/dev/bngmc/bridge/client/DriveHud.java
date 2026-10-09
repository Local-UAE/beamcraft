package dev.bngmc.bridge.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import dev.bngmc.bridge.link.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The car's dashboard on Minecraft's HUD while driving in a Minecraft-hosted world: gear, speed
 * and an RPM bar, from BeamNG's own electrics (STATE's "dash": gear, rpm, maxrpm, the gearbox
 * mode; the ESC or drive mode; wet and flooded when the car is in water), bottom right like
 * BeamNG's own gauge. The controls list folds away: J shows it (keyboard and PlayStation pad).
 */
public final class DriveHud {
	private DriveHud() {
	}

	private static boolean help;

	/** J: the controls list on or off. */
	public static void toggleHelp() {
		help = !help;
	}

	public static void render(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (!BngWorld.isHostWorld() || !HostDrive.seated(mc) || mc.options.hideGui || !dev.bngmc.bridge.BridgeSettings.get().dashboard()) {
			return;
		}
		Wire.Envelope env = BngLink.get().latest(Protocol.STATE);
		if (env == null) {
			return;
		}
		JsonObject st = env.body();
		double speed = st.has("veh") && st.getAsJsonObject("veh").has("speed") ? st.getAsJsonObject("veh").get("speed").getAsDouble() : 0;
		JsonObject dash = st.has("dash") && st.get("dash").isJsonObject() ? st.getAsJsonObject("dash") : new JsonObject();
		String gear = str(dash.get("gear"), "-");
		double rpm = num(dash.get("rpm"), 0), maxRpm = Math.max(1000, num(dash.get("maxrpm"), 7000));

		String esc = str(dash.get("esc"), "");   // "ESC: ..." or "Mode: ..." (G changes it)
		int w = g.guiWidth(), h = g.guiHeight();
		int x = w - 112, y = h - (esc.isEmpty() ? 62 : 74);
		g.fill(x - 6, y - 6, w - 6, h - 6, 0x90000000);
		g.pose().pushPose();
		g.pose().translate(x, y, 0);
		g.pose().scale(3.0F, 3.0F, 1.0F);
		g.drawString(mc.font, gear, 0, 0, 0xFFFFFFFF, true);
		g.pose().popPose();
		g.drawString(mc.font, String.format("%.0f km/h", speed * 3.6), x + 34, y + 2, 0xFFFFFFFF, true);
		g.drawString(mc.font, String.format("%.0f rpm", rpm), x + 34, y + 14, 0xFFB0B0B0, true);
		int barW = 94, filled = (int) Math.round(barW * Math.min(1.0, rpm / maxRpm));
		int color = rpm > maxRpm * 0.9 ? 0xFFE04040 : rpm > maxRpm * 0.75 ? 0xFFE0C040 : 0xFF40C060;
		g.fill(x, y + 30, x + barW, y + 36, 0xFF303030);
		g.fill(x, y + 30, x + filled, y + 36, color);
		String mode = str(dash.get("mode"), "");
		if (flag(dash.get("flooded"))) {
			g.drawString(mc.font, "engine flooded", x, y + 40, 0xFFE04040, true);   // BeamNG's hydrolock; reset (R) repairs it
		} else if (flag(dash.get("wet"))) {
			g.drawString(mc.font, "in water", x, y + 40, 0xFF60A0E0, true);
		} else if (!mode.isEmpty()) {
			g.drawString(mc.font, mode, x, y + 40, 0xFF909090, true);
		}
		if (!esc.isEmpty()) {
			g.drawString(mc.font, esc, x, y + 52, 0xFF80C0FF, true);
		}
		drawControls(g, mc, w, y - 10);
	}

	private static final String[][] PAD = {
		{"Drive", "W A S D", "R2 / L2, stick"},
		{"Handbrake", "Space", "Circle"},
		{"Shift up / down", null, "Square / Cross"},
		{"Gearbox mode", null, "R1"},
		{"ESC / drive mode", null, "D-pad right"},
		{"Reset to spawn", null, "Triangle"},
		{"Repair (tap) / rewind (hold)", null, "Share"},
		{"Starter", null, "L1"},
		{"Horn", null, "L3"},
		{"Lights", null, "D-pad up"},
		{"Camera view", "F5", "D-pad down"},
		{"Get out", "Shift", "R3"},
	};
	private static final String[] ACTION = {null, null, "shift", "gearbox_mode", "esc_mode", "reset", "recover", "starter", "horn", "lights", null, null};

	/** The controls list bottom up from baseY, right aligned; folded to one line unless J opened it. */
	private static void drawControls(GuiGraphics g, Minecraft mc, int w, int baseY) {
		if (!help) {
			String fold = BngBridgeClient.controlsKeyName() + "  controls";
			g.drawString(mc.font, fold, w - 6 - mc.font.width(fold), baseY - 8, 0x80FFFFFF, true);
			return;
		}
		int rows = PAD.length, lineH = 10, colW = 0;
		String[] left = new String[rows], right = new String[rows];
		for (int i = 0; i < rows; i++) {
			String key = PAD[i][1];
			if (ACTION[i] != null) {
				key = "shift".equals(ACTION[i]) ? CarControls.key("shift_up") + " / " + CarControls.key("shift_down") : CarControls.key(ACTION[i]);
			}
			left[i] = PAD[i][0];
			right[i] = key + "   " + PAD[i][2];
			colW = Math.max(colW, mc.font.width(left[i]) + 10 + mc.font.width(right[i]));
		}
		int top = baseY - rows * lineH - 4, x0 = w - 12 - colW;
		g.fill(x0 - 6, top - 4, w - 6, baseY, 0x90000000);
		for (int i = 0; i < rows; i++) {
			int y = top + i * lineH;
			g.drawString(mc.font, left[i], x0, y, 0xFFB0B0B0, true);
			g.drawString(mc.font, right[i], w - 12 - mc.font.width(right[i]), y, 0xFFFFFFFF, true);
		}
	}

	private static String str(JsonElement e, String def) {
		if (e == null || e.isJsonNull()) {
			return def;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			int n = e.getAsInt();
			return n < 0 ? "R" : n == 0 ? "N" : String.valueOf(n);
		}
		return e.getAsString();
	}

	private static boolean flag(JsonElement e) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	private static double num(JsonElement e, double def) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : def;
	}
}
