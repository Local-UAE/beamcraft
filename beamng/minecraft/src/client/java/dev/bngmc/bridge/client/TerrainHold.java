package dev.bngmc.bridge.client;

import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.TerrainManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps Steve from falling into the void where BeamNG's ground hasn't arrived yet (after a
 * teleport, a level change, or outrunning the sampler): while the column under the player is
 * unsampled, gravity is off and downward motion is cancelled. Lifted as soon as the column
 * arrives, usually within one round trip (~50-100 ms). Client tick; the player's movement is
 * client-side in Minecraft, so this is where it has to happen.
 */
public final class TerrainHold {
	private TerrainHold() {
	}

	private static boolean holding;

	public static boolean holding() {
		return holding;
	}

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		boolean want = p != null && BngWorld.isBridgeWorld() && BngWorld.region() != null && !p.getAbilities().flying
			&& !TerrainManager.isSampled(Mth.floor(p.getX()), Mth.floor(p.getZ()));
		if (want) {
			Vec3 v = p.getDeltaMovement();
			if (v.y < 0) {
				p.setDeltaMovement(v.x, 0, v.z);
			}
			p.fallDistance = 0;
		}
		if (want != holding && p != null) {
			p.setNoGravity(want);
			holding = want;
		}
	}
}
