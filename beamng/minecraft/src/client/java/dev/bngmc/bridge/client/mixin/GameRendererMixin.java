package dev.bngmc.bridge.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.bngmc.bridge.client.CameraSync;
import dev.bngmc.bridge.client.Overlay;
import net.minecraft.client.Camera;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Both games must project identically, so while the cameras are synced Minecraft renders with
 * exactly the FOV it sends to BeamNG (no sprint/potion FOV changes) and without view bobbing or
 * hurt tilt, which would move Minecraft's world against BeamNG's.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** With a shader pack on, the BeamNG cars go on the finished frame (NativeCars.renderAfterShaders). */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
	private void bngbridge$carsAfterShaders(net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
		dev.bngmc.bridge.client.NativeCars.renderAfterShaders();
	}

	@Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
	private void bngbridge$fov(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
		if (useFovSetting && CameraSync.locked()) {
			cir.setReturnValue((double) CameraSync.fov());
		}
	}

	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void bngbridge$noBob(PoseStack poseStack, float partialTick, CallbackInfo ci) {
		if (CameraSync.locked()) {
			ci.cancel();
		}
	}

	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void bngbridge$noHurtTilt(PoseStack poseStack, float partialTick, CallbackInfo ci) {
		if (CameraSync.locked()) {
			ci.cancel();
		}
	}

	/** Clicking BeamNG's window behind the overlay must not pause Minecraft. */
	@Redirect(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;pauseOnLostFocus:Z"))
	private boolean bngbridge$pauseOnLostFocus(Options options) {
		return options.pauseOnLostFocus && !Overlay.active();
	}
}
