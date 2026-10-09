package dev.bngmc.bridge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.bngmc.bridge.entity.BngVehicleEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

/**
 * BeamNG draws the car itself, so the proxy is invisible. While the status display is on (F9),
 * its box is outlined, which is how the proxy's alignment with BeamNG's car is checked.
 */
public class VehicleRenderer extends EntityRenderer<BngVehicleEntity> {
	private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");

	public VehicleRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	@Override
	public void render(BngVehicleEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
		// A car Minecraft draws itself gets Minecraft's ground shadow, the size of its box.
		this.shadowRadius = NativeCars.active() && NativeCars.ready(entity.bngId()) ? entity.getBbWidth() * 0.45F : 0.0F;
		if (StatusHud.visible()) {
			// The box is where tick() put it; draw it relative to the interpolated render position.
			double rx = net.minecraft.util.Mth.lerp(partialTick, entity.xo, entity.getX());
			double ry = net.minecraft.util.Mth.lerp(partialTick, entity.yo, entity.getY());
			double rz = net.minecraft.util.Mth.lerp(partialTick, entity.zo, entity.getZ());
			AABB box = entity.getBoundingBox().move(-entity.getX(), -entity.getY(), -entity.getZ())
				.move(entity.getX() - rx, entity.getY() - ry, entity.getZ() - rz);
			LevelRenderer.renderLineBox(poseStack, buffers.getBuffer(RenderType.lines()), box, 0.2F, 1.0F, 0.4F, 1.0F);
		}
		if (ShaderCompat.packInUse() && NativeCars.active()) {
			// under a shader pack the car is drawn here, as an entity, so the pack lights it and it
			// is in the pack's shadow map (PackCarRenderer); this runs in both of the pack's passes
			// where LevelRenderer.renderEntity put the pose
			double ex = net.minecraft.util.Mth.lerp(partialTick, entity.xOld, entity.getX());
			double ey = net.minecraft.util.Mth.lerp(partialTick, entity.yOld, entity.getY());
			double ez = net.minecraft.util.Mth.lerp(partialTick, entity.zOld, entity.getZ());
			PackCarRenderer.render(entity.bngId(), poseStack.last().pose(), ex, ey, ez, light);
		}
		super.render(entity, yaw, partialTick, poseStack, buffers, light);
	}

	/**
	 * Under a shader pack the car is drawn from render() (PackCarRenderer), so it must not drop out
	 * at the entity's render distance (about 160 blocks for a car's box): only off screen, with
	 * room for a mesh that reaches past the box.
	 */
	@Override
	public boolean shouldRender(BngVehicleEntity entity, Frustum frustum, double camX, double camY, double camZ) {
		if (ShaderCompat.packInUse() && NativeCars.active() && NativeCars.ready(entity.bngId())) {
			return frustum.isVisible(entity.getBoundingBoxForCulling().inflate(4.0));
		}
		return super.shouldRender(entity, frustum, camX, camY, camZ);
	}

	/**
	 * Vanilla shows a custom name whenever the crosshair is on the entity, and a driver's
	 * crosshair is inside the car's box: no label over an occupied car.
	 */
	@Override
	protected boolean shouldShowName(BngVehicleEntity entity) {
		return !entity.isVehicle() && super.shouldShowName(entity);
	}

	@Override
	public ResourceLocation getTextureLocation(BngVehicleEntity entity) {
		return TEXTURE;
	}
}
