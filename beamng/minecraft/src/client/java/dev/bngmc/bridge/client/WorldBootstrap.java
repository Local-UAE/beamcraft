package dev.bngmc.bridge.client;

import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.link.BngLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * When Minecraft is on its title screen and BeamNG answers, skip the menus and open the world for
 * the chosen direction (-Dbngbridge.mode, scripts/run-minecraft.ps1 -Mode):
 * <ul>
 *   <li>bridge (default): a void creative world whose only terrain is BeamNG's. Adapted from
 *   upstream (MIT).</li>
 *   <li>host: a superflat creative world where Minecraft hosts and BeamNG cars drive on the grass
 *   (docs/minecraft-host.md).</li>
 *   <li>terrain: the same with Minecraft's normal terrain; BeamNG gets it as a terrain
 *   (TerrainSync).</li>
 * </ul>
 * Disable with -Dbngbridge.noAutoWorld=true.
 */
public final class WorldBootstrap {
	private WorldBootstrap() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	public static final String LEVEL_ID = "bng-bridge";
	public static final String HOST_LEVEL_ID = "bng-host";
	public static final String TERRAIN_LEVEL_ID = "bng-terrain";

	public static boolean hostMode() {
		return "host".equalsIgnoreCase(System.getProperty("bngbridge.mode", "bridge")) || terrainMode();
	}

	public static boolean terrainMode() {
		return "terrain".equalsIgnoreCase(System.getProperty("bngbridge.mode", "bridge"));
	}
	private static boolean attempted;

	public static void tick(Minecraft mc) {
		boolean onMenu = mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen;
		if (attempted || Boolean.getBoolean("bngbridge.noAutoWorld") || !onMenu || !BngLink.get().connected()) {
			return;
		}
		attempted = true;
		if (mc.options.onboardAccessibility) {
			mc.options.onboardAccessibility = false;
			mc.options.save();
		}
		boolean host = hostMode();
		boolean terrain = terrainMode();
		// -Dbngbridge.world: a save of its own (a downloaded map named "... [BeamNG]"), opened as it is
		String own = System.getProperty("bngbridge.world", "");
		String id = !own.isEmpty() ? own : terrain ? TERRAIN_LEVEL_ID : host ? HOST_LEVEL_ID : LEVEL_ID;
		mc.execute(() -> {
			if (mc.getLevelSource().levelExists(id)) {
				LOG.info("Opening the BeamNG {} world", terrain ? "terrain" : host ? "host" : "bridge");
				mc.createWorldOpenFlows().openWorld(id, () -> mc.setScreen(new TitleScreen()));
			} else if (terrain) {
				LOG.info("Creating the BeamNG terrain world");
				createTerrain(mc);
			} else if (host) {
				LOG.info("Creating the BeamNG host world");
				createHost(mc);
			} else {
				LOG.info("Creating the BeamNG bridge world");
				create(mc);
			}
		});
	}

	private static void create(Minecraft mc) {
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		LevelSettings settings = new LevelSettings(BngWorld.BRIDGE_LEVEL_NAME, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules,
			WorldDataConfiguration.DEFAULT);
		WorldOptions options = new WorldOptions(0L, false, false);
		mc.createWorldOpenFlows().createFreshLevel(LEVEL_ID, settings, options, registries -> {
			HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
			HolderSet<StructureSet> noStructures = HolderSet.direct();
			FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.of(noStructures), biomes.getOrThrow(Biomes.THE_VOID), List.of());
			flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.AIR));
			flat.updateLayers();
			WorldDimensions dims = WorldPresets.createNormalWorldDimensions(registries);
			return dims.replaceOverworldGenerator(registries, new FlatLevelSource(flat));
		}, new TitleScreen());
	}

	/**
	 * Minecraft's normal terrain (a random seed), creative, the sun held at noon; BeamNG drives on it
	 * through TerrainSync.
	 */
	private static void createTerrain(Minecraft mc) {
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		LevelSettings settings = new LevelSettings(BngWorld.TERRAIN_LEVEL_NAME, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules,
			WorldDataConfiguration.DEFAULT);
		WorldOptions options = new WorldOptions(WorldOptions.randomSeed(), true, false);
		mc.createWorldOpenFlows().createFreshLevel(TERRAIN_LEVEL_ID, settings, options, WorldPresets::createNormalWorldDimensions,
			new TitleScreen());
	}

	/**
	 * Superflat: bedrock, 2 dirt, grass, with the grass top at y = {@link BngWorld#HOST_GROUND_TOP_Y}
	 * like vanilla's. The mod's datapack lowers the overworld floor to y = -2032, and flat layers
	 * start at the floor, so air fills the space below the bedrock.
	 */
	private static void createHost(Minecraft mc) {
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		LevelSettings settings = new LevelSettings(BngWorld.HOST_LEVEL_NAME, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules,
			WorldDataConfiguration.DEFAULT);
		WorldOptions options = new WorldOptions(0L, false, false);
		mc.createWorldOpenFlows().createFreshLevel(HOST_LEVEL_ID, settings, options, registries -> {
			HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
			int minY = registries.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value().minY();
			int bedrockY = BngWorld.HOST_GROUND_TOP_Y - 4;
			FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()), biomes.getOrThrow(Biomes.PLAINS), List.of());
			if (bedrockY > minY) {
				flat.getLayersInfo().add(new FlatLayerInfo(bedrockY - minY, Blocks.AIR));
			}
			flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
			flat.getLayersInfo().add(new FlatLayerInfo(2, Blocks.DIRT));
			flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.GRASS_BLOCK));
			flat.updateLayers();
			WorldDimensions dims = WorldPresets.createNormalWorldDimensions(registries);
			return dims.replaceOverworldGenerator(registries, new FlatLevelSource(flat));
		}, new TitleScreen());
	}
}
