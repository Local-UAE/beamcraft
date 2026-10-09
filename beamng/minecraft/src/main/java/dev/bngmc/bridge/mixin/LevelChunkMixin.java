package dev.bngmc.bridge.mixin;

import dev.bngmc.bridge.BlockSync;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every block change in a loaded chunk (placing, breaking, explosions) -> BlockSync. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow
	public abstract Level getLevel();

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void bngbridge$blockChanged(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
		BlockState old = cir.getReturnValue();   // null when nothing changed
		if (old != null) {
			BlockSync.onChange(getLevel(), pos, old, state);
		}
	}
}
