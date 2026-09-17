package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/** Territory rules for the terrain infection produced by Abominations Infection. */
public final class AbominationsInfectionCompat {
    public static final String MOD_ID = "abominations_infection";
    private static final Set<String> INFECTION_BLOCKS = Set.of("parasitic_worm_nest", "red_spines", "red_plant",
            "red_root_block", "rooted_stone", "rooted_log", "rooted_cobblestone", "rooted_plank", "rooted_vein",
            "rooted_parasitic_worm_nest", "rooted_sculk", "rooted_bricks", "red_root", "hardenned_red_root",
            "redrootspikes", "heart_root_core", "heart_root", "heartrootcorner", "dormantheartroot", "redrootteeth",
            "redrootbigteeth", "hanging_heart_root", "longhangingredroot", "bloody_red_root", "parasitic_worm_colony",
            "parasiticwormstonenest", "frostbitten_putrid_muck", "infernal_putrid_muck", "tumorroot_core", "tumorroot",
            "tumorroot_dormant", "dense_root", "tumorrootcoredormant");

    private AbominationsInfectionCompat() {
    }

    public static boolean isInfectionBlock(BlockState state) {
        return isInfectionBlockId(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
    }

    static boolean isInfectionBlockId(ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace()) && INFECTION_BLOCKS.contains(id.getPath());
    }

    static Block cleanupReplacement(ResourceLocation id) {
        Block block = ForgeRegistries.BLOCKS.getValue(cleanupReplacementId(id));
        return block == null ? Blocks.AIR : block;
    }

    static ResourceLocation cleanupReplacementId(ResourceLocation id) {
        String path = id == null ? "air" : switch (id.getPath()) {
            case "rooted_stone", "parasiticwormstonenest" -> "stone";
            case "rooted_cobblestone" -> "cobblestone";
            case "rooted_log" -> "oak_log";
            case "rooted_plank" -> "oak_planks";
            case "rooted_bricks" -> "stone_bricks";
            case "rooted_sculk" -> "sculk";
            case "rooted_vein" -> "sculk_vein";
            case "red_root_block", "hardenned_red_root", "frostbitten_putrid_muck", "infernal_putrid_muck",
                    "parasitic_worm_nest" -> "dirt";
            default -> "air";
        };
        return ResourceLocation.withDefaultNamespace(path);
    }

    public static TerritoryControlApi.TerrainCleanupProfile terrainCleanupProfile() {
        return new TerritoryControlApi.TerrainCleanupProfile(MOD_ID,
                level -> CompatSavedData.get(level).config().purgeAbominationsOnLoss(),
                (level, pos, state) -> {
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                    return isInfectionBlockId(id) ? cleanupReplacement(id).defaultBlockState() : null;
                });
    }

    public static boolean isOwnedTerritory(ServerLevel level, BlockPos pos) {
        return TerritoryControlApi.isOwnedByModFaction(level, pos, MOD_ID);
    }
}
