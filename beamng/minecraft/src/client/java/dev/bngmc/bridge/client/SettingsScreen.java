package dev.bngmc.bridge.client;

import com.mojang.serialization.Codec;
import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.BridgeSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The crossover's settings (O, or "BeamNG settings" in the pause menu): Minecraft's own options
 * list, a row per BridgeSettings value, each with a tooltip saying what it does. The changes take
 * effect together when the screen closes (Done or Esc), so the terrain is built again once even
 * when several of its settings changed.
 */
public final class SettingsScreen extends OptionsSubScreen {
	private OptionInstance<BridgeSettings.Ground> ground;
	private OptionInstance<Integer> slope, dent, shove, chaseDistance, chaseHeight;
	private OptionInstance<Boolean> sea, openBuildings, crashHurts, carsHurt, chaseFollows, dashboard, statusHud;

	public SettingsScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("bngbridge.settings"));
	}

	@Override
	protected void addOptions() {
		if (ground == null) {   // once: a resize runs this again and must keep what was changed
			makeOptions(BridgeSettings.get());
		}
		header("bngbridge.settings.world");
		list.addBig(ground);
		list.addSmall(slope, sea);
		list.addSmall(openBuildings);
		header("bngbridge.settings.car");
		list.addSmall(dent, shove);
		list.addSmall(crashHurts, carsHurt);
		header("bngbridge.settings.camera");
		list.addSmall(chaseDistance, chaseHeight);
		list.addSmall(chaseFollows, dashboard);
		list.addSmall(statusHud);
	}

	private void makeOptions(BridgeSettings s) {
		ground = new OptionInstance<>("bngbridge.settings.ground", tip("ground"),
			(caption, g) -> Options.genericValueLabel(caption, Component.translatable("bngbridge.settings.ground." + g.key)),
			new OptionInstance.Enum<>(List.of(BridgeSettings.Ground.values()),
				Codec.STRING.xmap(k -> BridgeSettings.Ground.of(k, BridgeSettings.Ground.SMOOTH), g -> g.key)),
			s.ground(), g -> { });
		slope = new OptionInstance<>("bngbridge.settings.slope", tip("slope"),
			(caption, r) -> Options.genericValueLabel(caption, Component.translatable("bngbridge.settings.blocks", r * 2 + 1)),
			new OptionInstance.IntRange(BridgeSettings.MIN_SLOPE_BLOCKS, BridgeSettings.MAX_SLOPE_BLOCKS), s.slopeBlocks(), r -> { });
		sea = OptionInstance.createBoolean("bngbridge.settings.sea", tip("sea"), s.seaWater());
		openBuildings = OptionInstance.createBoolean("bngbridge.settings.open_buildings", tip("open_buildings"), s.openBuildings());
		dent = percent("dent", s.dentPercent());
		shove = percent("shove", s.shovePercent());
		crashHurts = OptionInstance.createBoolean("bngbridge.settings.crash_hurts", tip("crash_hurts"), s.crashHurts());
		carsHurt = OptionInstance.createBoolean("bngbridge.settings.cars_hurt", tip("cars_hurt"), s.carsHurt());
		chaseDistance = metres("chase_distance", BridgeSettings.MIN_CHASE_DISTANCE, BridgeSettings.MAX_CHASE_DISTANCE, s.chaseDistance());
		chaseHeight = metres("chase_height", BridgeSettings.MIN_CHASE_HEIGHT, BridgeSettings.MAX_CHASE_HEIGHT, s.chaseHeight());
		chaseFollows = OptionInstance.createBoolean("bngbridge.settings.chase_follows", tip("chase_follows"), s.chaseFollows());
		dashboard = OptionInstance.createBoolean("bngbridge.settings.dashboard", tip("dashboard"), s.dashboard());
		statusHud = OptionInstance.createBoolean("bngbridge.settings.status", tip("status"), s.statusHud());
	}

	private static <T> OptionInstance.TooltipSupplier<T> tip(String name) {
		return OptionInstance.cachedConstantTooltip(Component.translatable("bngbridge.settings." + name + ".tip"));
	}

	/** 0..300 % in steps of 10 (the slider holds tens). */
	private static OptionInstance<Integer> percent(String name, int value) {
		return new OptionInstance<>("bngbridge.settings." + name, tip(name),
			(caption, v) -> Options.genericValueLabel(caption, Component.literal(v * 10 + "%")),
			new OptionInstance.IntRange(0, BridgeSettings.MAX_PERCENT / 10), Math.round(value / 10F), v -> { });
	}

	/** Metres in steps of 0.1 (the slider holds tenths). */
	private static OptionInstance<Integer> metres(String name, double min, double max, double value) {
		return new OptionInstance<>("bngbridge.settings." + name, tip(name),
			(caption, v) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f m", v / 10.0))),
			new OptionInstance.IntRange((int) Math.round(min * 10), (int) Math.round(max * 10)), (int) Math.round(value * 10), v -> { });
	}

	private void header(String key) {
		list.addSmall(new StringWidget(310, 20, Component.translatable(key).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), font).alignLeft(),
			null);
	}

	@Override
	public void removed() {
		super.removed();
		if (ground == null) {
			return;   // closed before it was ever shown
		}
		BridgeSettings before = BridgeSettings.get();
		BridgeSettings after = new BridgeSettings(ground.get(), slope.get(), sea.get(), openBuildings.get(), dent.get() * 10, shove.get() * 10,
			crashHurts.get(), carsHurt.get(), chaseDistance.get() / 10.0, chaseHeight.get() / 10.0, chaseFollows.get(), dashboard.get(),
			statusHud.get());
		BridgeSettings.set(after);
		boolean groundChanged = before.ground() != after.ground() || before.slopeRadius() != after.slopeRadius()
			|| before.seaWater() != after.seaWater() || before.openBuildings() != after.openBuildings();
		if (groundChanged && BngWorld.isTerrainWorld() && minecraft != null && minecraft.player != null) {
			minecraft.player.displayClientMessage(Component.translatable("bngbridge.settings.rebuilding",
				Component.translatable("bngbridge.settings.ground." + after.ground().key)), false);
		}
	}
}
