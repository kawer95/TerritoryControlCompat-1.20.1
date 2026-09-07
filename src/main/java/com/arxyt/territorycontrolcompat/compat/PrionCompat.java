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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/** Territory rules for the terrain and temporary biological blocks created by Prion. */
public final class PrionCompat {
    public static final String MOD_ID = "prionmod";

    private static final Set<String> TERRITORY_BLOCKS = Set.of(
            "root_block", "living_block", "nox_block", "entrails_block", "eggs_block");

    private PrionCompat() {
    }

    public static boolean isTerritoryBlock(BlockState state) {
        return isTerritoryBlockId(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
    }

    static boolean isTerritoryBlockId(ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace()) && TERRITORY_BLOCKS.contains(id.getPath());
    }

    static boolean isEggsBlockId(ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace()) && "eggs_block".equals(id.getPath());
    }

    public static boolean isEggLifecycleRemoval(BlockState oldState, BlockState newState) {
        return newState.isAir() && isEggsBlockId(ForgeRegistries.BLOCKS.getKey(oldState.getBlock()));
    }

    public static void onOwnershipChanged(ServerLevel level, ChunkPos chunk, Faction previous, Faction current) {
        if (previous == null || !previous.ownsMod(MOD_ID)
                || current != null && current.ownsMod(MOD_ID)
                || !CompatSavedData.get(level).config().purgePrionOnLoss()
                || !level.hasChunk(chunk.x, chunk.z)) {
            return;
        }

        TerritorySavedData data = TerritorySavedData.get(level);
        BlockDamageProtection.runUntracked(() -> {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
                for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        pos.set(x, y, z);
                        if (!isTerritoryBlock(level.getBlockState(pos))) continue;
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        data.removeProtectedBlock(level, pos);
                    }
                }
            }
        });
    }
}
