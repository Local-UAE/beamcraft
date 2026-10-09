package dev.bngmc.bridge.client;

import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.TerrainManager;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Development channel (adapted from upstream): runs the lines written to
 * {@code %TEMP%\bngmc\mc_cmd.txt}, once per client tick, so tests can drive Minecraft from a
 * terminal (scripts\mc.ps1). Results are appended to {@code %TEMP%\bngmc\mc_out.txt}.
 *
 * <pre>
 *   status                     HUD lines -> mc_out.txt
 *   run &lt;command&gt;              a chat command, without the slash (the bridge world allows cheats)
 *   look &lt;yaw&gt; &lt;pitch&gt;         set the player's view
 *   turn &lt;dyaw&gt; &lt;dpitch&gt; &lt;ticks&gt;   turn smoothly
 *   key &lt;forward|back|left|right|jump|sneak|sprint&gt; &lt;ticks&gt;   hold a movement key
 *   cam drive|follow|off       camera sync mode
 *   screenshot &lt;name&gt;          Minecraft screenshot into run/screenshots
 *   terrain reset | recall     resample terrain | move to the BeamNG vehicle
 *   tdump x0 z0 x1 z1          TerrainSync's columns there (raw, driven, surface, decks, choice) -> tdump.json
 *   markers                    wool blocks 8 m ahead + BeamNG spheres at the same points (alignment)
 *   column [dx dz] [r]         terrain blocks (y:height) of the columns around the player
 *   enter                      get into the nearest BeamNG car (same as right-clicking its proxy)
 *   switch beamng|mc           hand the controls over (same as F4)
 *   click attack|use           one left / right mouse click
 *   hitboxes on|off            entity hitboxes (F3+B)
 *   view first|back|front      the perspective (in a car: driver's seat, chase, from ahead)
 *   fullscreen on|off          Minecraft's own fullscreen (F11), for recording
 *   closescreen                close whatever screen is open (the car picker)
 *   native debug N             car shader debug view (CarShader.setDebugView)
 *   settings [open]            the crossover settings as JSON | open the settings screen
 *   press &lt;text&gt;               press the open screen's button whose label contains the text
 *   set &lt;name&gt; &lt;json value&gt;     change one setting by its name in config/bngbridge.json (set ground "blocks")
 *   clearchat | hud on|off | quit
 * </pre>
 */
public final class DevCommands {
	private DevCommands() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	public static final Path DIR = Path.of(System.getProperty("java.io.tmpdir"), "bngmc");
	private static final Path FILE = DIR.resolve("mc_cmd.txt");
	private static final Path OUT = DIR.resolve("mc_out.txt");

	private static final java.util.List<KeyMapping> heldKeys = new java.util.ArrayList<>();
	private static int heldTicks;
	private static float turnYaw, turnPitch;
	private static int turnTicks;

	public static void tick(Minecraft mc) {
		holdAndTurn(mc);
		if (!Files.exists(FILE)) {
			return;
		}
		List<String> lines;
		try {
			// Written before this game started (say a "quit" for a Minecraft that had already closed):
			// not meant for us.
			boolean stale = Files.getLastModifiedTime(FILE).toMillis() < java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
			lines = Files.readAllLines(FILE);
			Files.delete(FILE);
			if (stale) {
				LOG.info("Ignored {} dev command(s) left over from before this start", lines.size());
				return;
			}
		} catch (IOException e) {
			return;   // the writer still has it open: next tick
		}
		for (String line : lines) {
			line = line.strip();
			if (!line.isEmpty()) {
				try {
					run(mc, line);
				} catch (RuntimeException e) {
					out("ERR " + line + ": " + e);
				}
			}
		}
	}

	private static void holdAndTurn(Minecraft mc) {
		if (!heldKeys.isEmpty() && --heldTicks <= 0) {
			heldKeys.forEach(k -> k.setDown(false));
			heldKeys.clear();
		}
		if (turnTicks > 0 && mc.player != null) {
			turnTicks--;
			mc.player.setYRot(mc.player.getYRot() + turnYaw);
			mc.player.setXRot(Math.max(-90, Math.min(90, mc.player.getXRot() + turnPitch)));
		}
	}

	private static void run(Minecraft mc, String line) {
		String[] a = line.split("\\s+");
		switch (a[0]) {
			case "status" -> {
				out("STATUS " + System.currentTimeMillis());
				for (String s : StatusHud.lines(mc)) {
					out("  " + s);
				}
			}
			case "run" -> {
				String cmd = line.substring(3).strip();
				if (cmd.startsWith("/")) {
					cmd = cmd.substring(1);
				}
				if (mc.player != null) {
					mc.player.connection.sendCommand(cmd);
					out("OK run " + cmd);
				}
			}
			case "look" -> {
				if (mc.player != null) {
					mc.player.setYRot(Float.parseFloat(a[1]));
					mc.player.setXRot(Float.parseFloat(a[2]));
					out("OK look " + a[1] + " " + a[2]);
				}
			}
			case "turn" -> {
				turnTicks = Integer.parseInt(a[3]);
				turnYaw = Float.parseFloat(a[1]) / turnTicks;
				turnPitch = Float.parseFloat(a[2]) / turnTicks;
				out("OK turn");
			}
			case "key" -> {
				// key forward 40, or several at once: key forward,right 40 (ticks)
				heldKeys.forEach(k -> k.setDown(false));
				heldKeys.clear();
				for (String name : a[1].split(",")) {
					KeyMapping k = switch (name) {
						case "forward" -> mc.options.keyUp;
						case "back" -> mc.options.keyDown;
						case "left" -> mc.options.keyLeft;
						case "right" -> mc.options.keyRight;
						case "jump" -> mc.options.keyJump;
						case "sneak" -> mc.options.keyShift;
						case "sprint" -> mc.options.keySprint;
						default -> throw new IllegalArgumentException("unknown key " + name);
					};
					k.setDown(true);
					heldKeys.add(k);
				}
				heldTicks = Integer.parseInt(a[2]);
				out("OK key " + a[1] + " " + a[2]);
			}
			case "cam" -> {
				CameraSync.setMode(switch (a[1]) {
					case "drive" -> CameraSync.Mode.DRIVE;
					case "follow" -> CameraSync.Mode.FOLLOW;
					default -> CameraSync.Mode.OFF;
				});
				out("OK cam " + CameraSync.mode());
			}
			case "screenshot" -> {
				String name = a.length > 1 ? a[1] + ".png" : null;
				Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> out("OK screenshot " + msg.getString()));
			}
			case "terrain" -> {
				TerrainManager.requestReset();
				out("OK terrain reset");
			}
			case "tdump" -> {
				// tdump x0 z0 x1 z1: TerrainSync's columns there -> %TEMP%\bngmc\tdump.json
				var json = dev.bngmc.bridge.TerrainSync.dump(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]),
					Integer.parseInt(a[4]));
				try {
					Files.writeString(DIR.resolve("tdump.json"), json.toString(), StandardCharsets.UTF_8);
					out("OK tdump " + (json.has("error") ? json.get("error").getAsString() : json.get("w") + "x" + json.get("h")));
				} catch (IOException e) {
					out("ERR tdump: " + e);
				}
			}
			case "recall" -> {
				BngWorld.requestRecall();
				out("OK recall");
			}
			case "markers" -> markers(mc);
			case "column" -> column(mc, a);
			case "enter" -> {
				if (mc.player == null || mc.level == null) {
					return;
				}
				var near = mc.level.getEntitiesOfClass(dev.bngmc.bridge.entity.BngVehicleEntity.class, mc.player.getBoundingBox().inflate(64));
				near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
				if (near.isEmpty()) {
					out("ERR enter: no BeamNG car within 64 blocks");
				} else {
					ControlSwitch.enterVehicle(mc, near.get(0).bngId());
					out("OK enter " + near.get(0).bngId() + " (" + near.get(0).model() + ")");
				}
			}
			case "switch" -> {
				if ("mc".equals(a[1])) {
					ControlSwitch.toMinecraft(mc);
				} else {
					ControlSwitch.toBeamng(mc);
				}
				out("OK switch " + ControlSwitch.mode());
			}
			case "click" -> {
				// One left/right click, through the same KeyMapping path the mouse uses.
				KeyMapping k = "use".equals(a[1]) ? mc.options.keyUse : mc.options.keyAttack;
				KeyMapping.click(KeyBindingHelper.getBoundKeyOf(k));
				out("OK click " + a[1]);
			}
			case "hitboxes" -> {
				mc.getEntityRenderDispatcher().setRenderHitBoxes(!"off".equals(a[1]));
				out("OK hitboxes " + a[1]);
			}
			case "probe" -> probe(mc);
			case "clearchat" -> {
				mc.gui.getChat().clearMessages(false);
				out("OK clearchat");
			}
			case "hud" -> {
				boolean want = !"off".equals(a[1]);
				if (StatusHud.toggle() != want) {
					StatusHud.toggle();
				}
				out("OK hud " + want);
			}
			case "native" -> {
				if (a.length < 2) {
					// no argument: only say how things stand
				} else if ("export".equals(a[1])) {
					NativeCars.retryExports();
				} else if ("reload".equals(a[1])) {
					NativeCars.reloadAll();
				} else if ("blend".equals(a[1])) {
					NativeCars.setBlend(!"off".equals(a[2]));
				} else if ("debug".equals(a[1])) {
					CarShader.setDebugView(Integer.parseInt(a[2]));
				} else if ("shaderdebug".equals(a[1])) {
					ShaderCarTarget.debug = Integer.parseInt(a[2]);
				} else if ("packpath".equals(a[1])) {
					out("OK native packpath " + (PackCarRenderer.toggle() ? "entity" : "overlay"));
					return;
				} else if ("stats".equals(a[1])) {
					NativeCars.recordStats(Integer.parseInt(a[2]), DevCommands::out);
				} else if (("on".equals(a[1]) || "off".equals(a[1])) && NativeCars.enabled() != "on".equals(a[1])) {
					NativeCars.toggle();
				}
				out("OK native " + NativeCars.describe());
			}
			case "closescreen" -> {
				mc.setScreen(null);
				out("OK closescreen");
			}
			case "fullscreen" -> {
				if (mc.getWindow().isFullscreen() != "on".equals(a.length > 1 ? a[1] : "on")) {
					mc.getWindow().toggleFullScreen();
				}
				out("OK fullscreen " + mc.getWindow().isFullscreen() + " " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
			}
			case "view" -> {
				// first | back | front: the perspective, as F5 cycles it (HostDrive)
				net.minecraft.client.CameraType type = switch (a.length > 1 ? a[1] : "") {
					case "first" -> net.minecraft.client.CameraType.FIRST_PERSON;
					case "front" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
					default -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
				};
				mc.options.setCameraType(type);
				out("OK view " + type);
			}
			case "picker" -> {
				mc.setScreen(new CarSelectScreen(HostDrive.seated(mc)));
				out("OK picker");
			}
			case "picktile" -> {
				boolean hit = mc.screen instanceof CarSelectScreen s && s.clickTile(Integer.parseInt(a[1]));
				out((hit ? "OK" : "ERR") + " picktile " + a[1]);
			}
			case "pick" -> {
				// pick <model> [config|-] [new|replace]: straight to BeamNG, no screen ('+' for a space in
				// a mod's model name, "Porsche+911+992+TwiXeR")
				String key = a[1].replace('+', ' ');
				var model = new dev.bngmc.bridge.link.VehicleCatalog.Model(key, key, "", "", 0, "", "");
				var config = new dev.bngmc.bridge.link.VehicleCatalog.Config(a.length > 2 && !"-".equals(a[2]) ? a[2] : "", "", false, "");
				CarSelectScreen.spawn(mc, model, config, a.length > 3 && "replace".equals(a[3]));
				out("OK pick " + a[1]);
			}
			case "cutouts" -> {
				if (CarCutouts.enabled() != !"off".equals(a[1])) {
					CarCutouts.toggle();
				}
				out("OK cutouts " + CarCutouts.enabled() + " (" + CarCutouts.describe() + ")");
			}
			case "press" -> {
				String want = line.substring(line.indexOf(' ') + 1).trim().toLowerCase(java.util.Locale.ROOT);
				var button = mc.screen == null ? null : mc.screen.children().stream()
					.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractButton b
						&& b.getMessage().getString().toLowerCase(java.util.Locale.ROOT).contains(want))
					.map(c -> (net.minecraft.client.gui.components.AbstractButton) c).findFirst().orElse(null);
				if (button != null) {
					button.onPress();
				}
				out((button != null ? "OK press " : "ERR press: no such button on ") + (button != null ? button.getMessage().getString()
					: mc.screen == null ? "no screen" : mc.screen.getClass().getSimpleName()));
			}
			case "settings" -> {
				if (a.length > 1 && "open".equals(a[1])) {
					mc.setScreen(new SettingsScreen(mc.screen));
				}
				out("OK settings " + dev.bngmc.bridge.BridgeSettings.get().toJson());
			}
			case "set" -> {
				var before = dev.bngmc.bridge.BridgeSettings.get();
				var after = before.with(a[1], com.google.gson.JsonParser.parseString(line.substring(line.indexOf(a[1]) + a[1].length()).trim()));
				dev.bngmc.bridge.BridgeSettings.set(after);
				out((after.equals(before) ? "OK unchanged " : "OK set ") + after.toJson());
			}
			case "quit" -> {
				out("OK quit");
				mc.stop();
			}
			default -> out("ERR unknown command: " + line);
		}
	}

	/**
	 * Alignment check: a 5x3 grid of wool blocks 8 m in front of the player (lateral -4..4 m,
	 * 0..2 m up), and BeamNG draws a 0.5 m sphere at the centre of each block. With both cameras
	 * synced, every sphere must sit on its block in the two screenshots.
	 */
	private static void markers(Minecraft mc) {
		var region = BngWorld.region();
		if (mc.player == null || region == null) {
			out("ERR markers: not in the bridge world");
			return;
		}
		double yaw = Math.toRadians(mc.player.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // horizontal forward, MC space
		double rx = -fz, rz = fx;                          // right-hand side
		int bx0 = (int) Math.floor(mc.player.getX() + fx * 8);
		int bz0 = (int) Math.floor(mc.player.getZ() + fz * 8);
		int by0 = (int) Math.floor(mc.player.getY());
		com.google.gson.JsonArray pts = new com.google.gson.JsonArray();
		String[] colors = {"red_wool", "lime_wool", "blue_wool", "yellow_wool", "magenta_wool"};
		for (int i = -2; i <= 2; i++) {
			for (int h = 0; h <= 2; h += 1) {
				int bx = (int) Math.floor(bx0 + rx * i * 2);
				int bz = (int) Math.floor(bz0 + rz * i * 2);
				int by = by0 + h;
				mc.player.connection.sendCommand(String.format("setblock %d %d %d %s", bx, by, bz, colors[i + 2]));
				var c = dev.bngmc.bridge.coords.CrossoverCoords.minecraftToCanonicalPosition(
					new dev.bngmc.bridge.coords.V3(bx + 0.5, by + 0.5, bz + 0.5), region);
				pts.add(c.x());
				pts.add(c.y());
				pts.add(c.z());
			}
		}
		com.google.gson.JsonObject msg = new com.google.gson.JsonObject();
		msg.add("pts", pts);
		msg.addProperty("r", 0.5);
		msg.addProperty("ttl", 120);
		boolean sent = dev.bngmc.bridge.link.BngLink.get().send("markers", msg);
		out("OK markers: 15 blocks, BeamNG spheres " + (sent ? "sent" : "NOT sent"));
	}

	/** Collision check against the nearest car proxy, as the client sees it (roof standing). */
	private static void probe(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		var p = mc.player;
		var near = mc.level.getEntitiesOfClass(dev.bngmc.bridge.entity.BngVehicleEntity.class, p.getBoundingBox().inflate(32));
		near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(p)));
		out("PROBE player box " + p.getBoundingBox() + " onGround " + p.onGround() + " flying " + p.getAbilities().flying);
		if (near.isEmpty()) {
			out("  no car proxy within 32 blocks (client level)");
			return;
		}
		var car = near.get(0);
		var down = p.getBoundingBox().expandTowards(0, -2, 0);
		out("  car box " + car.getBoundingBox() + " canBeCollidedWith " + car.canBeCollidedWith() + " player.canCollideWith " + p.canCollideWith(car));
		out("  entity collisions below the player: " + mc.level.getEntityCollisions(p, down).size()
			+ "   getEntities in that box: " + mc.level.getEntities(p, down).size());
	}

	/** Terrain voxels near the player: for each column, the Y of every terrain block and its HEIGHT/16. */
	private static void column(Minecraft mc, String[] a) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		int dx0 = a.length > 2 ? Integer.parseInt(a[1]) : 0;
		int dz0 = a.length > 2 ? Integer.parseInt(a[2]) : 0;
		int r = a.length > 3 ? Integer.parseInt(a[3]) : (a.length == 2 ? Integer.parseInt(a[1]) : 1);
		int px = (int) Math.floor(mc.player.getX()) + dx0;
		int pz = (int) Math.floor(mc.player.getZ()) + dz0;
		int py = (int) Math.floor(mc.player.getY());
		out(String.format("COLUMNS around MC (%d, %d), player at %.3f %.3f %.3f", px, pz, mc.player.getX(), mc.player.getY(), mc.player.getZ()));
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				StringBuilder b = new StringBuilder();
				for (int y = py - 6; y <= py + 6; y++) {
					var st = mc.level.getBlockState(new net.minecraft.core.BlockPos(px + dx, y, pz + dz));
					if (st.is(dev.bngmc.bridge.BngBridgeMod.TERRAIN)) {
						b.append(' ').append(y).append(':').append(st.getValue(dev.bngmc.bridge.TerrainBlock.HEIGHT));
					} else if (!st.isAir()) {
						b.append(' ').append(y).append(":").append(st.getBlock().getName().getString());
					}
				}
				out(String.format("  col (%d, %d)%s%s", px + dx, pz + dz, dev.bngmc.bridge.TerrainManager.isSampled(px + dx, pz + dz) ? "" : " UNSAMPLED", b));
			}
		}
	}

	static void out(String s) {
		LOG.info("[devcmd] {}", s);
		try {
			Files.createDirectories(DIR);
			Files.writeString(OUT, s + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOG.warn("[devcmd] cannot write {}: {}", OUT, e.toString());
		}
	}
}
