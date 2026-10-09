package dev.bngmc.bridge.client.mixin;

import dev.bngmc.bridge.client.HostDrive;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Seated in a BeamNG car, the player keeps Minecraft's keyboard input instead of Controlify's
 * controller movement: Controlify's sneak (B) would otherwise get the driver out whenever the
 * handbrake is pulled. Controlify 3.0.1+lts (1.21.1); skipped when it isn't installed.
 */
@Pseudo
@Mixin(targets = "dev.isxander.controlify.ingame.ControllerPlayerMovement", remap = false)
public abstract class ControlifyMovementMixin {
	@Inject(method = "shouldBeControllerInput", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private static void bngbridge$keyboardInCar(CallbackInfoReturnable<Boolean> cir) {
		if (HostDrive.seated(Minecraft.getInstance())) {
			cir.setReturnValue(false);
		}
	}
}
