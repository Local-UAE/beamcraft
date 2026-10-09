package dev.bngmc.bridge.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.bngmc.bridge.client.Overlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adapted from upstream (MIT). The final blit to the window normally leaves the window's alpha
 * untouched. With a transparent window that alpha decides what shows through, so: clear it first
 * (to 0 in overlay mode, 1 otherwise) and, in overlay mode, copy Minecraft's alpha along with
 * colour. Only the main render target's blit goes to the window; the same method also composites
 * other targets (entity outlines...) and those are left alone.
 */
@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
	private static final int GL_COLOR_BUFFER_BIT = 0x4000;

	private boolean bngbridge$isWindowBlit() {
		return (Object) this == Minecraft.getInstance().getMainRenderTarget();
	}

	@Inject(method = "_blitToScreen", at = @At("HEAD"))
	private void bngbridge$clearWindow(int width, int height, boolean disableBlend, CallbackInfo ci) {
		if (!bngbridge$isWindowBlit() || !Overlay.transparentWindow()) {
			return;
		}
		GlStateManager._colorMask(true, true, true, true);
		GlStateManager._clearColor(0.0F, 0.0F, 0.0F, Overlay.active() ? 0.0F : 1.0F);
		GlStateManager._clear(GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
	}

	@Redirect(method = "_blitToScreen", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/platform/GlStateManager;_colorMask(ZZZZ)V", ordinal = 0, remap = false))
	private void bngbridge$writeAlpha(boolean r, boolean g, boolean b, boolean a) {
		GlStateManager._colorMask(r, g, b, a || (Overlay.active() && bngbridge$isWindowBlit()));
	}
}
