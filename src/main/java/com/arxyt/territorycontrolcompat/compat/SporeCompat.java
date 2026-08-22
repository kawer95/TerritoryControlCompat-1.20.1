package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrol.core.data.Faction;
import com.arxyt.territorycontrol.core.data.TerritorySavedData;
import com.arxyt.territorycontrol.core.protection.BlockDamageProtection;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
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

    /** Union of Spore 2.2.0j's fungal_blocks and foliage-emission block tags. */
    private static final Set<String> FUNGAL_BLOCK_PATHS = Set.of(
            "remains", "rooted_biomass", "biomass_block", "sicken_biomass_block",
            "calcified_biomass_block", "gastric_biomass_block", "fungal_shell", "membrane_block",
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
            "outpost_watcher", "organite", "wall_remains");

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
        return !CompatSavedData.get(level).config().restrictSporeMounds()
                || TerritoryControlApi.isOwnedByModFaction(level, pos, MOD_ID);
    }

    public static boolean allowVigilSpawn(ServerLevel level, BlockPos pos) {
        return !CompatSavedData.get(level).config().restrictSporeVigils()
                || TerritoryControlApi.isOwnedByModFaction(level, pos, MOD_ID);
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

    static boolean isFungalInfectionBlockId(ResourceLocation id) {
        return (VANILLA_NAMESPACE.equals(id.getNamespace()) && MYCELIUM_PATH.equals(id.getPath()))
                || (MOD_ID.equals(id.getNamespace()) && FUNGAL_BLOCK_PATHS.contains(id.getPath()));
    }

    static boolean isOvergrownSpawner(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && MOD_ID.equals(id.getNamespace()) && OVERGROWN_SPAWNER_PATH.equals(id.getPath());
    }

    /** Cleans infection-tagged residues after a chunk changes from the Spore faction to another faction. */
    public static void onOwnershipChanged(ServerLevel level, ChunkPos chunk, Faction previous, Faction current) {
        if (previous == null || current == null || !previous.ownsMod(MOD_ID) || current.ownsMod(MOD_ID)
                || !CompatSavedData.get(level).config().restoreSporeOnLoss()) {
            return;
        }

        cleanseLostTerritory(level, chunk);
        // Spore can enqueue a final placement while ownership is changing. Recheck after that work runs.
        level.getServer().execute(() -> {
            if (CompatSavedData.get(level).config().restoreSporeOnLoss()
                    && !TerritoryControlApi.isOwnedByModFaction(
                    level, chunk.getMiddleBlockPosition(level.getMinBuildHeight()), MOD_ID)) {
                cleanseLostTerritory(level, chunk);
            }
        });
    }

    private static void cleanseLostTerritory(ServerLevel level, ChunkPos chunk) {
        if (!level.hasChunk(chunk.x, chunk.z)) {
            return;
        }

        TerritorySavedData data = TerritorySavedData.get(level);
        Set<String> affectedBlockIds = new HashSet<>();
        FUNGAL_BLOCK_PATHS.forEach(path -> affectedBlockIds.add(MOD_ID + ":" + path));
        affectedBlockIds.add(VANILLA_NAMESPACE + ":" + MYCELIUM_PATH);
        data.removeProtectedBlocksInChunk(level, chunk, affectedBlockIds);

        BlockDamageProtection.runUntracked(() -> {
            for (int x = chunk.getMinBlockX(); x < chunk.getMinBlockX() + 16; x++) {
                for (int z = chunk.getMinBlockZ(); z < chunk.getMinBlockZ() + 16; z++) {
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                        if (id == null || !isFungalInfectionBlockId(id)) {
                            continue;
                        }
                        level.setBlock(pos, restorationFor(id), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        data.removeProtectedBlock(level, pos);
                    }
                }
            }
        });
    }

    /** Mirrors Spore's default cleaning mappings; ambiguous foliage intentionally falls back to cobblestone. */
    private static BlockState restorationFor(ResourceLocation id) {
        if (VANILLA_NAMESPACE.equals(id.getNamespace()) && MYCELIUM_PATH.equals(id.getPath())) {
            return Blocks.DIRT.defaultBlockState();
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
            case "rotten_grass" -> Blocks.GRASS.defaultBlockState();
            case "rotten_fern" -> Blocks.FERN.defaultBlockState();
            default -> Blocks.COBBLESTONE.defaultBlockState();
        };
    }
}
