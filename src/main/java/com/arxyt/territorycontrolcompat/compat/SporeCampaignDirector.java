package com.arxyt.territorycontrolcompat.compat;

import com.Harbinger.Spore.Core.SConfig;
import com.Harbinger.Spore.Core.Sentities;
import com.Harbinger.Spore.Sentities.BaseEntities.Calamity;
import com.Harbinger.Spore.Sentities.BaseEntities.Infected;
import com.Harbinger.Spore.Sentities.Utility.ScentEntity;
import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrol.core.BattleModes;
import com.arxyt.territorycontrol.core.data.Warzone;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Drives territory-aware Spore offensives using the mod's own linked-hive, Scent, and Calamity AI. */
public final class SporeCampaignDirector {
    public static final String MOD_ID = "spore";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int REGULAR_MIN = 8;
    private static final int REGULAR_MAX = 16;
    private static final int GRAND_MIN = 12;
    private static final int GRAND_MAX = 20;
    private static final long TICK_INTERVAL = 20L;
    private static final long PLAN_RETRY_TICKS = 1_200L;
    private static final long MUSTER_TIMEOUT_TICKS = 1_200L;
    private static final long STABILIZE_TICKS = 600L;
    private static final long MOUND_TIMEOUT_TICKS = 1_200L;
    private static final Map<SporeCampaignSavedData.Key, Long> NEXT_PLAN_ATTEMPT = new HashMap<>();
    private static final Map<CampaignPlanningService.JobKey, ProbeSequence> PROBE_SEQUENCES = new HashMap<>();
    private static long planningAttempts, candidateWarzones, pathProbes, campaignsStarted, campaignsCompleted, campaignsFailed, pauses;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % TICK_INTERVAL != 0L) return;
        MinecraftServer server = event.getServer();
        CompatSavedData.Config config = CompatSavedData.get(server.overworld()).config();
        if (!config.sporeRegularCampaigns() && !config.sporeGrandCampaigns()) {
            CampaignPlanningService.cancelDirector("spore");
            CampaignRouteProbeService.cancelDirector("spore");
            CampaignRallyPlacementService.cancelDirector("spore");
            PROBE_SEQUENCES.clear();
            for (ServerLevel level : server.getAllLevels()) { abortAll(level); CampaignDeploymentService.rollback(level, "SPORE_"); }
            return;
        }
        if (BattleModes.isLayoutMode(server) || BattleModes.isCleanupMode(server)) {
            PROBE_SEQUENCES.clear();
            for (ServerLevel level : server.getAllLevels()) {
                CampaignPlanningService.cancelDimension(level.dimension().location().toString());
                CampaignRouteProbeService.cancelDimension(level.dimension().location().toString());
                CampaignRallyPlacementService.cancelDimension(level.dimension().location().toString());
                CampaignDeploymentService.rollback(level, "");
            }
            return;
        }
        for (ServerLevel level : server.getAllLevels()) tickLevel(level, config);
    }

    private static void tickLevel(ServerLevel level, CompatSavedData.Config config) {
        List<ServerPlayer> players = level.players().stream().filter(player -> player.isAlive() && !player.isSpectator()).toList();
        SporeCampaignSavedData data = SporeCampaignSavedData.get(level);
        if (!config.sporeRegularCampaigns()) abort(level, SporeCampaignSavedData.Type.REGULAR, false, "常规战役已关闭");
        if (!config.sporeGrandCampaigns()) abort(level, SporeCampaignSavedData.Type.GRAND, false, "大型远征已关闭");
        if (players.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        CampaignWorldSnapshotCache.PlanningSnapshot snapshot = CampaignWorldSnapshotCache.planningSnapshot(level);
        for (SporeCampaignSavedData.Campaign campaign : data.campaigns(level)) tickCampaign(level, players, data, campaign, now, config);
        if (snapshot == null) return;
        if (config.sporeRegularCampaigns()) prepareAsync(level, data, SporeCampaignSavedData.Type.REGULAR, now, snapshot);
        if (config.sporeGrandCampaigns()) prepareAsync(level, data, SporeCampaignSavedData.Type.GRAND, now, snapshot);
    }

    private static void tickCampaign(ServerLevel level, List<ServerPlayer> players,
                                     SporeCampaignSavedData data, SporeCampaignSavedData.Campaign campaign,
                                     long now, CompatSavedData.Config config) {
        List<Infected> members = members(level, campaign);
        List<Calamity> calamities = calamities(level, campaign);
        if (!isOperational(level, players, campaign)) {
            if (!campaign.paused()) {
                campaign.setPaused(true);
                pauses++;
                data.markChanged();
                logCampaign(level, campaign, "PAUSED", "战区或目标区块不在玩家加载范围内，或集结点失效");
            }
            return;
        }
        if (campaign.paused()) {
            campaign.setPaused(false);
            data.markChanged();
            logCampaign(level, campaign, "RESUMED", "战区和目标区块重新可用");
        }
        if (members.isEmpty()) {
            finish(level, data, campaign, members, calamities, now, false, "战役成员全部失联或阵亡");
            return;
        }
        if (campaign.phase() == SporeCampaignSavedData.Phase.RETREAT) {
            SporeCampaignSavedData.Phase resumed = members.stream().anyMatch(member -> campaign.contains(member.chunkPosition()))
                    ? SporeCampaignSavedData.Phase.OCCUPY : SporeCampaignSavedData.Phase.ADVANCE;
            transition(level, campaign, resumed, now, "撤退功能已关闭，恢复进攻");
            data.markChanged();
        }

        switch (campaign.phase()) {
            case MUSTER -> {
                if (now >= campaign.launchAt() && targetState(level, campaign) != CampaignTerritoryConditions.TargetState.ACTIVE) {
                    finish(level, data, campaign, members, calamities, now, true,
                            "目标战区已完成或剩余区块不可达");
                    return;
                }
                if (now >= campaign.launchAt() && mustered(campaign, members)) {
                    transition(level, campaign, SporeCampaignSavedData.Phase.ADVANCE, now, "至少 75% 护卫抵达集结点");
                }
                else if (now >= campaign.launchAt() && now - Math.max(campaign.phaseSince(), campaign.launchAt()) > MUSTER_TIMEOUT_TICKS) {
                    transition(level, campaign, SporeCampaignSavedData.Phase.ADVANCE, now,
                            "撤退已关闭，集结超时后直接进攻");
                }
            }
            case ADVANCE -> {
                if (members.stream().filter(member -> campaign.contains(member.chunkPosition())).count() * 2 >= members.size()) {
                    transition(level, campaign, SporeCampaignSavedData.Phase.OCCUPY, now, "至少半数护卫进入目标战区");
                }
            }
            case OCCUPY -> {
                List<Objective> hostile = hostileObjectives(level, campaign);
                if (hostile.isEmpty() && targetState(level, campaign) != CampaignTerritoryConditions.TargetState.ACTIVE) {
                    transition(level, campaign, SporeCampaignSavedData.Phase.STABILIZE, now,
                            "目标已清空或剩余区块不可达");
                }
                else if (!hostile.isEmpty()) {
                    List<BlockPos> objectives = hostile.stream().map(Objective::anchor).distinct()
                            .limit(campaign.members().size()).toList();
                    Set<ChunkPos> hostileChunks = hostile.stream().map(Objective::chunk).collect(java.util.stream.Collectors.toSet());
                    long currentDistinct = campaign.members().stream().map(SporeCampaignSavedData.InfectedMember::objective)
                            .distinct().count();
                    boolean invalidObjective = campaign.members().stream()
                            .anyMatch(member -> !hostileChunks.contains(new ChunkPos(member.objective())));
                    if (!objectives.isEmpty() && (invalidObjective
                            || currentDistinct < Math.min(campaign.members().size(), objectives.size()))) {
                        campaign.assignObjectives(objectives);
                    }
                }
            }
            case STABILIZE -> {
                if (targetState(level, campaign) == CampaignTerritoryConditions.TargetState.ACTIVE) {
                    transition(level, campaign, SporeCampaignSavedData.Phase.OCCUPY, now, "出现新的可达目标");
                }
                else if (now - campaign.phaseSince() >= STABILIZE_TICKS) {
                    if (campaign.type() == SporeCampaignSavedData.Type.GRAND && config.sporeCampaignMoundEstablishment()
                            && !calamities.isEmpty()) {
                        BlockPos mound = conqueredMoundAnchor(level, campaign);
                        if (mound != null) {
                            campaign.setMoundAnchor(mound);
                            transition(level, campaign, SporeCampaignSavedData.Phase.ESTABLISH_MOUND, now,
                                    "战区稳定，菌丘候选=" + mound);
                        }
                        else { finish(level, data, campaign, members, calamities, now, true, "战区已稳固"); return; }
                    } else { finish(level, data, campaign, members, calamities, now, true, "战区已稳固"); return; }
                }
            }
            case ESTABLISH_MOUND -> {
                requestMoundLanding(level, campaign, calamities);
                boolean requested = campaign.calamities().stream().allMatch(SporeCampaignSavedData.CalamityMember::moundRequested);
                boolean complete = requested && calamities.stream().allMatch(calamity -> calamity.getSearchArea().equals(BlockPos.ZERO));
                if (complete || now - campaign.phaseSince() >= MOUND_TIMEOUT_TICKS) {
                    finish(level, data, campaign, members, calamities, now, true, complete ? "战区已稳固并建立菌丘" : "战区已稳固");
                    return;
                }
            }
            case RETREAT -> {
                // Legacy saves are migrated above before entering the phase switch.
            }
        }
        applyDirectives(campaign, members, calamities);
        data.markChanged();
    }

    private static void prepareAsync(ServerLevel level, SporeCampaignSavedData data, SporeCampaignSavedData.Type type,
                                     long now, CampaignWorldSnapshotCache.PlanningSnapshot snapshot) {
        if (data.campaign(level, type) != null || CampaignDeploymentSavedData.get(level).hasScope("SPORE_" + type, type.name())) return;
        SporeCampaignSavedData.Key key = SporeCampaignSavedData.Key.of(level, type);
        CampaignPlanningService.JobKey jobKey = new CampaignPlanningService.JobKey(
                level.dimension().location().toString(), "spore", type.name());
        if (PROBE_SEQUENCES.containsKey(jobKey)) return;
        CampaignPlanningService.Completion completion = CampaignPlanningService.poll(jobKey, snapshot.generation());
        if (completion != null) {
            completeBackgroundPlan(level, data, type, key, jobKey, completion);
            return;
        }
        if (now < data.cooldownUntil(level, type) || now < NEXT_PLAN_ATTEMPT.getOrDefault(key, 0L)) return;
        CampaignStrategicPlanner.Request request = planningRequest(level, data, type, snapshot);
        if (request == null) {
            NEXT_PLAN_ATTEMPT.put(key, now + PLAN_RETRY_TICKS);
            return;
        }
        if (CampaignPlanningService.submit(jobKey, snapshot.generation(), request)) {
            planningAttempts++;
            NEXT_PLAN_ATTEMPT.put(key, now + PLAN_RETRY_TICKS);
        }
    }

    private static CampaignStrategicPlanner.Request planningRequest(ServerLevel level, SporeCampaignSavedData data,
                                                                     SporeCampaignSavedData.Type type,
                                                                     CampaignWorldSnapshotCache.PlanningSnapshot snapshot) {
        String controller = TerritoryControlApi.factionIdForMod(level, MOD_ID).orElse("");
        if (controller.isBlank()) {
            CampaignFailureLog.record("SporeCampaign", "selection result=FAILURE type=" + type + " dimension="
                    + level.dimension().location() + " rejectedCandidates=0 reason=NO_FACTION_BINDING");
            return null;
        }
        Map<String, Boolean> relations = new HashMap<>();
        java.util.function.Predicate<String> friendly = faction -> faction != null && !faction.isBlank()
                && relations.computeIfAbsent(faction, value -> controller.equals(value)
                || TerritoryControlApi.areFactionsSameOrAllied(level, controller, value));
        List<CampaignStrategicPlanner.TerritoryCell> cells = new ArrayList<>();
        Set<Long> friendlyChunks = new HashSet<>();
        for (Map.Entry<Long, TerritoryControlApi.TerritoryView> entry : snapshot.territories().entrySet()) {
            TerritoryControlApi.TerritoryView view = entry.getValue();
            boolean neutral = view.ownerFaction().isBlank() && view.progressFaction().isBlank() && view.contestFaction().isBlank();
            boolean stableFriendly = friendly.test(view.ownerFaction());
            boolean hostile = (!view.ownerFaction().isBlank() && !friendly.test(view.ownerFaction()))
                    || (!view.progressFaction().isBlank() && !friendly.test(view.progressFaction()))
                    || (!view.contestFaction().isBlank() && !friendly.test(view.contestFaction()));
            int chunkX = ChunkPos.getX(entry.getKey()), chunkZ = ChunkPos.getZ(entry.getKey());
            cells.add(new CampaignStrategicPlanner.TerritoryCell(chunkX, chunkZ, stableFriendly, hostile, neutral));
            if (stableFriendly) friendlyChunks.add(entry.getKey());
        }
        Set<CampaignStrategicPlanner.ZoneKey> unsafeZones = new HashSet<>();
        for (TerritoryControlApi.WarzonePresenceView presence : TerritoryControlApi.warzonePresenceSnapshot(level)) {
            if (presence.factionCounts().keySet().stream().anyMatch(faction -> !friendly.test(faction))) {
                unsafeZones.add(new CampaignStrategicPlanner.ZoneKey(presence.zoneX(), presence.zoneZ()));
            }
        }
        Set<UUID> assigned = assignedIds(level, data);
        SporeCampaignUnitIndex.Snapshot infectedSnapshot = SporeCampaignUnitIndex.infectedSnapshotWithStats(level, friendlyChunks, assigned);
        SporeCampaignUnitIndex.Snapshot calamitySnapshot = type == SporeCampaignSavedData.Type.GRAND
                ? SporeCampaignUnitIndex.calamitySnapshotWithStats(level, friendlyChunks, assigned)
                : new SporeCampaignUnitIndex.Snapshot(List.of(), CampaignStrategicPlanner.SourceStats.empty());
        List<CampaignStrategicPlanner.Unit> units = infectedSnapshot.units();
        List<CampaignStrategicPlanner.Unit> calamities = calamitySnapshot.units();
        int minimum = type == SporeCampaignSavedData.Type.REGULAR ? REGULAR_MIN : GRAND_MIN;
        int maximum = type == SporeCampaignSavedData.Type.REGULAR ? REGULAR_MAX : GRAND_MAX;
        int requiredCalamities = type == SporeCampaignSavedData.Type.GRAND ? (calamities.size() >= 4 ? 2 : 1) : 0;
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level).warzoneConfig().normalized().sizeChunks();
        CampaignWorldSnapshotCache.markFrontierHot(level, CampaignStrategicPlanner.frontierTerrainChunks(cells, size));
        Set<Long> unsafeRallyChunks = CampaignRallySafety.unsafeRallyChunks(level,
                CampaignStrategicPlanner.potentialRallyChunks(cells, size), friendly);
        List<UUID> cooldownAuditIds = new ArrayList<>(units.stream().map(CampaignStrategicPlanner.Unit::id).toList());
        cooldownAuditIds.addAll(calamities.stream().map(CampaignStrategicPlanner.Unit::id).toList());
        String cooldownAudit = CampaignCombatTracker.cooldownAudit(level, cooldownAuditIds);
        candidateWarzones += cells.size();
        return new CampaignStrategicPlanner.Request(type == SporeCampaignSavedData.Type.REGULAR
                ? CampaignStrategicPlanner.Kind.SPORE_REGULAR : CampaignStrategicPlanner.Kind.SPORE_GRAND,
                size, minimum, maximum, requiredCalamities, cells, unsafeZones, units, calamities, snapshot.terrain(),
                unsafeRallyChunks, cooldownAudit, infectedSnapshot.stats(), calamitySnapshot.stats());
    }

    private static void completeBackgroundPlan(ServerLevel level, SporeCampaignSavedData data,
                                               SporeCampaignSavedData.Type type, SporeCampaignSavedData.Key key,
                                               CampaignPlanningService.JobKey jobKey,
                                               CampaignPlanningService.Completion completion) {
        if (completion.expired()) return;
        if (completion.failure() != null || completion.result() == null || !completion.result().success()) {
            String reason = completion.failure() != null ? completion.failure().toString()
                    : completion.result() == null ? "NO_RESULT" : completion.result().reason();
            int rejected = completion.result() == null ? 0 : completion.result().rejectedCandidates();
            String diagnostics = completion.result() == null ? "" : " diagnostics=" + completion.result().diagnostics().compact();
            CampaignFailureLog.record("SporeCampaign", "selection result=FAILURE type=" + type + " dimension="
                    + level.dimension().location() + " rejectedCandidates=" + rejected + " reason=" + reason + diagnostics);
            return;
        }
        ProbeSequence sequence = new ProbeSequence(new java.util.ArrayDeque<>(completion.result().options()),
                completion.result().rejectedCandidates(), completion.result().diagnostics().cooldownAudit());
        PROBE_SEQUENCES.put(jobKey, sequence);
        startNextProbe(level, type, key, jobKey, sequence);
    }

    private static void startNextProbe(ServerLevel level, SporeCampaignSavedData.Type type,
                                       SporeCampaignSavedData.Key key, CampaignPlanningService.JobKey jobKey,
                                       ProbeSequence sequence) {
        CampaignStrategicPlanner.Option option = sequence.options.poll();
        if (option == null) {
            CampaignUnitReservations.release(jobKey);
            PROBE_SEQUENCES.remove(jobKey);
            CampaignFailureLog.record("SporeCampaign", "selection result=FAILURE type=" + type + " dimension="
                    + level.dimension().location() + " rejectedCandidates=" + sequence.rejected
                    + " reason=ALL_ROUTE_PROBES_FAILED probeFailures=" + sequence.failureSummary());
            return;
        }
        List<UUID> reservationIds = new ArrayList<>(option.memberIds());
        reservationIds.addAll(option.calamityIds());
        if (!CampaignUnitReservations.tryReserve(jobKey, reservationIds)) {
            sequence.reject("RESERVATION_FAILED");
            startNextProbe(level, type, key, jobKey, sequence);
            return;
        }
        SporeCampaignSavedData currentData = SporeCampaignSavedData.get(level);
        Set<UUID> initialAssigned = assignedIds(level, currentData);
        List<Infected> initialUnits = SporeCampaignUnitIndex.resolveInfected(level, option.memberIds(), initialAssigned);
        List<Calamity> initialCalamities = SporeCampaignUnitIndex.resolveCalamities(level, option.calamityIds(), initialAssigned);
        String controller = TerritoryControlApi.factionIdForMod(level, MOD_ID).orElse("");
        List<Entity> validationUnits = new ArrayList<>(initialUnits);
        validationUnits.addAll(initialCalamities);
        int minimum = type == SporeCampaignSavedData.Type.REGULAR ? REGULAR_MIN : GRAND_MIN;
        if (initialUnits.size() < minimum) {
            CampaignUnitReservations.release(jobKey);
            sequence.reject("PRE_PROBE_MIN_UNITS");
            startNextProbe(level, type, key, jobKey, sequence);
            return;
        }
        if (initialCalamities.size() < option.calamityIds().size()) {
            CampaignUnitReservations.release(jobKey);
            sequence.reject("PRE_PROBE_CALAMITIES_UNAVAILABLE");
            startNextProbe(level, type, key, jobKey, sequence);
            return;
        }
        CampaignPlanValidation.Result preProbeValidation = CampaignPlanValidation.validate(level, controller, option, validationUnits);
        if (preProbeValidation != CampaignPlanValidation.Result.VALID) {
            CampaignUnitReservations.release(jobKey);
            sequence.reject("PRE_PROBE_" + preProbeValidation);
            startNextProbe(level, type, key, jobKey, sequence);
            return;
        }
        CampaignWorldSnapshotCache.markHot(level, option);
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level)
                .warzoneConfig().normalized().sizeChunks();
        int movers = (int) validationUnits.stream().filter(unit -> !CampaignStrategicPlanner.inPlace(
                option.candidate(), size, unit.getBlockX() >> 4, unit.getBlockZ() >> 4)).count();
        if (!CampaignRallyPlacementService.submit(level, jobKey, controller, option, movers, placement -> {
            if (!placement.success()) {
                CampaignUnitReservations.release(jobKey);
                if (targetResolvedReason(placement.reason())) {
                    sequence.rejectTarget(option.candidate().target(), placement.reason());
                } else {
                    sequence.reject(placement.reason());
                }
                startNextProbe(level, type, key, jobKey, sequence);
                return;
            }
            CampaignPlanValidation.Result placementValidation = CampaignPlanValidation.validate(
                    level, controller, option, validationUnits);
            if (placementValidation != CampaignPlanValidation.Result.VALID) {
                CampaignUnitReservations.release(jobKey);
                if (placementValidation == CampaignPlanValidation.Result.TARGET_WARZONE_FULLY_FRIENDLY
                        || placementValidation == CampaignPlanValidation.Result.TARGET_NO_REACHABLE_OBJECTIVES) {
                    sequence.rejectTarget(option.candidate().target(), "PLACEMENT_" + placementValidation);
                } else {
                    sequence.reject("PLACEMENT_" + placementValidation);
                }
                startNextProbe(level, type, key, jobKey, sequence);
                return;
            }
            if (!CampaignRouteProbeService.submit(level, jobKey, controller, option, placement.positions().get(0), outcome -> {
            if (!outcome.success()) {
                CampaignUnitReservations.release(jobKey);
                if (targetResolvedReason(outcome.reason())) {
                    sequence.rejectTarget(option.candidate().target(), outcome.reason());
                } else {
                    sequence.reject("PROBE_" + outcome.reason());
                }
                startNextProbe(level, type, key, jobKey, sequence);
                return;
            }
            SporeCampaignSavedData campaignData = SporeCampaignSavedData.get(level);
            Set<UUID> assigned = assignedIds(level, campaignData);
            List<Infected> units = SporeCampaignUnitIndex.resolveInfected(level, option.memberIds(), assigned);
            List<Calamity> calamities = SporeCampaignUnitIndex.resolveCalamities(level, option.calamityIds(), assigned);
            List<Entity> currentUnits = new ArrayList<>(units);
            currentUnits.addAll(calamities);
            String currentController = TerritoryControlApi.factionIdForMod(level, MOD_ID).orElse("");
            if (units.size() < minimum) {
                CampaignUnitReservations.release(jobKey);
                sequence.reject("POST_PROBE_MIN_UNITS");
                startNextProbe(level, type, key, jobKey, sequence);
                return;
            }
            if (calamities.size() < option.calamityIds().size()) {
                CampaignUnitReservations.release(jobKey);
                sequence.reject("POST_PROBE_CALAMITIES_UNAVAILABLE");
                startNextProbe(level, type, key, jobKey, sequence);
                return;
            }
            CampaignPlanValidation.Result postProbeValidation = CampaignPlanValidation.validate(
                    level, currentController, option, currentUnits);
            if (postProbeValidation != CampaignPlanValidation.Result.VALID) {
                CampaignUnitReservations.release(jobKey);
                sequence.reject("POST_PROBE_" + postProbeValidation);
                startNextProbe(level, type, key, jobKey, sequence);
                return;
            }
            CampaignDeploymentService.BeginResult deployment = CampaignDeploymentService.begin(
                    level, "SPORE_" + type, type.name(), sequence.rejected, option, units, calamities,
                    placement.positions().get(0),
                    movers == 0 ? List.of() : placement.positions());
            if (deployment != CampaignDeploymentService.BeginResult.STARTED) {
                CampaignUnitReservations.release(jobKey);
                sequence.reject("DEPLOYMENT_" + deployment);
                startNextProbe(level, type, key, jobKey, sequence);
                return;
            }
            PROBE_SEQUENCES.remove(jobKey);
            })) {
                CampaignUnitReservations.release(jobKey);
                sequence.reject("PROBE_QUEUE_REJECTED");
                startNextProbe(level, type, key, jobKey, sequence);
            } else {
                sequence.probesSubmitted++;
            }
        })) {
            CampaignUnitReservations.release(jobKey);
            sequence.reject("RALLY_PLACEMENT_QUEUE_REJECTED");
            startNextProbe(level, type, key, jobKey, sequence);
        }
    }

    static CampaignDeploymentService.FinalizationResult completeDeployment(ServerLevel level, CampaignDeploymentSavedData.Deployment deployment) {
        SporeCampaignSavedData.Type type;
        try { type = SporeCampaignSavedData.Type.valueOf(deployment.scope); } catch (RuntimeException invalid) {
            return CampaignDeploymentService.FinalizationResult.INVALID_SCOPE;
        }
        SporeCampaignSavedData data = SporeCampaignSavedData.get(level);
        if (data.campaign(level, type) != null) return CampaignDeploymentService.FinalizationResult.CAMPAIGN_ALREADY_ACTIVE;
        String controller = TerritoryControlApi.factionIdForMod(level, MOD_ID).orElse("");
        if (controller.isBlank()) return CampaignDeploymentService.FinalizationResult.CONTROLLER_MISSING;
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level)
                .warzoneConfig().normalized().sizeChunks();
        CampaignStrategicPlanner.ZoneKey target = CampaignStrategicPlanner.zone(
                deployment.minChunkX, deployment.minChunkZ, size);
        CampaignStrategicPlanner.ChunkKey rally = new CampaignStrategicPlanner.ChunkKey(
                deployment.rally.getX() >> 4, deployment.rally.getZ() >> 4);
        CampaignTerritoryConditions.TargetState targetState = CampaignTerritoryConditions.targetState(level, controller, target);
        if (targetState == CampaignTerritoryConditions.TargetState.FULLY_FRIENDLY) {
            return CampaignDeploymentService.FinalizationResult.TARGET_WARZONE_FULLY_FRIENDLY;
        }
        if (targetState == CampaignTerritoryConditions.TargetState.NO_REACHABLE_OBJECTIVES) {
            return CampaignDeploymentService.FinalizationResult.TARGET_NO_REACHABLE_OBJECTIVES;
        }
        if (!CampaignTerritoryConditions.rallyChunkOwnedByFriendlyBloc(level, controller, rally)) {
            return CampaignDeploymentService.FinalizationResult.RALLY_OWNER_LOST;
        }
        if (!CampaignRallySafety.isSafeNow(level, deployment.rally,
                faction -> isFriendlyBloc(level, controller, faction))) {
            return CampaignDeploymentService.FinalizationResult.RALLY_SAFETY_BLOCKED;
        }
        if (deployment.members.stream().anyMatch(member -> {
            Entity entity = level.getEntity(member.id);
            return entity != null && !CampaignDeploymentService.isInPlace(level, deployment, entity)
                    && (entity instanceof Mob mob
                    ? CampaignCombatTracker.blocksMobilization(level, mob)
                    : CampaignCombatTracker.recentlyInCombat(level, member.id));
        })) return CampaignDeploymentService.FinalizationResult.MEMBER_BUSY;
        List<Infected> units = new ArrayList<>();
        List<Calamity> calamities = new ArrayList<>();
        for (CampaignDeploymentSavedData.Member member : deployment.members) {
            Entity entity = level.getEntity(member.id);
            if (!member.calamity && entity instanceof Infected infected && infected.isAlive()) units.add(infected);
            if (member.calamity && entity instanceof Calamity calamity && calamity.isAlive()) calamities.add(calamity);
        }
        int minimum = type == SporeCampaignSavedData.Type.REGULAR ? REGULAR_MIN : GRAND_MIN;
        if (units.size() < minimum || (type == SporeCampaignSavedData.Type.GRAND && calamities.isEmpty())) {
            return CampaignDeploymentService.FinalizationResult.MINIMUM_UNITS;
        }
        List<SporeCampaignSavedData.InfectedMember> memberStates = units.stream()
                .map(unit -> new SporeCampaignSavedData.InfectedMember(unit.getUUID(), unit.getLinked(), unit.getSearchPos(), deployment.target)).toList();
        List<SporeCampaignSavedData.CalamityMember> calamityStates = calamities.stream()
                .map(unit -> new SporeCampaignSavedData.CalamityMember(unit.getUUID(), unit.getSearchArea(), deployment.target, false, false)).toList();
        UUID campaignId = UUID.randomUUID();
        SporeCampaignSavedData.Campaign campaign = new SporeCampaignSavedData.Campaign(campaignId,
                SporeCampaignSavedData.Key.of(level, type), deployment.minChunkX, deployment.minChunkZ,
                deployment.maxChunkX, deployment.maxChunkZ, deployment.rally, deployment.rally,
                memberStates, calamityStates, SporeCampaignSavedData.Phase.MUSTER,
                level.getGameTime(), level.getGameTime());
        data.put(campaign);
        deployScent(level, campaign, CompatSavedData.get(level).config());
        campaignsStarted++;
        applyDirectives(campaign, units, calamities);
        LOGGER.info("[SporeCampaign] selection result=SUCCESS type={} dimension={} rejectedCandidates={} campaign={}",
                type, level.dimension().location(), deployment.rejectedCandidates, campaignId);
        notifyNearby(level, deployment.rally, (type == SporeCampaignSavedData.Type.GRAND ? "真菌大型远征" : "真菌蜂群")
                + "已完成战略部署，准备进攻战区。");
        return CampaignDeploymentService.FinalizationResult.STARTED;
    }

    private static void deployScent(ServerLevel level, SporeCampaignSavedData.Campaign campaign, CompatSavedData.Config config) {
        if (!config.sporeCampaignScentReinforcements() || !SConfig.SERVER.scent_summon.get()) return;
        int cap = Math.max(1, SConfig.SERVER.scent_cap.get());
        if (level.getEntitiesOfClass(ScentEntity.class, new AABB(campaign.rally()).inflate(16)).size() >= cap) return;
        ScentEntity scent = new ScentEntity(Sentities.SCENT.get(), level);
        scent.setOvercharged(true);
        scent.moveTo(campaign.rally().getX() + 0.5D, campaign.rally().getY(), campaign.rally().getZ() + 0.5D);
        if (level.addFreshEntity(scent)) campaign.setScentId(scent.getUUID());
    }

    private static void applyDirectives(SporeCampaignSavedData.Campaign campaign, List<Infected> members, List<Calamity> calamities) {
        Map<UUID, BlockPos> objectives = new HashMap<>();
        for (SporeCampaignSavedData.InfectedMember member : campaign.members()) objectives.put(member.id(), member.objective());
        for (Infected member : members) {
            BlockPos target = switch (campaign.phase()) {
                case MUSTER -> campaign.contains(member.chunkPosition())
                        ? objectives.getOrDefault(member.getUUID(), campaign.rally()) : campaign.rally();
                case RETREAT -> campaign.fallback();
                default -> objectives.getOrDefault(member.getUUID(), campaign.rally());
            };
            member.setLinked(true);
            member.setSearchPos(target);
        }
        if (campaign.type() == SporeCampaignSavedData.Type.GRAND && campaign.phase() == SporeCampaignSavedData.Phase.ADVANCE) {
            Map<UUID, BlockPos> calamityObjectives = new HashMap<>();
            for (SporeCampaignSavedData.CalamityMember member : campaign.calamities()) calamityObjectives.put(member.id(), member.objective());
            for (Calamity calamity : calamities) {
                SporeCampaignSavedData.CalamityMember state = campaign.calamities().stream()
                        .filter(value -> value.id().equals(calamity.getUUID())).findFirst().orElse(null);
                BlockPos target = calamityObjectives.get(calamity.getUUID());
                if (state != null && !state.advanceIssued() && target != null && calamity.getSearchArea().equals(BlockPos.ZERO)) {
                    calamity.setSearchArea(target);
                    campaign.markAdvanceIssued(calamity.getUUID());
                }
            }
        }
    }

    private static void requestMoundLanding(ServerLevel level, SporeCampaignSavedData.Campaign campaign, List<Calamity> calamities) {
        BlockPos anchor = campaign.moundAnchor();
        if (anchor == null || !TerritoryControlApi.isOwnedByModFaction(level, anchor, MOD_ID)
                || !CampaignTerrainResolver.isSafeLandAnchor(level, anchor)) return;
        for (Calamity calamity : calamities) {
            SporeCampaignSavedData.CalamityMember state = campaign.calamities().stream()
                    .filter(value -> value.id().equals(calamity.getUUID())).findFirst().orElse(null);
            if (state != null && !state.moundRequested()) {
                calamity.setSearchArea(anchor);
                campaign.markMoundRequested(calamity.getUUID());
            }
        }
    }

    /** Called by the Calamity mixin. Non-campaign calamities keep their normal mound behaviour. */
    public static boolean allowNativeMoundLanding(Entity entity) {
        if (!(entity instanceof Calamity calamity) || !(entity.level() instanceof ServerLevel level)) return true;
        SporeCampaignSavedData.Campaign campaign = SporeCampaignSavedData.get(level).campaign(level, SporeCampaignSavedData.Type.GRAND);
        if (campaign == null || campaign.calamities().stream().noneMatch(member -> member.id().equals(calamity.getUUID()))) return true;
        BlockPos anchor = campaign.moundAnchor();
        return campaign.phase() == SporeCampaignSavedData.Phase.ESTABLISH_MOUND && anchor != null
                && anchor.equals(calamity.getSearchArea()) && TerritoryControlApi.isOwnedByModFaction(level, anchor, MOD_ID)
                && CampaignTerrainResolver.isSafeLandAnchor(level, anchor)
                && CampaignTerrainResolver.isSafeLandAnchor(level, calamity.blockPosition());
    }

    private static List<Objective> hostileObjectives(ServerLevel level, SporeCampaignSavedData.Campaign campaign) {
        String controller = TerritoryControlApi.factionIdForMod(level, MOD_ID).orElse("");
        if (controller.isBlank()) return List.of();
        List<Objective> result = new ArrayList<>();
        for (int x = campaign.minChunkX(); x <= campaign.maxChunkX(); x++) {
            for (int z = campaign.minChunkZ(); z <= campaign.maxChunkZ(); z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                if (campaign.isIgnored(chunk)
                        || CampaignTerritoryConditions.chunkOwnedByFriendlyBloc(level, controller, x, z)) continue;
                List<BlockPos> anchors = CampaignWorldSnapshotCache.safeAnchors(level, x, z, campaign.members().size());
                if (anchors.isEmpty()) {
                    if (CampaignWorldSnapshotCache.hasTerrainSnapshot(level, x, z)) campaign.ignore(chunk);
                    continue;
                }
                for (BlockPos anchor : anchors) result.add(new Objective(chunk, anchor, false));
            }
        }
        result.sort(Comparator.comparing(Objective::elevated)
                .thenComparingDouble(value -> chunkDistanceSqr(value.chunk(), campaign.rally())));
        return result;
    }

    private static CampaignTerritoryConditions.TargetState targetState(
            ServerLevel level, SporeCampaignSavedData.Campaign campaign) {
        String controller = TerritoryControlApi.factionIdForMod(level, MOD_ID).orElse("");
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level)
                .warzoneConfig().normalized().sizeChunks();
        return CampaignTerritoryConditions.targetState(level, controller,
                CampaignStrategicPlanner.zone(campaign.minChunkX(), campaign.minChunkZ(), size));
    }

    private static BlockPos conqueredMoundAnchor(ServerLevel level, SporeCampaignSavedData.Campaign campaign) {
        for (SporeCampaignSavedData.InfectedMember member : campaign.members()) {
            ChunkPos chunk = new ChunkPos(member.objective());
            BlockPos anchor = CampaignWorldSnapshotCache.safeAnchor(level, chunk.x, chunk.z);
            if (anchor != null && TerritoryControlApi.isOwnedByModFaction(level, anchor, MOD_ID)) return anchor;
        }
        return null;
    }

    private static void finish(ServerLevel level, SporeCampaignSavedData data, SporeCampaignSavedData.Campaign campaign,
                               List<Infected> members, List<Calamity> calamities, long now, boolean victory, String reason) {
        release(level, campaign, members, calamities);
        data.remove(level, campaign.type());
        CompatSavedData.Config config = CompatSavedData.get(level).config();
        int minutes = campaign.type() == SporeCampaignSavedData.Type.REGULAR
                ? (victory ? config.sporeCampaignVictoryCooldownMinutes() : config.sporeCampaignFailureCooldownMinutes())
                : (victory ? config.sporeGrandCampaignVictoryCooldownMinutes() : config.sporeGrandCampaignFailureCooldownMinutes());
        data.setCooldownUntil(level, campaign.type(), now + minutesToTicks(minutes));
        NEXT_PLAN_ATTEMPT.remove(campaign.key());
        if (victory) campaignsCompleted++; else campaignsFailed++;
        if (victory) {
            LOGGER.info("[SporeCampaign] result=SUCCESS type={} id={} dimension={} tick={} reason={} members={} calamities={}",
                    campaign.type(), campaign.id(), level.dimension().location(), now, reason, members.size(), calamities.size());
        } else {
            CampaignFailureLog.record("SporeCampaign", "campaign result=FAILURE type=" + campaign.type() + " id="
                    + campaign.id() + " dimension=" + level.dimension().location() + " reason=" + reason);
        }
        notifyNearby(level, campaign.rally(), "真菌" + (campaign.type() == SporeCampaignSavedData.Type.GRAND ? "大型远征" : "蜂群战役")
                + (victory ? "胜利：" : "失败：") + reason);
    }

    public static boolean abort(ServerLevel level, SporeCampaignSavedData.Type type, boolean announce, String reason) {
        SporeCampaignSavedData data = SporeCampaignSavedData.get(level);
        SporeCampaignSavedData.Campaign campaign = data.remove(level, type);
        if (campaign == null) return false;
        release(level, campaign, members(level, campaign), calamities(level, campaign));
        NEXT_PLAN_ATTEMPT.remove(campaign.key());
        if (announce) notifyNearby(level, campaign.rally(), "真菌战役已取消：" + reason);
        return true;
    }

    private static void abortAll(ServerLevel level) {
        abort(level, SporeCampaignSavedData.Type.REGULAR, false, "配置已关闭");
        abort(level, SporeCampaignSavedData.Type.GRAND, false, "配置已关闭");
    }

    private static void release(ServerLevel level, SporeCampaignSavedData.Campaign campaign, List<Infected> members, List<Calamity> calamities) {
        Map<UUID, SporeCampaignSavedData.InfectedMember> states = new HashMap<>();
        for (SporeCampaignSavedData.InfectedMember state : campaign.members()) states.put(state.id(), state);
        for (Infected member : members) {
            SporeCampaignSavedData.InfectedMember state = states.get(member.getUUID());
            if (state == null) continue;
            if (equalsAny(member.getSearchPos(), state.objective(), campaign.rally(), campaign.fallback())) member.setSearchPos(state.originalSearch());
            if (!state.originalLinked() && member.getLinked()) member.setLinked(false);
        }
        Map<UUID, SporeCampaignSavedData.CalamityMember> calamityStates = new HashMap<>();
        for (SporeCampaignSavedData.CalamityMember state : campaign.calamities()) calamityStates.put(state.id(), state);
        for (Calamity calamity : calamities) {
            SporeCampaignSavedData.CalamityMember state = calamityStates.get(calamity.getUUID());
            if (state != null && equalsAny(calamity.getSearchArea(), state.objective(), campaign.moundAnchor())) calamity.setSearchArea(state.originalSearch());
        }
        if (campaign.scentId() != null) {
            Entity scent = level.getEntity(campaign.scentId());
            if (scent != null) scent.discard();
        }
    }

    private static boolean equalsAny(BlockPos value, BlockPos... candidates) {
        if (value == null) return false;
        for (BlockPos candidate : candidates) if (candidate != null && candidate.equals(value)) return true;
        return false;
    }

    private static boolean isOperational(ServerLevel level, List<ServerPlayer> players, SporeCampaignSavedData.Campaign campaign) {
        int radius = Math.max(0, level.getServer().getPlayerList().getViewDistance());
        boolean visible = players.stream().anyMatch(player -> {
            ChunkPos chunk = player.chunkPosition();
            return chunk.x + radius >= campaign.minChunkX() && chunk.x - radius <= campaign.maxChunkX()
                    && chunk.z + radius >= campaign.minChunkZ() && chunk.z - radius <= campaign.maxChunkZ();
        });
        if (!visible || !CampaignTerrainResolver.isSafeLandAnchor(level, campaign.rally())
                || !CampaignTerrainResolver.isSafeLandAnchor(level, campaign.fallback())) return false;
        return campaign.members().stream().allMatch(member -> level.hasChunk(member.objective().getX() >> 4, member.objective().getZ() >> 4));
    }

    private static boolean mustered(SporeCampaignSavedData.Campaign campaign, List<Infected> members) {
        return members.stream().filter(member -> campaign.contains(member.chunkPosition())
                || member.distanceToSqr(campaign.rally().getX() + 0.5D,
                campaign.rally().getY(), campaign.rally().getZ() + 0.5D) <= 256.0D).count() * 4 >= members.size() * 3;
    }

    private static boolean hostile(ServerLevel level, String controller, TerritoryControlApi.TerritoryView territory) {
        return hostileFaction(level, controller, territory.ownerFaction()) || hostileFaction(level, controller, territory.progressFaction())
                || hostileFaction(level, controller, territory.contestFaction());
    }
    private static boolean hostileFaction(ServerLevel level, String controller, String other) {
        return other != null && !other.isBlank() && !other.equals(controller) && !TerritoryControlApi.areFactionsSameOrAllied(level, controller, other);
    }
    private static boolean isFriendlyBloc(ServerLevel level, String controller, String faction) {
        return faction != null && !faction.isBlank() && (controller.equals(faction)
                || TerritoryControlApi.areFactionsSameOrAllied(level, controller, faction));
    }

    private static Set<UUID> assignedIds(ServerLevel level, SporeCampaignSavedData data) {
        Set<UUID> result = new HashSet<>();
        for (SporeCampaignSavedData.Campaign campaign : data.campaigns(level)) {
            campaign.members().forEach(member -> result.add(member.id()));
            campaign.calamities().forEach(member -> result.add(member.id()));
        }
        return result;
    }

    private static List<Infected> members(ServerLevel level, SporeCampaignSavedData.Campaign campaign) {
        List<Infected> result = new ArrayList<>();
        for (SporeCampaignSavedData.InfectedMember member : campaign.members()) {
            Entity entity = level.getEntity(member.id());
            if (entity instanceof Infected infected && infected.isAlive()) result.add(infected);
        }
        return result;
    }

    private static List<Calamity> calamities(ServerLevel level, SporeCampaignSavedData.Campaign campaign) {
        List<Calamity> result = new ArrayList<>();
        for (SporeCampaignSavedData.CalamityMember member : campaign.calamities()) {
            Entity entity = level.getEntity(member.id());
            if (entity instanceof Calamity calamity && calamity.isAlive()) result.add(calamity);
        }
        return result;
    }

    public static List<String> status(ServerLevel level) {
        List<String> result = new ArrayList<>();
        for (SporeCampaignSavedData.Campaign campaign : SporeCampaignSavedData.get(level).campaigns(level)) {
            result.add(campaign.type() + " " + campaign.phase() + " zone=" + campaign.minChunkX() + "," + campaign.minChunkZ()
                    + "-" + campaign.maxChunkX() + "," + campaign.maxChunkZ() + " infected=" + members(level, campaign).size()
                    + " calamities=" + calamities(level, campaign).size());
        }
        return result;
    }

    public static Metrics metrics() { return new Metrics(planningAttempts, candidateWarzones, pathProbes, campaignsStarted, campaignsCompleted, campaignsFailed, pauses); }

    static void retryAfterDeployment(ServerLevel level, String scope) {
        try {
            SporeCampaignSavedData.Type type = SporeCampaignSavedData.Type.valueOf(scope);
            NEXT_PLAN_ATTEMPT.put(new SporeCampaignSavedData.Key(
                    level.dimension().location().toString(), type), level.getGameTime());
        } catch (RuntimeException ignored) { }
    }

    private static boolean targetResolvedReason(String reason) {
        return "TARGET_WARZONE_FULLY_FRIENDLY".equals(reason)
                || "TARGET_NO_REACHABLE_OBJECTIVES".equals(reason);
    }
    private static void transition(ServerLevel level, SporeCampaignSavedData.Campaign campaign,
                                   SporeCampaignSavedData.Phase next, long now, String reason) {
        SporeCampaignSavedData.Phase previous = campaign.phase();
        campaign.setPhase(next, now);
        LOGGER.info("[SporeCampaign] phase type={} id={} dimension={} tick={} {}->{} reason={} rally={}",
                campaign.type(), campaign.id(), level.dimension().location(), now, previous, next, reason, campaign.rally());
    }

    private static void logCampaign(ServerLevel level, SporeCampaignSavedData.Campaign campaign,
                                    String event, String detail) {
        LOGGER.info("[SporeCampaign] event={} type={} id={} dimension={} tick={} phase={} zone={},{},{}:{} {}", event,
                campaign.type(), campaign.id(), level.dimension().location(), level.getGameTime(), campaign.phase(),
                campaign.minChunkX(), campaign.minChunkZ(), campaign.maxChunkX(), campaign.maxChunkZ(), detail);
    }

    private static long minutesToTicks(int minutes) { return Math.max(1L, Math.min(1440L, minutes)) * 1_200L; }
    private static boolean sharesBounds(SporeCampaignSavedData.Campaign campaign, Warzone.Bounds bounds) {
        return campaign.minChunkX() <= bounds.maxChunkX() && campaign.maxChunkX() >= bounds.minChunkX()
                && campaign.minChunkZ() <= bounds.maxChunkZ() && campaign.maxChunkZ() >= bounds.minChunkZ();
    }
    private static void notifyNearby(ServerLevel level, BlockPos pos, String text) {
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.blockPosition().distSqr(pos) <= 128.0D * 128.0D) {
                player.displayClientMessage(Component.literal(text), false);
            }
        }
    }
    private static double chunkDistanceSqr(ChunkPos chunk, BlockPos point) { double x = chunk.getMiddleBlockX() - point.getX(), z = chunk.getMiddleBlockZ() - point.getZ(); return x * x + z * z; }

    private record Objective(ChunkPos chunk, BlockPos anchor, boolean elevated) { }
    private static final class ProbeSequence {
        private final java.util.ArrayDeque<CampaignStrategicPlanner.Option> options;
        private final Map<String, Integer> failures = new java.util.TreeMap<>();
        private final String cooldownAudit;
        private int rejected;
        private int probesSubmitted;
        private ProbeSequence(java.util.ArrayDeque<CampaignStrategicPlanner.Option> options, int rejected, String cooldownAudit) {
            this.options = options;
            this.rejected = rejected;
            this.cooldownAudit = cooldownAudit == null || cooldownAudit.isBlank() ? "none" : cooldownAudit;
        }
        private void reject(String reason) {
            rejected++;
            failures.merge(reason == null || reason.isBlank() ? "UNKNOWN" : reason, 1, Integer::sum);
        }
        private void rejectTarget(CampaignStrategicPlanner.ZoneKey target, String reason) {
            reject(reason);
            int removed = 0;
            for (var iterator = options.iterator(); iterator.hasNext();) {
                if (iterator.next().candidate().target().equals(target)) {
                    iterator.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                rejected += removed;
                failures.merge(reason, removed, Integer::sum);
            }
        }
        private String failureSummary() {
            String reasons = failures.isEmpty() ? "none" : failures.entrySet().stream()
                    .map(entry -> entry.getKey() + ":" + entry.getValue()).collect(java.util.stream.Collectors.joining("|"));
            return "submitted:" + probesSubmitted + ",reasons:" + reasons + ",cooldownAudit{" + cooldownAudit + "}";
        }
    }
    public record Metrics(long planningAttempts, long candidateWarzones, long pathProbes, long campaignsStarted, long campaignsCompleted, long campaignsFailed, long pauses) { }
}
