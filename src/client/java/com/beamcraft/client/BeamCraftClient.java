package com.beamcraft.client;

import com.beamcraft.BeamCraftMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;

public class BeamCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BeamNgBridge.start();
		HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player == null) {
				return;
			}

			BeamNgBridge.State state = BeamNgBridge.getState();
			String line = state.connected
				? state.vehicleActive
					? String.format("BeamNG LINK | %s | %.0f km/h | X %.1f Y %.1f Z %.1f",
						state.vehicleName, state.speedMetersPerSecond * 3.6, state.x, state.y, state.z)
					: "BeamNG LINK | Bridge ready, load a vehicle in BeamNG"
				: "BeamNG LINK OFFLINE | Start BeamNG and load beamcraftBridge";
			int color = state.connected ? 0xFF70E890 : 0xFFFFB060;
			drawContext.drawString(minecraft.font, line, 8, 8, color, true);
		});
		BeamCraftMod.LOGGER.info("BeamCraft HUD initialized; polling the local BeamNG bridge.");
	}
}
