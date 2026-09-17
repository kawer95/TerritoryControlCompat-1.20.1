package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Candidate-rally threat checks. Snapshot filtering is index-backed; final validation is exact. */
final class CampaignRallySafety {
    static final int RADIUS_BLOCKS = 32;
    private static final double RADIUS_SQR = RADIUS_BLOCKS * RADIUS_BLOCKS;

    private CampaignRallySafety() { }

    static Set<Long> unsafeRallyChunks(ServerLevel level, Set<Long> candidateChunks,
                                       Predicate<String> friendly) {
        if (level == null || candidateChunks == null || candidateChunks.isEmpty()) return Set.of();
        Map<Long, List<TerritoryControlApi.FactionMobView>> enemiesByChunk = new HashMap<>();
        for (TerritoryControlApi.FactionMobView mob : TerritoryControlApi.factionMobSnapshot(level)) {
            if (friendly.test(mob.factionId())) continue;
            int chunkX = Math.floorDiv((int) Math.floor(mob.x()), 16);
            int chunkZ = Math.floorDiv((int) Math.floor(mob.z()), 16);
            enemiesByChunk.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), ignored -> new ArrayList<>()).add(mob);
        }
        List<ServerPlayer> players = level.players().stream()
                .filter(player -> player.isAlive() && !player.isSpectator()).toList();
        Set<Long> unsafe = new HashSet<>();
        for (long chunkKey : candidateChunks) {
            int chunkX = ChunkPos.getX(chunkKey), chunkZ = ChunkPos.getZ(chunkKey);
            BlockPos anchor = CampaignWorldSnapshotCache.safeAnchor(level, chunkX, chunkZ);
            if (anchor == null) continue; // Planner reports terrain-anchor absence separately.
            if (playerWithin(players, anchor) || indexedEnemyWithin(enemiesByChunk, anchor)) unsafe.add(chunkKey);
        }
        return Set.copyOf(unsafe);
    }

    /** Exact, constant-radius recheck immediately before a probe/deployment is committed. */
    static boolean isSafeNow(ServerLevel level, BlockPos anchor, Predicate<String> friendly) {
        if (level == null || anchor == null) return false;
        List<ServerPlayer> players = level.players().stream()
                .filter(player -> player.isAlive() && !player.isSpectator()).toList();
        if (playerWithin(players, anchor)) return false;
        AABB area = new AABB(anchor.getX() - RADIUS_BLOCKS, level.getMinBuildHeight(), anchor.getZ() - RADIUS_BLOCKS,
                anchor.getX() + RADIUS_BLOCKS, level.getMaxBuildHeight(), anchor.getZ() + RADIUS_BLOCKS);
        return level.getEntitiesOfClass(Mob.class, area, Mob::isAlive).stream().noneMatch(mob -> {
            if (horizontalDistanceSqr(mob.getX(), mob.getZ(), anchor) > RADIUS_SQR) return false;
            return TerritoryControlApi.factionIdForEntity(level, mob)
                    .filter(faction -> !friendly.test(faction)).isPresent();
        });
    }

    private static boolean indexedEnemyWithin(Map<Long, List<TerritoryControlApi.FactionMobView>> enemiesByChunk,
                                              BlockPos anchor) {
        int minChunkX = Math.floorDiv(anchor.getX() - RADIUS_BLOCKS, 16);
        int maxChunkX = Math.floorDiv(anchor.getX() + RADIUS_BLOCKS, 16);
        int minChunkZ = Math.floorDiv(anchor.getZ() - RADIUS_BLOCKS, 16);
        int maxChunkZ = Math.floorDiv(anchor.getZ() + RADIUS_BLOCKS, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (TerritoryControlApi.FactionMobView mob : enemiesByChunk.getOrDefault(ChunkPos.asLong(chunkX, chunkZ), List.of())) {
                    if (horizontalDistanceSqr(mob.x(), mob.z(), anchor) <= RADIUS_SQR) return true;
                }
            }
        }
        return false;
    }

    private static boolean playerWithin(List<ServerPlayer> players, BlockPos anchor) {
        return players.stream().anyMatch(player -> horizontalDistanceSqr(player.getX(), player.getZ(), anchor) <= RADIUS_SQR);
    }

    private static double horizontalDistanceSqr(double x, double z, BlockPos anchor) {
        double dx = x - (anchor.getX() + 0.5D), dz = z - (anchor.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }
}
