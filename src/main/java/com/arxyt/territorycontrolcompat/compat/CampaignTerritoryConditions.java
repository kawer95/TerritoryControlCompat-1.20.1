package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import net.minecraft.server.level.ServerLevel;

/** Shared ownership-only campaign conditions for rat and spore directors. */
final class CampaignTerritoryConditions {
    private CampaignTerritoryConditions() { }

    static boolean targetWarzoneFullyFriendly(ServerLevel level, String controller,
                                               CampaignStrategicPlanner.ZoneKey target) {
        return targetState(level, controller, target) == TargetState.FULLY_FRIENDLY;
    }

    static TargetState targetState(ServerLevel level, String controller,
                                   CampaignStrategicPlanner.ZoneKey target) {
        if (level == null || controller == null || controller.isBlank() || target == null) return TargetState.ACTIVE;
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level)
                .warzoneConfig().normalized().sizeChunks();
        boolean hasNonFriendly = false;
        boolean allNonFriendlyTerrainKnown = true;
        for (int x = target.x() * size; x < target.x() * size + size; x++) {
            for (int z = target.z() * size; z < target.z() * size + size; z++) {
                TerritoryControlApi.TerritoryView view = CampaignWorldSnapshotCache.territory(level, x, z);
                if (view != null && friendlyBloc(level, controller, view.ownerFaction())) continue;
                hasNonFriendly = true;
                if (!CampaignWorldSnapshotCache.hasTerrainSnapshot(level, x, z)) {
                    allNonFriendlyTerrainKnown = false;
                } else if (CampaignWorldSnapshotCache.safeAnchor(level, x, z) != null) {
                    return TargetState.ACTIVE;
                }
            }
        }
        if (!hasNonFriendly) return TargetState.FULLY_FRIENDLY;
        return allNonFriendlyTerrainKnown ? TargetState.NO_REACHABLE_OBJECTIVES : TargetState.ACTIVE;
    }

    static boolean rallyChunkOwnedByFriendlyBloc(ServerLevel level, String controller,
                                                  CampaignStrategicPlanner.ChunkKey rally) {
        if (level == null || controller == null || controller.isBlank() || rally == null) return false;
        TerritoryControlApi.TerritoryView view = CampaignWorldSnapshotCache.territory(level, rally.x(), rally.z());
        return view != null && friendlyBloc(level, controller, view.ownerFaction());
    }

    static boolean chunkOwnedByFriendlyBloc(ServerLevel level, String controller, int chunkX, int chunkZ) {
        TerritoryControlApi.TerritoryView view = CampaignWorldSnapshotCache.territory(level, chunkX, chunkZ);
        return view != null && friendlyBloc(level, controller, view.ownerFaction());
    }

    static boolean friendlyBloc(ServerLevel level, String controller, String faction) {
        return faction != null && !faction.isBlank() && (controller.equals(faction)
                || TerritoryControlApi.areFactionsSameOrAllied(level, controller, faction));
    }

    enum TargetState { ACTIVE, FULLY_FRIENDLY, NO_REACHABLE_OBJECTIVES }
}
