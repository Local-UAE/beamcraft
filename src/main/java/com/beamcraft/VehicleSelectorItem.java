package com.beamcraft;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class VehicleSelectorItem extends Item {
	public VehicleSelectorItem(Properties settings) {
		super(settings);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level world, Player user, InteractionHand hand) {
		ItemStack stack = user.getItemInHand(hand);
		if (world.isClientSide) {
			return InteractionResultHolder.success(stack);
		}

		user.sendSystemMessage(Component.literal(
			"BeamCraft telemetry is shown in the HUD. Vehicle spawning and BeamNG map loading are not implemented yet."));
		return InteractionResultHolder.success(stack);
	}
}
