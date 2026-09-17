package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratnations.entity.AbstractMouseSoldierEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Event-driven main-thread index for Rat Nations campaign candidates. */
public final class RatCampaignUnitIndex {
    private static final Map<ServerLevel, Map<UUID, AbstractMouseSoldierEntity>> LEVELS = new IdentityHashMap<>();

    public RatCampaignUnitIndex() { }

    static List<CampaignStrategicPlanner.Unit> snapshot(ServerLevel level, ResourceLocation nation, Set<Long> friendlyChunks) {
        return snapshotWithStats(level, nation, friendlyChunks).units();
    }

    static Snapshot snapshotWithStats(ServerLevel level, ResourceLocation nation, Set<Long> friendlyChunks) {
        List<CampaignStrategicPlanner.Unit> result = new ArrayList<>();
        Map<UUID, AbstractMouseSoldierEntity> units = LEVELS.getOrDefault(level, Map.of());
        int scanned = 0, wrongFaction = 0, dead = 0, assigned = 0, targeted = 0, unalignedTargetIgnored = 0,
                combat = 0, combatPlayer = 0, combatHostileFaction = 0, reserved = 0, deployment = 0, outsideFriendly = 0;
        for (AbstractMouseSoldierEntity soldier : units.values()) {
            scanned++;
            UUID id = soldier.getUUID();
            if (!nation.equals(soldier.ratNationsFactionId())) { wrongFaction++; continue; }
            if (!soldier.isAlive() || soldier.isRemoved()) { dead++; continue; }
            if (soldier.isCampaignAssigned()) { assigned++; continue; }
            boolean blockingTarget = CampaignCombatTracker.hasBlockingTarget(level, soldier);
            if (soldier.getTarget() != null && !blockingTarget) unalignedTargetIgnored++;
            if (blockingTarget) targeted++;
            CampaignCombatTracker.CombatReason combatReason = CampaignCombatTracker.recentReason(level, id);
            boolean recentCombat = combatReason != CampaignCombatTracker.CombatReason.NONE;
            if (recentCombat) combat++;
            if (combatReason == CampaignCombatTracker.CombatReason.PLAYER) combatPlayer++;
            if (combatReason == CampaignCombatTracker.CombatReason.HOSTILE_FACTION) combatHostileFaction++;
            if (CampaignUnitReservations.reserved(id)) { reserved++; continue; }
            if (CampaignDeploymentSavedData.get(level).containsMember(id)) { deployment++; continue; }
            if (!friendlyChunks.contains(soldier.chunkPosition().toLong())) outsideFriendly++;
            result.add(snapshot(soldier, blockingTarget, recentCombat));
        }
        return new Snapshot(List.copyOf(result), new CampaignStrategicPlanner.SourceStats(scanned, wrongFaction, dead,
                assigned, targeted, unalignedTargetIgnored, combat, reserved, deployment,
                outsideFriendly, result.size(), combatPlayer, combatHostileFaction));
    }

    static List<AbstractMouseSoldierEntity> resolve(ServerLevel level, List<UUID> ids, ResourceLocation nation) {
        Map<UUID, AbstractMouseSoldierEntity> units = LEVELS.getOrDefault(level, Map.of());
        List<AbstractMouseSoldierEntity> result = new ArrayList<>();
        for (UUID id : ids) {
            AbstractMouseSoldierEntity soldier = units.get(id);
            if (soldier != null && soldier.isAlive() && !soldier.isRemoved()
                    && nation.equals(soldier.ratNationsFactionId()) && !soldier.isCampaignAssigned()) result.add(soldier);
        }
        return result;
    }

    private static CampaignStrategicPlanner.Unit snapshot(AbstractMouseSoldierEntity entity,
                                                          boolean blockingTarget, boolean recentCombat) {
        return new CampaignStrategicPlanner.Unit(entity.getUUID(), entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(),
                entity.getBbWidth(), entity.getBbHeight(), blockingTarget, recentCombat);
    }

    record Snapshot(List<CampaignStrategicPlanner.Unit> units, CampaignStrategicPlanner.SourceStats stats) {
        Snapshot {
            units = List.copyOf(units);
            stats = stats == null ? CampaignStrategicPlanner.SourceStats.empty() : stats;
        }
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof AbstractMouseSoldierEntity soldier) {
            LEVELS.computeIfAbsent(level, ignored -> new HashMap<>()).put(soldier.getUUID(), soldier);
        }
    }

    @SubscribeEvent
    public void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof AbstractMouseSoldierEntity soldier) {
            Map<UUID, AbstractMouseSoldierEntity> units = LEVELS.get(level);
            if (units != null) units.remove(soldier.getUUID());
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level && event.getEntity() instanceof AbstractMouseSoldierEntity soldier) {
            Map<UUID, AbstractMouseSoldierEntity> units = LEVELS.get(level);
            if (units != null) units.remove(soldier.getUUID());
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
    }
}
