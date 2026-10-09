package dev.bngmc.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bngmc.bridge.BlastBridge;
import dev.bngmc.bridge.BngBridgeMod;
import dev.bngmc.bridge.TerrainBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Explosions in the bridge world.
 * <ul>
 *   <li>Every server-side explosion is reported to BeamNG, which pushes nearby cars with it
 *   ({@link BlastBridge}).</li>
 *   <li>Adapted from upstream (MIT): a {@link TerrainBlock} voxel holds ground only up to its
 *   HEIGHT, but explosion rays count any block as filling its whole voxel, so TNT resting on
 *   sloped ground sat inside one and fizzled. Rays now pass through the empty part of a terrain
 *   voxel, and terrain itself never breaks.</li>
 * </ul>
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
	@Shadow
	@Final
	private Level level;

	/** Height of the current ray point; {@code explode()} walks one ray at a time on one thread. */
	@Unique
	private double bngbridge$rayY;

	@Inject(method = "explode", at = @At("HEAD"))
	private void bngbridge$report(CallbackInfo ci) {
		if (!this.level.isClientSide) {
			Explosion self = (Explosion) (Object) this;
			BlastBridge.onExplosion(this.level, self.center(), self.radius());
		}
	}

	@WrapOperation(method = "explode", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"))
	private BlockPos bngbridge$trackRay(double x, double y, double z, Operation<BlockPos> original) {
		this.bngbridge$rayY = y;
		return original.call(x, y, z);
	}

	@WrapOperation(method = "explode", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;getBlockExplosionResistance(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Ljava/util/Optional;"))
	private Optional<Float> bngbridge$terrainSurface(ExplosionDamageCalculator calculator, Explosion explosion, BlockGetter level,
		BlockPos pos, BlockState state, FluidState fluid, Operation<Optional<Float>> original) {
		if (state.is(BngBridgeMod.TERRAIN) && this.bngbridge$rayY - pos.getY() >= state.getValue(TerrainBlock.HEIGHT) / 16.0) {
			return Optional.empty();   // above BeamNG's ground in this voxel: air
		}
		return original.call(calculator, explosion, level, pos, state, fluid);
	}

	@WrapOperation(method = "explode", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;shouldBlockExplode(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;F)Z"))
	private boolean bngbridge$terrainStays(ExplosionDamageCalculator calculator, Explosion explosion, BlockGetter level,
		BlockPos pos, BlockState state, float power, Operation<Boolean> original) {
		return !state.is(BngBridgeMod.TERRAIN) && original.call(calculator, explosion, level, pos, state, power);
	}
}
