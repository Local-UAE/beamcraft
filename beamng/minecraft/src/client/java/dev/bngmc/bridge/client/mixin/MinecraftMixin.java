package dev.bngmc.bridge.client.mixin;

import dev.bngmc.bridge.client.Overlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** Once per frame, before anything is drawn: overlay on/off and window placement. */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void bngbridge$frame(boolean tick, CallbackInfo ci) {
		Overlay.onFrame((Minecraft) (Object) this);
	}
}
