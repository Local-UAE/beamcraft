package dev.bngmc.bridge.entity;

import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.coords.CrossoverCoords;
import dev.bngmc.bridge.coords.V3;
import dev.bngmc.bridge.link.BngLink;
import dev.bngmc.bridge.link.Vehicles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft stand-in for one BeamNG vehicle: a solid, invisible box the size of the car's
 * bounding box (players bump into it and can stand on it, the crosshair can target it).
 *
 * <p>Both the server and the client copy place it straight from the newest BeamNG vehicle data
 * every tick, extrapolated with the car's velocity, instead of relying on Minecraft's entity
 * sync: that sync interpolates over several ticks, which leaves a fast car metres behind where
 * BeamNG draws it. Only the BeamNG id is synced. Box sizes follow upstream's proxy entity
 * (MHW bridge, MIT): an arbitrary axis-aligned box, not Minecraft's square footprint.
 */
public class BngVehicleEntity extends Entity {
	private static final EntityDataAccessor<Long> DATA_BNG_ID = SynchedEntityData.defineId(BngVehicleEntity.class, EntityDataSerializers.LONG);

	private double hx = 1.0, hy = 0.75, hz = 1.0;
	private String model = "";

	/**
	 * Set by the client mod: what happens when the local player gets into a car (control goes to
	 * BeamNG). The main source set can't see client classes, hence the hook.
	 */
	public static volatile java.util.function.LongConsumer enterHook = id -> { };

