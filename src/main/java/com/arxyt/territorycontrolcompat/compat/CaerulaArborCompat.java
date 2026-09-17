package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrol.core.data.TerritorySavedData;
import com.arxyt.territorycontrol.core.protection.BlockDamageProtection;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class CaerulaArborCompat {
    public static final String MOD_ID = "caerula_arbor";
    private static final Set<String> CONTROLLED = Set.of("sea_trail_solid", "trail_pulse", "sea_trail_growing", "sea_trail_init", "sea_trail_grown", "ocean_ovary", "red_ovary");
    private static final Set<String> TIDE_POLLUTION = Set.of(
            "ocean_ovary", "red_ovary", "sea_trail_solid", "trail_pulse", "sea_trail_growing", "sea_trail_init", "sea_trail_grown", "sea_trail_burnt", "sea_trail_burnt_solid", "sea_trail_stop",
            "trail_log", "stripped_trail_log", "trail_plank", "trail_stone", "trail_leave", "trail_pumpking", "trail_debris", "trail_mushroom",
            "nethersea_soul_sand", "nethersea_bugged_stone", "deep_seagrass", "viviparous_lily"
    );
    private static final Set<String> OVARIES = Set.of("ocean_ovary", "red_ovary");
    private static final Map<String, BlockState> TIDE_REPLACEMENTS = Map.ofEntries(
            Map.entry("sea_trail_solid", Blocks.COBBLESTONE.defaultBlockState()),
            Map.entry("trail_pulse", Blocks.COBBLESTONE.defaultBlockState()),
            Map.entry("sea_trail_burnt_solid", Blocks.COBBLESTONE.defaultBlockState()),
            Map.entry("trail_plank", Blocks.OAK_PLANKS.defaultBlockState()),
            Map.entry("trail_stone", Blocks.STONE.defaultBlockState()),
            Map.entry("nethersea_bugged_stone", Blocks.STONE.defaultBlockState()),
            Map.entry("nethersea_soul_sand", Blocks.SOUL_SAND.defaultBlockState()),
            Map.entry("trail_pumpking", Blocks.PUMPKIN.defaultBlockState()),
            Map.entry("trail_leave", Blocks.OAK_LEAVES.defaultBlockState())
    );
    private static final Set<String> TIDE_REMOVALS = Set.of(
            "ocean_ovary", "red_ovary", "sea_trail_growing", "sea_trail_init", "sea_trail_grown",
            "sea_trail_burnt", "sea_trail_stop", "trail_debris", "deep_seagrass", "trail_mushroom",
            "viviparous_lily"
    );
    private CaerulaArborCompat() {}
    public static boolean allowControlledBlockPlacement(LevelAccessor level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel server) || !isControlledBlock(state)) return true;
        return !CompatSavedData.get(server).config().restrictCaerula() || TerritoryControlApi.isOwnedByModFaction(server, pos, MOD_ID);
    }
    public static boolean allowOvaryCreation(LevelAccessor level, BlockState state) {
        return !(level instanceof ServerLevel server) || !isOvary(state) || !CompatSavedData.get(server).config().balancedOvaryDensity() || ThreadLocalRandom.current().nextBoolean();
    }
    public static boolean ovaryActive(ServerLevel level, BlockPos pos) { return !CompatSavedData.get(level).config().restrictCaerula() || TerritoryControlApi.isOwnedByModFaction(level, pos, MOD_ID); }
    public static boolean controlledTrailActive(ServerLevel level, BlockPos pos) { return ovaryActive(level, pos); }
    public static void removeOutsideTerritoryTrail(ServerLevel level, BlockPos pos) { BlockDamageProtection.runUntracked(() -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)); TerritorySavedData.get(level).removeProtectedBlock(level, pos); }
    public static boolean isControlledBlock(BlockState state) { ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock()); return id != null && MOD_ID.equals(id.getNamespace()) && CONTROLLED.contains(id.getPath()); }
    public static boolean isTidePollutionBlock(BlockState state) { ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock()); return id != null && MOD_ID.equals(id.getNamespace()) && TIDE_POLLUTION.contains(id.getPath()); }
    public static TerritoryControlApi.TerrainCleanupProfile terrainCleanupProfile() {
        return new TerritoryControlApi.TerrainCleanupProfile(MOD_ID,
                level -> CompatSavedData.get(level).config().tideRecession(),
                (level, pos, state) -> tideCleanupReplacement(state));
    }

    private static boolean isOvary(BlockState state) { ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock()); return id != null && MOD_ID.equals(id.getNamespace()) && OVARIES.contains(id.getPath()); }

    private static BlockState tideCleanupReplacement(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null || !MOD_ID.equals(id.getNamespace())) return null;
        BlockState replacement = TIDE_REPLACEMENTS.get(id.getPath());
        if (replacement != null) return replacement;
        if ("trail_log".equals(id.getPath())) return preserveAxis(state, Blocks.OAK_LOG.defaultBlockState());
        if ("stripped_trail_log".equals(id.getPath())) return preserveAxis(state, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        return TIDE_REMOVALS.contains(id.getPath()) ? Blocks.AIR.defaultBlockState() : null;
    }

    private static BlockState preserveAxis(BlockState source, BlockState target) {
        return source.hasProperty(BlockStateProperties.AXIS)
                ? target.setValue(BlockStateProperties.AXIS, source.getValue(BlockStateProperties.AXIS))
                : target;
    }
}
