package com.beamcraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BeamCraftMod implements ModInitializer {
	public static final String MOD_ID = "beamcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final Item VEHICLE_SELECTOR = new VehicleSelectorItem(new Item.Properties().stacksTo(1));

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.ITEM, id("vehicle_selector"), VEHICLE_SELECTOR);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
				Commands.literal("beamcraft")
					.then(Commands.literal("list").executes(context -> {
						context.getSource().sendSystemMessage(Component.literal(
							"BeamNG vehicle catalog is not connected in this prototype; no BeamNG vehicles are loaded."));
						return 1;
					}))
					.then(Commands.literal("maps").executes(context -> {
						context.getSource().sendSystemMessage(Component.literal(
							"BeamNG saved-map catalog is not connected in this prototype; no BeamNG maps are loaded."));
						return 1;
					}))
					.then(Commands.literal("status").executes(context -> {
						context.getSource().sendSystemMessage(Component.literal(
							"BeamCraft prototype loaded in Minecraft. BeamNG connection: not implemented. Solo mode; no vehicle or map integration yet."));
						return 1;
					}))
					.then(Commands.literal("kit").executes(context -> {
						if (context.getSource().getPlayer() != null) {
							Player player = context.getSource().getPlayer();
							player.getInventory().add(new ItemStack(VEHICLE_SELECTOR));
							context.getSource().sendSystemMessage(Component.literal("BeamCraft vehicle selector added to your inventory."));
						}
						return 1;
					}))
			);
		});

		LOGGER.info("BeamCraft prototype initialized for Minecraft Java Edition; BeamNG integration is not yet connected.");
	}

	public static ResourceLocation id(String path) {
		return new ResourceLocation(MOD_ID, path);
	}
}
