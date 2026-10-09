package dev.bngmc.bridge.client.mixin;

import dev.bngmc.bridge.client.HostDrive;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Controlify drives the character with a controller; in a BeamNG car the same buttons drive the
 * car (B handbrake, Y reset, bumpers shift, right stick camera: CarControls, HostDrive), so its
 * in-game bindings and look input stand down while seated. Controlify 3.0.1+lts (1.21.1); skipped
 * when Controlify isn't installed.
 */
@Pseudo
@Mixin(targets = "dev.isxander.controlify.ingame.InGameInputHandler", remap = false)
public abstract class ControlifyInGameMixin {
	@Inject(method = "inputTick", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void bngbridge$carHasTheButtons(CallbackInfo ci) {
		if (HostDrive.seated(Minecraft.getInstance())) {
			ci.cancel();
		}
	}

	@Inject(method = "processPlayerLook", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void bngbridge$carHasTheStick(float partialTick, CallbackInfo ci) {
		if (HostDrive.seated(Minecraft.getInstance())) {
			ci.cancel();
		}
	}
}
