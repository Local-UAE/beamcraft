package dev.bngmc.bridge.client.mixin;

import dev.bngmc.bridge.client.SkinOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The local player wears the skin SkinOverride fetched (the dev client plays offline). */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void bngbridge$localSkin(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin skin = SkinOverride.skin();
		if (skin != null && (Object) this == Minecraft.getInstance().player) {
			cir.setReturnValue(skin);
		}
	}
}
