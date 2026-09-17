package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;
import java.util.Set;

/**
 * Territory-aware controls for the Spore infection family and the sporesrp Builder structure.
 *
 * <p>Only blocks emitted by the infection tags in Spore 2.2.0j are considered fungal here.
 * This deliberately excludes laboratory machinery and other player-placeable Spore content.
 * Ownership is always resolved through the canonical {@value #MOD_ID} faction, including
 * when the optional sporesrp add-on is the source of the mutation.</p>
 */
public final class SporeCompat {
    public static final String MOD_ID = "spore";
    private static final String VANILLA_NAMESPACE = "minecraft";
    private static final String MYCELIUM_PATH = "mycelium";
    private static final String OVERGROWN_SPAWNER_PATH = "overgrown_spawner";
    private static final Set<String> TERRITORY_BOUND_ORGANOID_PATHS = Set.of(
            "mound", "delusioner", "umarmed", "braurei", "tentacle", "arena_tendril",
            "gastgaber", "reconstructor", "verva", "usurper", "proto", "hivetumor");
    private static final ThreadLocal<Integer> SCENT_SUMMON_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * Blocks which can be emitted by Spore infection, foliage spread, corpses, casings, or
     * infection structures.  This list is intentionally broader than the fungal_blocks tag:
     * the source also places several blocks through removable_foliage and block_st without
     * adding them to fungal_blocks.
     */
    private static final Set<String> FUNGAL_BLOCK_PATHS = Set.of(
            "remains", "rooted_biomass", "biomass_block", "sicken_biomass_block",
            "calcified_biomass_block", "gastric_biomass_block", "fungal_shell", "membrane_block",
            "freeze_burned_biomass",
            "infested_dirt", "infested_stone", "infested_netherrack", "infested_soul_sand",
            "infested_end_stone", "infested_sand", "infested_gravel", "infested_deepslate",
            "infested_red_sand", "infested_clay", "infested_cobblestone", "infested_cobbled_deepslate",
            "infested_stone_bricks", "infested_bricks", "infested_laboratory_block",
            "infested_laboratory_block1", "infested_laboratory_block2", "infested_laboratory_block3",
            "overgrown_spawner", "brain_remnants", "rotten_log", "rotten_planks", "rotten_stair",
            "rotten_slab", "rotten_scraps", "rotten_branch", "rotten_crops", "rotten_grass",
            "rotten_fern", "rotten_bush", "growths_big", "growths_small", "blomfung", "bloomfung2",
            "growth_mycelium", "fungal_stem_sapling", "fungal_roots", "underwater_fungal_stem",
            "underwater_fungal_stem_top", "wall_growths", "wall_growths_big", "wall_growths_fleshy",
            "hanging_fungal_stem", "mycelium_veins", "fungal_stem", "fungal_stem_top", "biomass_lump",
            "hive_spawn", "biomass_bulb", "bile_lump", "fang_lump", "exploding_lump", "fungal_clamp",
            "drowned_lump", "poisoning_lump", "glowshroom", "hand", "vocals", "lungs", "acidic_sack",
            "outpost_watcher", "organite", "wall_remains", "frozen_remains", "rooted_mycelium",
            "mycelium_block", "mycelium_slab", "bile", "crusted_bile", "acid", "tar");

    /**
     * Outputs of FoliageSpread's extra placers, death residue, casing generation, and the
     * rotten-wood path.  They do not contain enough information to reconstruct the displaced
     * or newly occupied block, so losing Spore control removes them instead of inventing stone.
     */
    private static final Set<String> AIR_CLEANUP_PATHS = Set.of(
            "remains", "wall_remains", "frozen_remains",
            "growths_big", "growths_small", "blomfung", "bloomfung2", "growth_mycelium",
            "fungal_stem_sapling", "fungal_roots", "underwater_fungal_stem",
            "underwater_fungal_stem_top", "wall_growths", "wall_growths_big", "wall_growths_fleshy",
            "hanging_fungal_stem", "mycelium_veins", "fungal_stem", "fungal_stem_top",
            "mycelium_block", "mycelium_slab", "biomass_lump", "hive_spawn", "biomass_bulb",
            "bile_lump", "fang_lump", "exploding_lump", "fungal_clamp", "drowned_lump",
            "poisoning_lump", "glowshroom", "hand", "vocals", "lungs", "acidic_sack",
            "outpost_watcher", "organite", "brain_remnants",
            "rooted_biomass", "biomass_block", "sicken_biomass_block", "calcified_biomass_block",
            "gastric_biomass_block", "fungal_shell", "membrane_block", "freeze_burned_biomass",
            "rotten_log", "rotten_planks", "rotten_stair", "rotten_slab", "rotten_scraps",
            "rotten_branch", "rotten_crops", "rotten_bush", "overgrown_spawner",
            "bile", "crusted_bile", "acid", "tar");

