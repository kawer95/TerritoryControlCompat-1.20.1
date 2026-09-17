package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Constant-range server-thread validation immediately before probing and deployment. */
final class CampaignPlanValidation {
    private CampaignPlanValidation() { }

    static boolean valid(ServerLevel level, String controller, CampaignStrategicPlanner.Option option,
                         List<? extends Entity> units) {
        return validate(level, controller, option, units) == Result.VALID;
    }

    /** Returns one bounded, coordinate-free reason for a main-thread plan recheck rejection. */
    static Result validate(ServerLevel level, String controller, CampaignStrategicPlanner.Option option,
                           List<? extends Entity> units) {
        if (level == null) return Result.LEVEL_MISSING;
        if (controller == null || controller.isBlank()) return Result.CONTROLLER_MISSING;
        if (option == null) return Result.OPTION_MISSING;
        if (units == null || units.isEmpty()) return Result.NO_UNITS;
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level).warzoneConfig().normalized().sizeChunks();
        Map<String, Boolean> relations = new HashMap<>();
        Predicate<String> friendly = faction -> faction != null && !faction.isBlank()
                && relations.computeIfAbsent(faction, value -> controller.equals(value)
                || TerritoryControlApi.areFactionsSameOrAllied(level, controller, value));
        CampaignStrategicPlanner.Candidate candidate = option.candidate();
        CampaignTerritoryConditions.TargetState targetState = CampaignTerritoryConditions.targetState(
                level, controller, candidate.target());
        if (targetState == CampaignTerritoryConditions.TargetState.FULLY_FRIENDLY) {
            return Result.TARGET_WARZONE_FULLY_FRIENDLY;
        }
        if (targetState == CampaignTerritoryConditions.TargetState.NO_REACHABLE_OBJECTIVES) {
            return Result.TARGET_NO_REACHABLE_OBJECTIVES;
        }
        if (!CampaignTerritoryConditions.rallyChunkOwnedByFriendlyBloc(level, controller, candidate.stagingChunk())) {
            return Result.RALLY_OWNER_LOST;
        }
        if (!CampaignRallySafety.isSafeNow(level, new net.minecraft.core.BlockPos(
                candidate.stagingAnchor().x(), candidate.stagingAnchor().y(), candidate.stagingAnchor().z()), friendly)) return Result.RALLY_SAFETY_BLOCKED;
        Set<CampaignStrategicPlanner.ZoneKey> unsafe = unsafePresence(level, friendly);
        for (Entity unit : units) {
            boolean inPlace = CampaignStrategicPlanner.inPlace(candidate, size,
                    unit.getBlockX() >> 4, unit.getBlockZ() >> 4);
            boolean blocked = !inPlace && (unit instanceof Mob mob
                    ? CampaignCombatTracker.blocksMobilization(level, mob)
                    : CampaignCombatTracker.recentlyInCombat(level, unit.getUUID()));
            if (!unit.isAlive() || unit.isRemoved()) return Result.UNIT_UNAVAILABLE;
            if (blocked) return Result.UNIT_BUSY;
            if (inPlace) continue;
            CampaignStrategicPlanner.ZoneKey source = CampaignStrategicPlanner.zone(unit.getBlockX() >> 4, unit.getBlockZ() >> 4, size);
            if (unsafe.contains(source)) return Result.SOURCE_ENEMY_PRESENCE;
        }
        return Result.VALID;
    }

    private static Set<CampaignStrategicPlanner.ZoneKey> unsafePresence(ServerLevel level, Predicate<String> friendly) {
        Set<CampaignStrategicPlanner.ZoneKey> result = new HashSet<>();
        for (TerritoryControlApi.WarzonePresenceView presence : TerritoryControlApi.warzonePresenceSnapshot(level)) {
            if (presence.factionCounts().keySet().stream().anyMatch(faction -> !friendly.test(faction))) {
                result.add(new CampaignStrategicPlanner.ZoneKey(presence.zoneX(), presence.zoneZ()));
            }
        }
        return result;
    }

    enum Result {
        VALID,
        LEVEL_MISSING,
        CONTROLLER_MISSING,
        OPTION_MISSING,
        NO_UNITS,
        TARGET_WARZONE_FULLY_FRIENDLY,
        TARGET_NO_REACHABLE_OBJECTIVES,
        RALLY_OWNER_LOST,
        RALLY_SAFETY_BLOCKED,
        UNIT_UNAVAILABLE,
        UNIT_BUSY,
        SOURCE_ENEMY_PRESENCE
    }
}
