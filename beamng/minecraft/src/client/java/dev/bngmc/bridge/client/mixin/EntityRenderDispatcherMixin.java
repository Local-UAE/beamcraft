package dev.bngmc.bridge.client.mixin;

import dev.bngmc.bridge.client.HostDrive;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Steve sitting in a BeamNG car isn't drawn: the car is BeamNG's picture and he'd poke through its roof. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void bngbridge$hideDriver(E entity, Frustum frustum, double camX, double camY, double camZ,
														 CallbackInfoReturnable<Boolean> cir) {
		if (HostDrive.hidden(entity)) {
			cir.setReturnValue(false);
		}
	}
}
