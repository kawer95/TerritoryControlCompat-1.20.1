package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

public final class EyesCompat {
    public static final String MOD_ID = "the_eyes_are_back";
    private static final Set<String> REPLACEMENT = Set.of("no_tick_soil", "eyesoil", "eye_sand", "no_tick_sand");
    private static final Set<String> PLANTS = Set.of("eye_roots", "eyevine", "eyevinetop", "e_ye_growth",
            "eyeflower", "short_eye_nerves", "tall_eye_nerves", "tall_eye_nerves_top", "dead_eye_bush",
            "short_withered_bush", "tall_withered_bush", "sightful_cactus", "pale_sightful_flower",
            "prickly_pear", "eye_tree_sapling");

    private EyesCompat() {
    }

    public static boolean allowReplacementBlockPlacement(LevelAccessor level, BlockPos pos, BlockState state) {
        return !(level instanceof ServerLevel server) || !isReplacementBlock(state)
                || !CompatSavedData.get(server).config().restrictEyes()
                || TerritoryControlApi.isOwnedByModFaction(server, pos, MOD_ID);
    }

    public static boolean isReplacementBlock(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && MOD_ID.equals(id.getNamespace()) && REPLACEMENT.contains(id.getPath());
    }

    public static TerritoryControlApi.TerrainCleanupProfile terrainCleanupProfile() {
        return new TerritoryControlApi.TerrainCleanupProfile(MOD_ID,
                level -> CompatSavedData.get(level).config().eyesCollapse(),
                (level, pos, state) -> replacementFor(state));
    }

    private static BlockState replacementFor(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null || !MOD_ID.equals(id.getNamespace())) return null;
        if (REPLACEMENT.contains(id.getPath())) return Blocks.COBBLESTONE.defaultBlockState();
        return PLANTS.contains(id.getPath()) ? Blocks.AIR.defaultBlockState() : null;
    }
}
