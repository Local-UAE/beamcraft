package dev.bngmc.bridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import dev.bngmc.bridge.entity.BngEntities;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class BngBridgeClient implements ClientModInitializer {
	private static KeyMapping cameraModeKey;
	private static KeyMapping statusKey;
	private static KeyMapping switchKey;
	private static KeyMapping overlayKey;
	private static KeyMapping carSelectKey;
	private static KeyMapping settingsKey;
	private static KeyMapping controlsKey;

	/** For the dashboard's folded controls line. */
	static String controlsKeyName() {
		return controlsKey != null ? controlsKey.getTranslatedKeyMessage().getString() : "J";
	}

	@Override
	public void onInitializeClient() {
		cameraModeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bngbridge.camera_mode",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.bngbridge"));
		statusKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bngbridge.status",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.bngbridge"));

		switchKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bngbridge.switch",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F4, "category.bngbridge"));
		overlayKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bngbridge.overlay",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "category.bngbridge"));
		// BeamNG opens its vehicle selector with Ctrl+E; E is Minecraft's inventory, so B here
		carSelectKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bngbridge.car_select",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.bngbridge.car"));

		settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bngbridge.settings",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.bngbridge"));
		controlsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bngbridge.controls",
			InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.bngbridge.car"));
		// and a button in the pause menu, top left, for the controller
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
				Screens.getButtons(screen).add(Button.builder(Component.translatable("bngbridge.settings.button"),
					b -> mc.setScreen(new SettingsScreen(screen))).bounds(8, 8, 120, 20).build());
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			placeWindowOnce(mc);
			while (cameraModeKey.consumeClick()) {
				toast(mc, "Camera: " + CameraSync.cycleMode());
			}
			while (switchKey.consumeClick()) {
				ControlSwitch.toBeamng(mc);
			}
			while (carSelectKey.consumeClick()) {
				if (!dev.bngmc.bridge.BngWorld.isLinkedWorld()) {
					toast(mc, "Car selection works in a world linked to BeamNG");
				} else if (!dev.bngmc.bridge.link.BngLink.get().connected()) {
					toast(mc, "BeamNG isn't connected");
				} else {
					mc.setScreen(new CarSelectScreen(HostDrive.seated(mc)));
				}
			}
			while (controlsKey.consumeClick()) {
				if (HostDrive.seated(mc)) {
					DriveHud.toggleHelp();   // in the car: the controls list
				} else {
					toast(mc, CarTriggers.toggleShown() ? "Door handles shown: look at one and right-click" : "Door handles hidden");
				}
			}
			while (settingsKey.consumeClick()) {
				if (mc.screen == null) {
					mc.setScreen(new SettingsScreen(null));
				}
			}
			while (overlayKey.consumeClick()) {
				toast(mc, "Overlay window " + (Overlay.toggle() ? "on" : "off"));
			}
			ControlSwitch.tick(mc);
			while (statusKey.consumeClick()) {
				toast(mc, "Crossover status " + (StatusHud.toggle() ? "on" : "off"));
			}
			TerrainHold.tick(mc);
			CarRide.tick(mc);
			CarCutouts.tick(mc);
			NativeCars.tick();
			CarControls.tick(mc);
			WorldBootstrap.tick(mc);
			DevCommands.tick(mc);
		});
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> StatusHud.render(graphics));
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> DriveHud.render(graphics));
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> CarTriggers.renderHud(graphics));
		ClientTickEvents.START_CLIENT_TICK.register(CarTriggers::startTick);
		WorldRenderEvents.AFTER_ENTITIES.register(CarTriggers::render);
		WorldRenderEvents.START.register(ctx -> CarTriggers.frame(Minecraft.getInstance()));
		CarTriggers.install(dev.bngmc.bridge.link.BngLink.get());
		SkinOverride.start();
		CarControls.register();
		WorldRenderEvents.START.register(ctx -> HostDrive.onFrame(Minecraft.getInstance()));
		WorldRenderEvents.START.register(ctx -> CarControls.frame(Minecraft.getInstance()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CarControls.releaseAll());
		WorldRenderEvents.LAST.register(CarCutouts::render);
		WorldRenderEvents.AFTER_ENTITIES.register(NativeCars::render);
		NativeCars.install(dev.bngmc.bridge.link.BngLink.get());
		CarSelectScreen.install(dev.bngmc.bridge.link.BngLink.get());
		EntityRendererRegistry.register(BngEntities.VEHICLE, VehicleRenderer::new);
		dev.bngmc.bridge.entity.BngVehicleEntity.enterHook = id -> {
			if (dev.bngmc.bridge.BngWorld.isHostWorld()) {
				HostDrive.enter(Minecraft.getInstance(), id);
			} else {
				ControlSwitch.enterVehicle(Minecraft.getInstance(), id);
			}
		};
	}

	private static boolean placed;

	/** -Dbngbridge.window=x,y,w,h: put the window there once (tests run both games side by side). */
	private static void placeWindowOnce(Minecraft mc) {
		if (placed) {
			return;
		}
		placed = true;
		String spec = System.getProperty("bngbridge.window", "");
		String[] p = spec.split(",");
		if (p.length != 4) {
			return;
		}
		try {
			long h = mc.getWindow().getWindow();
			GLFW.glfwSetWindowPos(h, Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
			GLFW.glfwSetWindowSize(h, Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
		} catch (NumberFormatException e) {
			org.slf4j.LoggerFactory.getLogger("bngbridge").warn("bad -Dbngbridge.window={}", spec);
		}
	}

	private static void toast(Minecraft mc, String msg) {
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal("[BeamNG] " + msg), true);
		}
	}
}