    /**
     * Effects emitted by Spore's traps, infection clouds, infected units, and fungal blocks.
     * Symbiosis is deliberately excluded because it is Spore's beneficial ally effect.
     */
    private static final Set<String> SPORE_DEBUFF_PATHS = Set.of(
            "mycelium_ef", "madness", "starvation", "uneasy", "ignitable", "marker",
            "corrosion", "frostbite", "biled");

    private SporeCompat() {
    }

    /** Blocks infection-tagged placements outside territory only when the visual option is enabled. */
    public static boolean allowFungalInfectionBlockPlacement(LevelAccessor level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel server) || !isFungalInfectionBlock(state)) {
            return true;
        }
        return !CompatSavedData.get(server).config().restrictSporeInfectionSpread()
                || TerritoryControlApi.isOwnedByModFaction(server, pos, MOD_ID);
    }

    /** Separately protects the Builder's overgrown-spawner structure without requiring spread containment. */
    public static boolean allowOvergrownSpawnerPlacement(LevelAccessor level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel server) || !isOvergrownSpawner(state)) {
            return true;
        }
        return !CompatSavedData.get(server).config().restrictSporeSpawnerStructures()
                || TerritoryControlApi.isOwnedByModFaction(server, pos, MOD_ID);
    }

    public static boolean allowMoundSpawn(ServerLevel level, BlockPos pos) {
        return allowOrganoidSpawn(level, pos);
    }

    public static boolean allowOrganoidSpawn(ServerLevel level, BlockPos pos) {
        return !CompatSavedData.get(level).config().restrictSporeMounds()
                || TerritoryControlApi.isOwnedByModFaction(level, pos, MOD_ID);
    }

    public static boolean allowVigilSpawn(ServerLevel level, BlockPos pos) {
        return !CompatSavedData.get(level).config().restrictSporeVigils()
                || TerritoryControlApi.isOwnedByModFaction(level, pos, MOD_ID);
    }

    /**
     * Returns true when the optional Spore friendly-fire control is enabled and the entity is
     * assigned to the Spore faction or one of its configured allies.  The lookup intentionally
     * uses Territory Control's canonical entity provider chain, so Custom NPC, Rat Nations, and
     * other registered providers work without being hard-coded here.
     */
    public static boolean shouldProtectFriendlyEntity(Level level, Entity entity) {
        if (!(level instanceof ServerLevel server) || entity == null || entity.level() != server
                || !CompatSavedData.get(server).config().protectSporeFriendlyEntities()) {
            return false;
        }
        Optional<String> sporeFaction = TerritoryControlApi.factionIdForMod(server, MOD_ID);
        Optional<String> entityFaction = TerritoryControlApi.factionIdForEntity(server, entity);
        return sporeFaction.isPresent() && entityFaction.isPresent()
                && TerritoryControlApi.areFactionsSameOrAllied(server, sporeFaction.get(), entityFaction.get());
    }

    /** Used by the global effect event so clouds and projectile effects are covered too. */
    public static boolean shouldBlockFriendlySporeEffect(LivingEntity entity, MobEffectInstance effect) {
        if (entity == null || effect == null || !isSporeDebuff(effect)) {
            return false;
        }
        return shouldProtectFriendlyEntity(entity.level(), entity);
    }

    static boolean isSporeDebuff(MobEffectInstance effect) {
        ResourceLocation id = effect == null ? null : ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect());
        return id != null && MOD_ID.equals(id.getNamespace()) && SPORE_DEBUFF_PATHS.contains(id.getPath());
    }

    /** Removes only Proto's low-altitude terrain override; water and air selection remain untouched. */
    public static int adjustUndergroundCalamityThreshold(Level level, int originalThreshold) {
        if (!(level instanceof ServerLevel server)
                || !CompatSavedData.get(server).config().disableSporeUndergroundBias()) {
            return originalThreshold;
        }
        return server.getMinBuildHeight();
    }

    static boolean isTerritoryBoundOrganoidId(ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace())
                && TERRITORY_BOUND_ORGANOID_PATHS.contains(id.getPath());
    }

    static boolean isScentSummonInProgress() {
        return SCENT_SUMMON_DEPTH.get() > 0;
    }

    /** Keeps Scent's configurable skill summons exempt without exempting unrelated joins. */
    public static boolean addScentSummonedEntity(Level level, Entity entity) {
        SCENT_SUMMON_DEPTH.set(SCENT_SUMMON_DEPTH.get() + 1);
        try {
            return level.addFreshEntity(entity);
        } finally {
            int depth = SCENT_SUMMON_DEPTH.get() - 1;
            if (depth <= 0) {
                SCENT_SUMMON_DEPTH.remove();
            } else {
                SCENT_SUMMON_DEPTH.set(depth);
            }
        }
    }

    /** Called by the optional Builder mixin before its complete template is placed. */
    public static boolean allowOvergrownSpawnerStructure(LevelAccessor level, BlockPos origin) {
        if (!(level instanceof ServerLevel server)) {
            return true;
        }
        return !CompatSavedData.get(server).config().restrictSporeSpawnerStructures()
                || TerritoryControlApi.isOwnedByModFaction(server, origin, MOD_ID);
    }

    public static boolean isFungalInfectionBlock(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && isFungalInfectionBlockId(id);
    }

    /** A fungal-to-fungal replacement is an infection lifecycle update, not new territory placement. */
    static boolean isFungalLifecycleTransition(BlockState oldState, BlockState newState) {
        ResourceLocation oldId = ForgeRegistries.BLOCKS.getKey(oldState.getBlock());
        ResourceLocation newId = ForgeRegistries.BLOCKS.getKey(newState.getBlock());
        return isFungalLifecycleTransition(oldId, newId);
    }

    static boolean isFungalLifecycleTransition(ResourceLocation oldId, ResourceLocation newId) {
        return oldId != null && newId != null && !oldId.equals(newId)
                && isFungalInfectionBlockId(oldId) && isFungalInfectionBlockId(newId);
    }

    static boolean isFungalInfectionBlockId(ResourceLocation id) {
        return (VANILLA_NAMESPACE.equals(id.getNamespace()) && MYCELIUM_PATH.equals(id.getPath()))
                || (MOD_ID.equals(id.getNamespace()) && FUNGAL_BLOCK_PATHS.contains(id.getPath()));
    }

    static boolean isOvergrownSpawner(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && MOD_ID.equals(id.getNamespace()) && OVERGROWN_SPAWNER_PATH.equals(id.getPath());
    }

    /** Registers Spore's terrain table; the core owns owner-transition timing and cleanup scans. */
    public static TerritoryControlApi.TerrainCleanupProfile terrainCleanupProfile() {
        return new TerritoryControlApi.TerrainCleanupProfile(MOD_ID,
                level -> CompatSavedData.get(level).config().restoreSporeOnLoss(),
                (level, pos, state) -> {
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                    return isFungalInfectionBlockId(id) ? restorationFor(id) : null;
                });
    }

    /**
     * Describes what the source block can be recovered to after Spore control is lost.  The
     * source's direct block-infection table is reversible for these fixed targets; foliage and
     * displaced wood are deliberately handled as air above.
     */
    private static BlockState restorationFor(ResourceLocation id) {
        if (VANILLA_NAMESPACE.equals(id.getNamespace()) && MYCELIUM_PATH.equals(id.getPath())) {
            return Blocks.DIRT.defaultBlockState();
        }
        if (isAirCleanupBlockId(id)) {
            return Blocks.AIR.defaultBlockState();
        }
        return switch (id.getPath()) {
            case "infested_stone" -> Blocks.STONE.defaultBlockState();
            case "infested_dirt" -> Blocks.DIRT.defaultBlockState();
            case "infested_deepslate" -> Blocks.DEEPSLATE.defaultBlockState();
            case "infested_sand" -> Blocks.SAND.defaultBlockState();
            case "infested_gravel" -> Blocks.GRAVEL.defaultBlockState();
            case "infested_netherrack" -> Blocks.NETHERRACK.defaultBlockState();
            case "infested_end_stone" -> Blocks.END_STONE.defaultBlockState();
            case "infested_soul_sand" -> Blocks.SOUL_SAND.defaultBlockState();
            case "infested_red_sand" -> Blocks.RED_SAND.defaultBlockState();
            case "infested_clay" -> Blocks.CLAY.defaultBlockState();
            case "infested_cobblestone" -> Blocks.COBBLESTONE.defaultBlockState();
            case "infested_cobbled_deepslate" -> Blocks.COBBLED_DEEPSLATE.defaultBlockState();
            case "infested_stone_bricks" -> Blocks.STONE_BRICKS.defaultBlockState();
            case "infested_bricks" -> Blocks.BRICKS.defaultBlockState();
            case "infested_laboratory_block" -> sporeBlockState("lab_block");
            case "infested_laboratory_block1" -> sporeBlockState("lab_block1");
            case "infested_laboratory_block2" -> sporeBlockState("lab_block2");
            case "infested_laboratory_block3" -> sporeBlockState("lab_block3");
            case "rooted_mycelium" -> Blocks.SCULK.defaultBlockState();
            case "rotten_grass" -> Blocks.GRASS.defaultBlockState();
            case "rotten_fern" -> Blocks.FERN.defaultBlockState();
            default -> Blocks.AIR.defaultBlockState();
        };
    }

    static boolean isAirCleanupBlockId(ResourceLocation id) {
        return MOD_ID.equals(id.getNamespace()) && AIR_CLEANUP_PATHS.contains(id.getPath());
    }

    private static BlockState sporeBlockState(String path) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(MOD_ID, path));
        return block == null ? Blocks.AIR.defaultBlockState() : block.defaultBlockState();
    }
}