	public BngVehicleEntity(EntityType<? extends BngVehicleEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_BNG_ID, 0L);
	}

	public long bngId() {
		return this.entityData.get(DATA_BNG_ID);
	}

	public void setBngId(long id) {
		this.entityData.set(DATA_BNG_ID, id);
	}

	public String model() {
		return model;
	}

	@Override
	public void tick() {
		super.tick();
		follow();
	}

	/** Places the box from BeamNG's data. Returns false if the vehicle isn't in the newest data. */
	public boolean follow() {
		CrossoverCoords.Region region = BngWorld.region();
		Vehicles.Info v = Vehicles.latest().get(bngId());
		if (region == null || v == null) {
			return false;
		}
		String m = v.model() != null ? v.model() : "";
		if (!m.equals(model)) {
			model = m;
			this.setCustomName(Component.literal("a BeamNG " + (m.isEmpty() ? "car" : m)));   // death messages: "hit by a BeamNG pickup"
			this.setCustomNameVisible(false);
		}
		V3[] a = v.axes();
		// World-aligned envelope of the oriented box, in Minecraft axes (MC X = X, MC Y = canonical Z, MC Z = canonical Y).
		double s = region.scale();   // metres -> Minecraft blocks
		double ex = (Math.abs(a[0].x()) + Math.abs(a[1].x()) + Math.abs(a[2].x())) * s;
		double ey = (Math.abs(a[0].y()) + Math.abs(a[1].y()) + Math.abs(a[2].y())) * s;
		double ez = (Math.abs(a[0].z()) + Math.abs(a[1].z()) + Math.abs(a[2].z())) * s;
		boolean resized = Math.abs(hx - ex) > 0.01 || Math.abs(hy - ez) > 0.01 || Math.abs(hz - ey) > 0.01;
		hx = ex;
		hy = ez;
		hz = ey;
		V3 c = CrossoverCoords.canonicalToMinecraftPosition(v.centerAt(BngLink.nowMs()), region);
		if (v.fwd() != null) {
			V3 f = CrossoverCoords.canonicalToMinecraftDirection(v.fwd());
			this.setYRot((float) Math.toDegrees(Math.atan2(-f.x(), f.z())));
		}
		this.setPos(c.x(), c.y() - hy, c.z());
		if (resized) {
			this.refreshDimensions();
		}
		return true;
	}

	@Override
	public EntityDimensions getDimensions(Pose pose) {
		return EntityDimensions.fixed((float) (2.0 * Math.max(hx, hz)), (float) (2.0 * hy));
	}

	/**
	 * The server's copy of the box is this much lower. Steve stands on the client's copy (his
	 * movement is client-side), and the server re-checks every move against its own copy, placed
	 * at slightly different moments; where its roof is higher than his feet he "collides with
	 * something new" and is pulled back. With riders no longer hit by their own car, Steve still
	 * slid off the back at about 10 m/s without this margin.
	 */
	static final double SERVER_ROOF_MARGIN = 0.05;

	@Override
	protected AABB makeBoundingBox() {
		double top = getY() + 2.0 * hy - (level().isClientSide ? 0.0 : SERVER_ROOF_MARGIN);
		return new AABB(getX() - hx, getY(), getZ() - hz, getX() + hx, top, getZ() + hz);
	}

	@Override
	public boolean canBeCollidedWith() {
		return true;   // solid like a boat: players collide with it and can stand on it
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	/**
	 * Right-click with an empty hand: get in. Where BeamNG hosts, BeamNG takes over the controls
	 * (F4 gets out). In a Minecraft-hosted world the player sits in this proxy (sneak gets out)
	 * and drives from Minecraft (client HostDrive).
	 */
	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		net.minecraft.world.item.ItemStack held = player.getMainHandItem();
		if (held.is(net.minecraft.world.item.Items.FLINT_AND_STEEL) || held.is(net.minecraft.world.item.Items.FIRE_CHARGE)) {
			if (!this.level().isClientSide) {   // sets the car on fire, as BeamNG's own fire does
				CarEffects.ignite(bngId());
				this.igniteForSeconds(BURN_SECONDS);   // and Minecraft's flames on it
				boolean charge = held.is(net.minecraft.world.item.Items.FIRE_CHARGE);
				this.level().playSound(null, this.blockPosition(), charge ? net.minecraft.sounds.SoundEvents.FIRECHARGE_USE
					: net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F);
				if (charge) {
					held.consume(1, player);
				} else {
					held.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
				}
			}
			return InteractionResult.sidedSuccess(this.level().isClientSide);
		}
		if (held.is(net.minecraft.world.item.Items.WATER_BUCKET)) {
			if (!this.level().isClientSide) {   // puts the fire out
				CarEffects.extinguish(bngId());
				this.clearFire();
				this.level().playSound(null, this.blockPosition(), net.minecraft.sounds.SoundEvents.BUCKET_EMPTY,
					net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F);
				player.setItemInHand(hand, net.minecraft.world.item.BucketItem.getEmptySuccessItem(held, player));
			}
			return InteractionResult.sidedSuccess(this.level().isClientSide);
		}
		if (!held.isEmpty() || player.isPassenger()) {
			return InteractionResult.PASS;
		}
		if (this.level().isClientSide) {
			enterHook.accept(bngId());
		} else if (BngWorld.isHostWorld()) {
			player.startRiding(this, true);
		}
		return InteractionResult.sidedSuccess(this.level().isClientSide);
	}

	/** A seated player is low inside the car, not on its roof (vanilla's default). */
	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		return new Vec3(0.0, SEAT_HEIGHT, 0.0);
	}

	static final double SEAT_HEIGHT = 0.3;

	/**
	 * Getting out puts the driver on the ground beside the car, on its left, not on the roof
	 * (vanilla). The box's bottom can sit a few cm below the ground (it spawned at y -60.045 on
	 * grass whose top is -60), so the spot is snapped onto the floor there, as vanilla does for
	 * minecarts; Jas landed inside the grass without this.
	 */
	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		double yaw = Math.toRadians(this.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // the car's forward, Minecraft axes
		double side = Math.min(hx, hz) + DISMOUNT_CLEARANCE;   // the car's half width (scaled cars are wider) and a step
		double x = this.getX() + fz * side, z = this.getZ() - fx * side;
		Vec3 safe = DismountHelper.findSafeDismountLocation(passenger.getType(), this.level(), BlockPos.containing(x, this.getY() + 0.5, z), true);
		return safe != null ? new Vec3(x, safe.y, z) : super.getDismountLocationForPassenger(passenger);
	}

	static final double DISMOUNT_CLEARANCE = 0.6;   // blocks beyond the car's side


	/**
	 * What hits do to the car (minecraft-host.md, "Items"), through BeamNG's own damage (carfx.lua):
	 * <ul>
	 * <li>the car remover stick: the car is deleted;</li>
	 * <li>fire, lava, lightning: the car catches fire (at most every {@link #IGNITE_COOLDOWN_TICKS});</li>
	 * <li>a falling anvil: a dent from above;</li>
	 * <li>swords, axes, the mace, tridents, arrows, punches: a dent where the blow lands, sized by
	 * the damage, and with an edge or a point, the tire there pops if it lands next to a wheel;</li>
	 * <li>and every blow, snowballs and eggs too, shoves the car along it:
	 * {@link #IMPULSE_PER_DAMAGE} m/s per point of damage.</li>
	 * </ul>
	 * Explosions are left out: BlastBridge sends them to BeamNG as a blast.
	 */
	@Override
	public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
		Entity attacker = source.getEntity(), direct = source.getDirectEntity();
		if (this.level().isClientSide || this.isRemoved() || source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)
			|| (attacker != null && attacker.getVehicle() == this)) {   // the driver punching from inside
			return false;
		}
		if (attacker instanceof LivingEntity living && direct == attacker && living.getMainHandItem().is(dev.bngmc.bridge.BngBridgeMod.CAR_REMOVER)) {
			CarEffects.remove(bngId());
			if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
				Vec3 c = this.getBoundingBox().getCenter();
				sl.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, c.x, c.y, c.z, 40, hx * 0.6, hy * 0.6, hz * 0.6, 0.05);
			}
			this.level().playSound(null, this.blockPosition(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
				net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.4F);
			return true;
		}
		if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || source.is(net.minecraft.tags.DamageTypeTags.IS_LIGHTNING)) {
			if (this.tickCount - lastIgniteTick > IGNITE_COOLDOWN_TICKS) {
				lastIgniteTick = this.tickCount;
				CarEffects.ignite(bngId());
			}
			return true;
		}
		if (source.is(net.minecraft.world.damagesource.DamageTypes.FALLING_ANVIL)) {
			AABB b = this.getBoundingBox();
			double dent = dev.bngmc.bridge.BridgeSettings.factor(dev.bngmc.bridge.BridgeSettings.get().dentPercent());
			if (dent > 0) {
				CarEffects.dent(bngId(), new Vec3(b.getCenter().x, b.maxY, b.getCenter().z), new Vec3(0, -1, 0), (float) (Math.max(amount, 8) * dent), false);
			}
			return true;
		}
		boolean projectile = direct instanceof net.minecraft.world.entity.projectile.Projectile;
		if ((amount <= 0 && !projectile) || source.getSourcePosition() == null) {
			return false;
		}
		Vec3 blow = blowOn(attacker, direct);
		dev.bngmc.bridge.BridgeSettings settings = dev.bngmc.bridge.BridgeSettings.get();
		if (blow != null && amount > 0 && settings.dentPercent() > 0) {
			boolean sharp = projectile || (attacker instanceof LivingEntity l && (l.getMainHandItem().getItem() instanceof net.minecraft.world.item.SwordItem
				|| l.getMainHandItem().getItem() instanceof net.minecraft.world.item.AxeItem
				|| l.getMainHandItem().getItem() instanceof net.minecraft.world.item.TridentItem));
			Vec3 along = direct != null && projectile ? direct.getDeltaMovement() : (attacker != null ? attacker.getLookAngle() : Vec3.ZERO);
			if (along.lengthSqr() < 1e-6) {
				along = this.getBoundingBox().getCenter().subtract(blow);
			}
			CarEffects.dent(bngId(), blow, along.normalize(), (float) (amount * dev.bngmc.bridge.BridgeSettings.factor(settings.dentPercent())), sharp);
		}
		if (settings.shovePercent() == 0) {
			return true;
		}
		net.minecraft.world.phys.Vec3 from = source.getSourcePosition();
		net.minecraft.world.phys.Vec3 dir = this.getBoundingBox().getCenter().subtract(from);
		dir = new net.minecraft.world.phys.Vec3(dir.x, Math.max(0, dir.y) * 0.3, dir.z).normalize();
		double dv = Math.max(0.05, Math.min(MAX_IMPULSE, amount * IMPULSE_PER_DAMAGE)) * dev.bngmc.bridge.BridgeSettings.factor(settings.shovePercent());
		dev.bngmc.bridge.coords.V3 c = dev.bngmc.bridge.coords.CrossoverCoords.minecraftToCanonicalVelocity(
			new dev.bngmc.bridge.coords.V3(dir.x * dv, dir.y * dv, dir.z * dv));
		com.google.gson.JsonObject msg = new com.google.gson.JsonObject();
		msg.addProperty("id", bngId());
		com.google.gson.JsonArray a = new com.google.gson.JsonArray();
		a.add(c.x());
		a.add(c.y());
		a.add(c.z());
		msg.add("dv", a);
		dev.bngmc.bridge.link.BngLink.get().send(dev.bngmc.bridge.link.Protocol.IMPULSE, msg);
		return true;
	}

	/**
	 * Where a blow lands on the car's box: a projectile where it is; a melee hit where the
	 * attacker's line of sight meets the box. Null if neither says.
	 */
	private Vec3 blowOn(Entity attacker, Entity direct) {
		AABB box = this.getBoundingBox();
		if (direct instanceof net.minecraft.world.entity.projectile.Projectile p) {
			Vec3 at = p.position();
			Vec3 v = p.getDeltaMovement();
			return box.inflate(0.5).contains(at) && v.lengthSqr() > 1e-6
				? box.clip(at.subtract(v.normalize().scale(2)), at.add(v.normalize().scale(2))).orElse(at) : at;
		}
		if (attacker != null) {
			Vec3 eye = attacker.getEyePosition();
			return box.clip(eye, eye.add(attacker.getLookAngle().scale(8))).orElse(box.getCenter());
		}
		return null;
	}

	private int lastIgniteTick = -1000;
	static final float BURN_SECONDS = 20;
	static final int IGNITE_COOLDOWN_TICKS = 60;

	/**
	 * A blow nudges the car, no more: a sword or a fist doesn't push a car around (Jas). The dent
	 * (CarEffects.dent) is what a hit does; at 0.4 m/s per damage a sword shoved it ~3 m/s.
	 */
	static final double IMPULSE_PER_DAMAGE = 0.03;
	static final double MAX_IMPULSE = 1.0;

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
		// Position comes from BeamNG's data in tick(), not from the server's position packets.
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
	}
}
