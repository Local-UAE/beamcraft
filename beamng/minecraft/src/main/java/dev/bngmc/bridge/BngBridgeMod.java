package dev.bngmc.bridge;

import dev.bngmc.bridge.entity.BngEntities;
import dev.bngmc.bridge.entity.VehicleBridge;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.VehicleMeshes;
import dev.bngmc.bridge.link.Vehicles;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

public class BngBridgeMod implements ModInitializer {
	public static final String MOD_ID = "bngbridge";

	/** Hit a BeamNG car with it and the car is gone (deleted in BeamNG). */
	public static final net.minecraft.world.item.Item CAR_REMOVER = Registry.register(
		BuiltInRegistries.ITEM,
		ResourceLocation.fromNamespaceAndPath(MOD_ID, "car_remover"),
		new net.minecraft.world.item.Item(new net.minecraft.world.item.Item.Properties().stacksTo(1)
			.component(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true))
	);

	/** Invisible stand-in for BeamNG's collision (same properties as upstream's terrain block). */
	public static final TerrainBlock TERRAIN = Registry.register(
		BuiltInRegistries.BLOCK,
		ResourceLocation.fromNamespaceAndPath(MOD_ID, "terrain"),
		new TerrainBlock(BlockBehaviour.Properties.of()
			.strength(-1.0F, 3600000.0F)
			.noLootTable()
			.noOcclusion()
			.sound(SoundType.STONE)
			.pushReaction(PushReaction.BLOCK)
			.isValidSpawn((state, level, pos, type) -> false)
			.isRedstoneConductor((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isViewBlocking((state, level, pos) -> false))
	);

	@Override
	public void onInitialize() {
		BridgeSettings.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("bngbridge.json"));
		// The link is process-wide; the client and the integrated server share it.
		BngLink.get().start("minecraft");
		Vehicles.install(BngLink.get());
		VehicleMeshes.install(BngLink.get());
		dev.bngmc.bridge.link.VehicleCatalog.install(BngLink.get());
		TerrainSync.install(BngLink.get());
		BngEntities.init();
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES)
			.register(entries -> entries.accept(CAR_REMOVER));
		ServerLifecycleEvents.SERVER_STARTED.register(BngWorld::onServerStarted);
		ServerLifecycleEvents.SERVER_STARTED.register(BlockSync::onServerStarted);   // after BngWorld: needs the world kind
		ServerLifecycleEvents.SERVER_STARTED.register(TerrainSync::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(BlockSync::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPING.register(GroundSync::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPING.register(TerrainSync::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TerrainManager.reset();
			VehicleBridge.reset();
			BngWorld.reset();
		});
		ServerTickEvents.END_SERVER_TICK.register(BngWorld::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(TerrainManager::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(VehicleBridge::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(BlockSync::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(GroundSync::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(TerrainSync::onServerTick);
		// joining puts the player beside the car; in a real-terrain world the car comes to the player instead (TerrainSync)
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!BngWorld.isTerrainWorld()) {
				BngWorld.requestRecall();
			}
		});

		// A driver sits inside the car's box, and BeamNG drives it through Minecraft's blocks wherever
		// its ground differs from them (smooth slopes cut into block corners, a heightfield has no
		// overhangs): no reason to suffocate. Other harm to a driver is logged, to see what it was.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (!(entity.getVehicle() instanceof dev.bngmc.bridge.entity.BngVehicleEntity)) {
				return true;
			}
			if (source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)) {
				return false;
			}
			org.slf4j.LoggerFactory.getLogger("bngbridge").info("{} took {} damage ({}) in a BeamNG car", entity.getName().getString(),
				amount, source.getMsgId());
			return true;
		});

		// BeamNG's ground can't be broken, even in creative mode.
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !state.is(TERRAIN));
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
			level.getBlockState(pos).is(TERRAIN) ? InteractionResult.FAIL : InteractionResult.PASS);
	}
}
