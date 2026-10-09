package dev.bngmc.bridge.client;

import com.google.gson.JsonObject;
import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.TerrainSync;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Protocol;
import dev.bngmc.bridge.link.VehicleCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * The car picker (key B): BeamNG's own vehicle list with its preview pictures, the same list
 * BeamNG's vehicle selector shows (carselect.lua). Pick a model, then one of its configurations.
 * Sitting in a car, the pick replaces it in place, as BeamNG's selector does; on foot (or with
 * "Add a new car") the car is put down in front of you.
 */
public final class CarSelectScreen extends Screen {
	private static final int TILE_W = 128, PIC_H = 72, TILE_H = PIC_H + 24, GAP = 8, TOP = 52, ASK_AGAIN_MS = 3000;
	private static final double SPAWN_AHEAD_M = 8;
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("bngbridge");

	private static boolean everything;   // props and trailers too; remembered while the game runs

	private final boolean seated;
	private boolean replace;
	private VehicleCatalog.Model opened;   // null: all models; else this model's configurations
	private double scroll;
	private long askedMs;
	private EditBox search;
	private Button filterButton, modeButton, backButton;

	public CarSelectScreen(boolean seated) {
		super(Component.literal("BeamNG cars"));
		this.seated = seated;
		this.replace = seated;
	}

