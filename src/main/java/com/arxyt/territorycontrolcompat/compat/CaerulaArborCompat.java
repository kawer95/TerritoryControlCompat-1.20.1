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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.HashSet;
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
    private static boolean isOvary(BlockState state) { ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock()); return id != null && MOD_ID.equals(id.getNamespace()) && OVARIES.contains(id.getPath()); }
    public static void onOwnershipChanged(ServerLevel level, ChunkPos chunk, Faction previous, Faction current) {
        if (previous == null || current == null || !previous.ownsMod(MOD_ID) || !CompatSavedData.get(level).config().tideRecession()) return;
        cleanseTide(level, chunk);
        // Some sea-trail procedures queue a final block write in the same server tick.
        // Recheck after queued work has run so an already-lost chunk cannot keep a residue.
        level.getServer().execute(() -> {
            if (CompatSavedData.get(level).config().tideRecession()
                    && !TerritoryControlApi.isOwnedByModFaction(level, chunk.getMiddleBlockPosition(level.getMinBuildHeight()), MOD_ID)) {
                cleanseTide(level, chunk);
            }
        });
    }

    private static void cleanseTide(ServerLevel level, ChunkPos chunk) {
        if (!level.hasChunk(chunk.x, chunk.z)) return;

        // These are emitted by the sea-trail expansion procedures, not player-built decor.
        Map<String, BlockState> replacements = Map.ofEntries(
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
        Set<String> remove = Set.of(
                "ocean_ovary", "red_ovary",
                "sea_trail_growing", "sea_trail_init", "sea_trail_grown", "sea_trail_burnt", "sea_trail_stop",
                "trail_debris", "deep_seagrass", "trail_mushroom", "viviparous_lily"
        );
        Set<String> affectedIds = new HashSet<>();
        remove.forEach(path -> affectedIds.add(MOD_ID + ":" + path));
        replacements.keySet().forEach(path -> affectedIds.add(MOD_ID + ":" + path));
        affectedIds.add(MOD_ID + ":trail_log");
        affectedIds.add(MOD_ID + ":stripped_trail_log");
        TerritorySavedData data = TerritorySavedData.get(level);
        data.removeProtectedBlocksInChunk(level, chunk, affectedIds);

        BlockDamageProtection.runUntracked(() -> {
            for (int x = chunk.getMinBlockX(); x < chunk.getMinBlockX() + 16; x++) {
                for (int z = chunk.getMinBlockZ(); z < chunk.getMinBlockZ() + 16; z++) {
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                        if (id == null || !MOD_ID.equals(id.getNamespace())) continue;

                        BlockState restored = replacements.get(id.getPath());
                        if (restored == null && "trail_log".equals(id.getPath())) restored = preserveAxis(state, Blocks.OAK_LOG.defaultBlockState());
                        if (restored == null && "stripped_trail_log".equals(id.getPath())) restored = preserveAxis(state, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
                        if (restored == null && remove.contains(id.getPath())) restored = Blocks.AIR.defaultBlockState();
                        if (restored == null) continue;

                        level.setBlock(pos, restored, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        data.removeProtectedBlock(level, pos);
                    }
                }
            }
        });
    }

    private static BlockState preserveAxis(BlockState source, BlockState target) {
        return source.hasProperty(BlockStateProperties.AXIS)
                ? target.setValue(BlockStateProperties.AXIS, source.getValue(BlockStateProperties.AXIS))
                : target;
    }
    static void convert(ServerLevel level, ChunkPos chunk, String namespace, Set<String> remove, Set<String> cobble) {
        if (!level.hasChunk(chunk.x, chunk.z)) return;
        Set<String> affectedIds = new HashSet<>();
        remove.forEach(path -> affectedIds.add(namespace + ":" + path));
        cobble.forEach(path -> affectedIds.add(namespace + ":" + path));
        TerritorySavedData.get(level).removeProtectedBlocksInChunk(level, chunk, affectedIds);
        BlockDamageProtection.runUntracked(() -> { for (int x=chunk.getMinBlockX();x<chunk.getMinBlockX()+16;x++) for(int z=chunk.getMinBlockZ();z<chunk.getMinBlockZ()+16;z++) for(int y=level.getMinBuildHeight();y<level.getMaxBuildHeight();y++){ BlockPos p=new BlockPos(x,y,z); ResourceLocation id=ForgeRegistries.BLOCKS.getKey(level.getBlockState(p).getBlock()); if(id==null || !namespace.equals(id.getNamespace()))continue; if(remove.contains(id.getPath())) level.setBlock(p,Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); else if(cobble.contains(id.getPath())) level.setBlock(p,Blocks.COBBLESTONE.defaultBlockState(),Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); }});
    }
}
