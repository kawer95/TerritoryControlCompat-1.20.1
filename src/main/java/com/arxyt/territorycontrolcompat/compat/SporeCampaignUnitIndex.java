package com.arxyt.territorycontrolcompat.compat;

import com.Harbinger.Spore.Sentities.BaseEntities.Calamity;
import com.Harbinger.Spore.Sentities.BaseEntities.Infected;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Event-driven main-thread index for Spore infected and Calamity campaign candidates. */
public final class SporeCampaignUnitIndex {
    private static final Map<ServerLevel, Map<UUID, Infected>> INFECTED = new IdentityHashMap<>();
    private static final Map<ServerLevel, Map<UUID, Calamity>> CALAMITIES = new IdentityHashMap<>();

    public SporeCampaignUnitIndex() { }

    static List<CampaignStrategicPlanner.Unit> infectedSnapshot(ServerLevel level, Set<Long> friendlyChunks, Set<UUID> assigned) {
        return infectedSnapshotWithStats(level, friendlyChunks, assigned).units();
    }

    static Snapshot infectedSnapshotWithStats(ServerLevel level, Set<Long> friendlyChunks, Set<UUID> assigned) {
        List<CampaignStrategicPlanner.Unit> result = new ArrayList<>();
        int scanned = 0, dead = 0, assignedCount = 0, targeted = 0, unalignedTargetIgnored = 0,
                combat = 0, combatPlayer = 0, combatHostileFaction = 0, reserved = 0, deployment = 0, outsideFriendly = 0;
        for (Infected entity : INFECTED.getOrDefault(level, Map.of()).values()) {
            scanned++;
            UUID id = entity.getUUID();
            if (!entity.isAlive() || entity.isRemoved()) { dead++; continue; }
            if (assigned.contains(id)) { assignedCount++; continue; }
            boolean blockingTarget = CampaignCombatTracker.hasBlockingTarget(level, entity);
            if (entity.getTarget() != null && !blockingTarget) unalignedTargetIgnored++;
            if (blockingTarget) targeted++;
            CampaignCombatTracker.CombatReason combatReason = CampaignCombatTracker.recentReason(level, id);
            boolean recentCombat = combatReason != CampaignCombatTracker.CombatReason.NONE;
            if (recentCombat) combat++;
            if (combatReason == CampaignCombatTracker.CombatReason.PLAYER) combatPlayer++;
            if (combatReason == CampaignCombatTracker.CombatReason.HOSTILE_FACTION) combatHostileFaction++;
            if (CampaignUnitReservations.reserved(id)) { reserved++; continue; }
            if (CampaignDeploymentSavedData.get(level).containsMember(id)) { deployment++; continue; }
            if (!friendlyChunks.contains(entity.chunkPosition().toLong())) outsideFriendly++;
            result.add(snapshot(entity, blockingTarget, recentCombat));
        }
        return new Snapshot(List.copyOf(result), new CampaignStrategicPlanner.SourceStats(scanned, 0, dead,
                assignedCount, targeted, unalignedTargetIgnored, combat, reserved, deployment,
                outsideFriendly, result.size(), combatPlayer, combatHostileFaction));
    }

    static List<CampaignStrategicPlanner.Unit> calamitySnapshot(ServerLevel level, Set<Long> friendlyChunks, Set<UUID> assigned) {
        return calamitySnapshotWithStats(level, friendlyChunks, assigned).units();
    }

