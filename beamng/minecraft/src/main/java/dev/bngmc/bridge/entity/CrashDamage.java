package dev.bngmc.bridge.entity;

import dev.bngmc.bridge.BngBridgeMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/** Damage for a player riding in a BeamNG car that crashes. Server thread. */
public final class CrashDamage {
	private CrashDamage() {
	}

	public static final ResourceKey<DamageType> CRASH =
		ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(BngBridgeMod.MOD_ID, "crash"));
	/** m/s of speed lost between two vehicle updates (50 ms) that a rider shrugs off. */
	public static final double SAFE_DELTA_V = 8.0;
	/** Minecraft health per m/s above that: losing 25 m/s at once (17 over) costs 25.5, i.e. death. */
	public static final double DAMAGE_PER_MS = 1.5;

	public static float damageFor(double deltaV) {
		return deltaV <= SAFE_DELTA_V ? 0f : (float) ((deltaV - SAFE_DELTA_V) * DAMAGE_PER_MS);
	}

	public static void apply(ServerPlayer player, float amount) {
		DamageSource src = new DamageSource(player.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(CRASH));
		player.hurt(src, amount);
	}
}