	@Override
	protected void init() {
		String query = search != null ? search.getValue() : "";
		search = new EditBox(font, 16, 24, 180, 18, Component.literal("Search"));
		search.setHint(Component.literal("Search cars").withStyle(ChatFormatting.DARK_GRAY));
		search.setValue(query);
		search.setResponder(s -> scroll = 0);
		addRenderableWidget(search);
		filterButton = addRenderableWidget(Button.builder(filterLabel(), b -> {
			everything = !everything;
			b.setMessage(filterLabel());
			scroll = 0;
		}).bounds(204, 23, 110, 20).build());
		backButton = addRenderableWidget(Button.builder(Component.literal("< All cars"), b -> {
			opened = null;
			scroll = 0;
			showWidgets();
		}).bounds(16, 23, 90, 20).build());
		modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> {
			replace = !replace;
			b.setMessage(modeLabel());
		}).bounds(width - 16 - 140, 23, 140, 20).build());
		showWidgets();
		setInitialFocus(search);
		if (!refreshed) {   // mods added since (scripts/mod_picker.py): ask again, show the old list meanwhile
			refreshed = true;
			VehicleCatalog.get().requestModels(BngLink.get());
			askedMs = System.currentTimeMillis();
		}
		ask();
	}

	private boolean refreshed;

	private void showWidgets() {
		search.visible = opened == null;
		filterButton.visible = opened == null;
		backButton.visible = opened != null;
		modeButton.visible = seated;
	}

	private Component filterLabel() {
		return Component.literal(everything ? "Everything" : "Cars & trucks");
	}

	private Component modeLabel() {
		return Component.literal(replace ? "Replace my car" : "Add a new car");
	}

	/** Asks BeamNG for what this view needs, again every few seconds until it is in (UDP). */
	private void ask() {
		long now = System.currentTimeMillis();
		if (now - askedMs < ASK_AGAIN_MS) {
			return;
		}
		VehicleCatalog cat = VehicleCatalog.get();
		if (opened == null && cat.models() == null) {
			cat.requestModels(BngLink.get());
			askedMs = now;
		} else if (opened != null && cat.configs(opened.key()) == null) {
			cat.requestConfigs(BngLink.get(), opened.key());
			askedMs = now;
		}
	}

	@Override
	public void tick() {
		ask();
	}

	@Override
	public boolean isPauseScreen() {
		return false;   // BeamNG keeps simulating anyway
	}

	// -- layout -------------------------------------------------------------------------------------

	private int columns() {
		return Math.max(1, (width - 32 + GAP) / (TILE_W + GAP));
	}

	private int left() {
		int cols = columns();
		return (width - (cols * (TILE_W + GAP) - GAP)) / 2;
	}

	private int bottom() {
		return height - 24;
	}

	private int tileX(int i) {
		return left() + (i % columns()) * (TILE_W + GAP);
	}

	private int tileY(int i) {
		return TOP + (i / columns()) * (TILE_H + GAP) - (int) scroll;
	}

	private int indexAt(double mx, double my, int count) {
		if (my < TOP || my >= bottom()) {
			return -1;
		}
		for (int i = 0; i < count; i++) {
			int x = tileX(i), y = tileY(i);
			if (mx >= x && mx < x + TILE_W && my >= y && my < y + TILE_H) {
				return i;
			}
		}
		return -1;
	}

	private List<VehicleCatalog.Model> shownModels() {
		List<VehicleCatalog.Model> all = VehicleCatalog.get().models();
		return all == null ? null : VehicleCatalog.filter(all, search.getValue(), everything);
	}

	private List<VehicleCatalog.Config> shownConfigs() {
		return opened == null ? null : VehicleCatalog.get().configs(opened.key());
	}

	// -- drawing ------------------------------------------------------------------------------------

	@Override
	public void render(GuiGraphics g, int mx, int my, float partialTick) {
		super.render(g, mx, my, partialTick);
		g.drawCenteredString(font, opened == null ? "BeamNG cars" : opened.title(), width / 2, 8, 0xFFFFFF);
		String status;
		g.enableScissor(0, TOP - 2, width, bottom());
		if (opened == null) {
			List<VehicleCatalog.Model> list = shownModels();
			status = list == null ? "Asking BeamNG for its cars..." : list.size() + " from BeamNG. Click one to see its versions.";
			for (int i = 0; list != null && i < list.size(); i++) {
				VehicleCatalog.Model m = list.get(i);
				tile(g, i, m.thumb(), m.title(), m.configs() + (m.configs() == 1 ? " version, " : " versions, ") + m.type(), mx, my, list.size());
			}
		} else {
			List<VehicleCatalog.Config> list = shownConfigs();
			status = list == null ? "Asking BeamNG for the versions..."
				: (seated && replace ? "Click one to swap your car for it." : "Click one to put it in front of you.");
			for (int i = 0; list != null && i < list.size(); i++) {
				VehicleCatalog.Config c = list.get(i);
				tile(g, i, c.thumb(), c.name(), c.isDefault() ? "default" : "", mx, my, list.size());
			}
		}
		g.disableScissor();
		g.drawCenteredString(font, status, width / 2, bottom() + 8, 0xA0A0A0);
	}

	private void tile(GuiGraphics g, int i, String thumb, String line1, String line2, int mx, int my, int count) {
		int x = tileX(i), y = tileY(i);
		if (y + TILE_H < TOP || y > bottom()) {
			return;
		}
		boolean hover = indexAt(mx, my, count) == i;
		g.fill(x - 3, y - 3, x + TILE_W + 3, y + TILE_H, hover ? 0x70FFFFFF : 0x40000000);
		ResourceLocation pic = CarThumbnails.get(thumb);
		if (pic != null) {
			g.blit(pic, x, y, TILE_W, PIC_H, 0, 0, CarThumbnails.W, CarThumbnails.H, CarThumbnails.W, CarThumbnails.H);
		} else {
			g.fill(x, y, x + TILE_W, y + PIC_H, 0xFF1E1E1E);
		}
		g.drawString(font, font.plainSubstrByWidth(line1, TILE_W), x, y + PIC_H + 3, 0xFFFFFF);
		g.drawString(font, font.plainSubstrByWidth(line2, TILE_W), x, y + PIC_H + 13, 0x909090);
	}

	// -- input --------------------------------------------------------------------------------------

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		int count = opened == null ? sizeOf(shownModels()) : sizeOf(shownConfigs());
		int rows = (count + columns() - 1) / columns();
		double max = Math.max(0, rows * (TILE_H + GAP) - (bottom() - TOP));
		scroll = Math.max(0, Math.min(max, scroll - scrollY * 40));
		return true;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) {
			return true;
		}
		if (button != 0) {
			return false;
		}
		if (opened == null) {
			List<VehicleCatalog.Model> list = shownModels();
			int i = list == null ? -1 : indexAt(mx, my, list.size());
			if (i >= 0) {
				opened = list.get(i);
				scroll = 0;
				askedMs = 0;
				showWidgets();
				ask();
				return true;
			}
		} else {
			List<VehicleCatalog.Config> list = shownConfigs();
			int i = list == null ? -1 : indexAt(mx, my, list.size());
			if (i >= 0) {
				spawn(minecraft, opened, list.get(i), seated && replace);
				onClose();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean keyPressed(int key, int scancode, int mods) {
		if (opened != null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
			opened = null;
			scroll = 0;
			showWidgets();
			return true;
		}
		return super.keyPressed(key, scancode, mods);
	}

	/** Dev command "picktile N": a left click in the middle of tile N, through the mouse path. */
	boolean clickTile(int i) {
		return mouseClicked(tileX(i) + TILE_W / 2.0, tileY(i) + PIC_H / 2.0, 0);
	}

	private static int sizeOf(List<?> l) {
		return l == null ? 0 : l.size();
	}

	// -- spawning -----------------------------------------------------------------------------------

	/**
	 * Asks BeamNG for the car: in place of the player's car, or on the ground SPAWN_AHEAD_M blocks in
	 * front of the player, side-on so it can be seen whole. In a terrain world the place must be open
	 * ground (TerrainSync.spawnSpot): asked for indoors, the car goes to the nearest open spot outside
	 * and the chat says where. Nothing is sent while BeamNG stands still (a car export or spawn in
	 * progress): it would be lost in BeamNG's full socket.
	 */
	static void spawn(Minecraft mc, VehicleCatalog.Model model, VehicleCatalog.Config config, boolean replace) {
		BngLink link = BngLink.get();
		String title = (model.title() + " " + config.name()).trim();
		if (!link.responsive()) {
			String why = link.stallReason();
			say(mc, link.connected() ? "BeamNG is busy" + (why.isEmpty() ? "" : " " + why) + ": pick the " + title + " again in a few seconds"
				: "BeamNG isn't connected", false);
			return;
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("model", model.key());
		if (!config.key().isEmpty()) {
			msg.addProperty("config", config.key());   // none: the model's default
		}
		msg.addProperty("mode", replace ? "replace" : "new");
		if (replace) {
			send(mc, link, msg, title, "Swapping your car for the " + title, false);
			return;
		}
		CrossoverCoords.Region region = BngWorld.region();
		if (mc.player == null || region == null) {
			return;
		}
		double yaw = Math.toRadians(mc.player.getYRot());
		double lx = -Math.sin(yaw), lz = Math.cos(yaw);   // Minecraft's horizontal look
		double px = mc.player.getX(), pz = mc.player.getZ();
		double wantX = px + lx * SPAWN_AHEAD_M, wantZ = pz + lz * SPAWN_AHEAD_M;
		msg.add("fwd", vec(CrossoverCoords.minecraftToCanonicalDirection(new V3(-lz, 0, lx))));
		var server = mc.getSingleplayerServer();
		if (BngWorld.isTerrainWorld() && server != null) {
			server.execute(() -> {
				TerrainSync.Spot spot = TerrainSync.spawnSpot(dev.bngmc.bridge.BngWorld.activeLevel(server), wantX, wantZ, region);
				mc.execute(() -> {
					if (spot == null) {
						say(mc, "No open ground within " + TerrainSync.SPAWN_SEARCH + " blocks for the " + title + " (indoors, or BeamNG's terrain isn't built yet): "
							+ "step outside and pick it again", false);
						return;
					}
					V3 at = CrossoverCoords.minecraftToCanonicalPosition(new V3(spot.x() + 0.5, 0, spot.z() + 0.5), region);
					msg.add("pos", vec(new V3(at.x(), at.y(), spot.canonicalZ() + SPAWN_CLEARANCE_M)));
					boolean moved = spot.movedBlocks() > MOVED_BLOCKS;
					double dx = spot.x() + 0.5 - px, dz = spot.z() + 0.5 - pz;
					send(mc, link, msg, title, moved ? "No room there: the " + title + " is parked in the open " + Math.round(Math.hypot(dx, dz))
						+ " blocks " + compass(dx, dz) + " of you (" + spot.x() + ", " + spot.z() + ")" : "Bringing the " + title, moved);
				});
			});
			return;
		}
		msg.add("pos", vec(CrossoverCoords.minecraftToCanonicalPosition(new V3(wantX, mc.player.getY(), wantZ), region)));
		send(mc, link, msg, title, "Bringing the " + title, false);
	}

	/** Metres above BeamNG's terrain a new car is asked for: spawn.safeTeleport's rays then start above the ground. */
	private static final double SPAWN_CLEARANCE_M = 0.3;
	/** A spot this many blocks from the one asked for is worth telling the player about. */
	private static final double MOVED_BLOCKS = 3;

	private static void send(Minecraft mc, BngLink link, JsonObject msg, String title, String text, boolean chat) {
		if (!link.responsive()) {   // went quiet while the spot was being found
			say(mc, "BeamNG is busy: pick the " + title + " again in a few seconds", false);
			return;
		}
		// BeamNG stands still while it loads the car, then while it exports it: keep the link through that
		link.expectStall("loading the " + title, Protocol.SPAWN_STALL_MS);
		if (link.send(Protocol.VEHICLE_SPAWN, msg)) {
			say(mc, text, !chat);
		} else {
			say(mc, "BeamNG isn't connected", true);
		}
	}

	private static void say(Minecraft mc, String text, boolean actionBar) {
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal("[BeamNG] " + text), actionBar);
		}
	}

	/** north, north-east, ... for a Minecraft offset (x east, z south). */
	static String compass(double dx, double dz) {
		String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
		double a = Math.toDegrees(Math.atan2(-dx, dz));   // 0 = south (+z), 90 = west (-x), as Minecraft's yaw
		return names[(int) Math.floorMod(Math.round(a / 45.0), 8)];
	}

	private static com.google.gson.JsonArray vec(V3 v) {
		com.google.gson.JsonArray a = new com.google.gson.JsonArray();
		a.add(v.x());
		a.add(v.y());
		a.add(v.z());
		return a;
	}

	/** BeamNG's answers to a pick, shown in the chat line above the hotbar. */
	static void install(BngLink link) {
		link.onMessage(Protocol.VEHICLE_SPAWNED, env -> {
			JsonObject b = env.body();
			long id = b.has("id") ? b.get("id").getAsLong() : -1;
			LOG.info("BeamNG {} car {} ({} {})", b.has("mode") && "replace".equals(b.get("mode").getAsString()) ? "replaced the player's" : "spawned",
				id, b.has("model") ? b.get("model").getAsString() : "?", b.has("config") ? b.get("config").getAsString() : "");
			if (b.has("mode") && "replace".equals(b.get("mode").getAsString())) {
				NativeCars.invalidate(id);   // same car id, new model: draw it again once re-exported
			}
		});
		// a car inside the ground or fallen through the floor, put back on top by BeamNG (terrain.lua T.rescue)
		link.onMessage(Protocol.VEHICLE_RESCUED, env -> {
			JsonObject b = env.body();
			String model = b.has("model") ? b.get("model").getAsString() : "car";
			String why = b.has("why") ? b.get("why").getAsString() : "was lost";
			LOG.info("BeamNG rescued car {} ({}): {} at z {}", b.has("id") ? b.get("id").getAsLong() : -1, model, why,
				b.has("from") ? b.get("from").getAsDouble() : Double.NaN);
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> say(mc, "The " + model + " " + why + ": BeamNG put it back on the ground", false));
		});
		link.onMessage(Protocol.ERROR, env -> {
			JsonObject b = env.body();
			if (b.has("code") && "spawn_failed".equals(b.get("code").getAsString())) {
				String why = b.has("msg") ? b.get("msg").getAsString() : "unknown reason";
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> {
					if (mc.player != null) {
						mc.player.displayClientMessage(Component.literal("[BeamNG] Couldn't spawn that: " + why), false);
					}
				});
			}
		});
	}
}