    static Snapshot calamitySnapshotWithStats(ServerLevel level, Set<Long> friendlyChunks, Set<UUID> assigned) {
        List<CampaignStrategicPlanner.Unit> result = new ArrayList<>();
        int scanned = 0, dead = 0, assignedCount = 0, targeted = 0, unalignedTargetIgnored = 0,
                combat = 0, combatPlayer = 0, combatHostileFaction = 0, reserved = 0, deployment = 0, outsideFriendly = 0;
        for (Calamity entity : CALAMITIES.getOrDefault(level, Map.of()).values()) {
            scanned++;
            UUID id = entity.getUUID();
            if (!entity.isAlive() || entity.isRemoved()) { dead++; continue; }
            if (assigned.contains(id) || !entity.getSearchArea().equals(BlockPos.ZERO)) { assignedCount++; continue; }
            boolean blockingTarget = CampaignCombatTracker.hasBlockingTarget(level, entity);
            if (entity.getTarget() != null && !blockingTarget) unalignedTargetIgnored++;
            if (blockingTarget) targeted++;
            CampaignCombatTracker.CombatReason combatReason = CampaignCombatTracker.recentReason(level, id);
            boolean recentCombat = combatReason != CampaignCombatTracker.CombatReason.NONE;
            if (recentCombat) combat++;
            if (combatReason == CampaignCombatTracker.CombatReason.PLAYER) combatPlayer++;
            if (combatReason == CampaignCombatTracker.CombatReason.HOSTILE_FACTION) combatHostileFaction++;
            if (CampaignUnitReservations.reserved(id)) { reserved++; continue; }
            if (CampaignDeploymentSavedData.get(level).containsMember(id)) { deployment++; continue; }
            if (!friendlyChunks.contains(entity.chunkPosition().toLong())) outsideFriendly++;
            result.add(snapshot(entity, blockingTarget, recentCombat));
        }
        return new Snapshot(List.copyOf(result), new CampaignStrategicPlanner.SourceStats(scanned, 0, dead,
                assignedCount, targeted, unalignedTargetIgnored, combat, reserved, deployment,
                outsideFriendly, result.size(), combatPlayer, combatHostileFaction));
    }

    static List<Infected> resolveInfected(ServerLevel level, List<UUID> ids, Set<UUID> assigned) {
        List<Infected> result = new ArrayList<>();
        Map<UUID, Infected> entities = INFECTED.getOrDefault(level, Map.of());
        for (UUID id : ids) {
            Infected entity = entities.get(id);
            if (entity != null && entity.isAlive() && !entity.isRemoved() && !assigned.contains(id)) result.add(entity);
        }
        return result;
    }

    static List<Calamity> resolveCalamities(ServerLevel level, List<UUID> ids, Set<UUID> assigned) {
        List<Calamity> result = new ArrayList<>();
        Map<UUID, Calamity> entities = CALAMITIES.getOrDefault(level, Map.of());
        for (UUID id : ids) {
            Calamity entity = entities.get(id);
            if (entity != null && entity.isAlive() && !entity.isRemoved() && !assigned.contains(id)
                    && entity.getSearchArea().equals(BlockPos.ZERO)) result.add(entity);
        }
        return result;
    }

    private static CampaignStrategicPlanner.Unit snapshot(Entity entity, boolean blockingTarget, boolean recentCombat) {
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
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof Calamity calamity) {
            CALAMITIES.computeIfAbsent(level, ignored -> new HashMap<>()).put(calamity.getUUID(), calamity);
        } else if (event.getEntity() instanceof Infected infected) {
            INFECTED.computeIfAbsent(level, ignored -> new HashMap<>()).put(infected.getUUID(), infected);
        }
    }

    @SubscribeEvent
    public void onLeave(EntityLeaveLevelEvent event) {
        remove(event.getLevel() instanceof ServerLevel level ? level : null, event.getEntity());
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        remove(event.getEntity().level() instanceof ServerLevel level ? level : null, event.getEntity());
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            INFECTED.remove(level);
            CALAMITIES.remove(level);
        }
    }

    private static void remove(ServerLevel level, Entity entity) {
        if (level == null) return;
        Map<UUID, Infected> infected = INFECTED.get(level);
        Map<UUID, Calamity> calamities = CALAMITIES.get(level);
        if (infected != null) infected.remove(entity.getUUID());
        if (calamities != null) calamities.remove(entity.getUUID());
    }
}
