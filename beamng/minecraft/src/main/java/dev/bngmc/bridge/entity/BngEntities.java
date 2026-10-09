package dev.bngmc.bridge.entity;

import dev.bngmc.bridge.BngBridgeMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class BngEntities {
	private BngEntities() {
	}

	public static final EntityType<BngVehicleEntity> VEHICLE = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		ResourceLocation.fromNamespaceAndPath(BngBridgeMod.MOD_ID, "vehicle"),
		EntityType.Builder.<BngVehicleEntity>of(BngVehicleEntity::new, MobCategory.MISC)
			.sized(2.0F, 1.5F)
			.noSave()
			.noSummon()
			.clientTrackingRange(32)
			.updateInterval(20)   // the client places it from BeamNG's data itself
			.build("vehicle"));

	/** Loading this class registers the entity type; call from the mod initializer. */
	public static void init() {
	}
}
