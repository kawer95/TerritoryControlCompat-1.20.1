package com.arxyt.territorycontrolcompat.compat;

import com.mojang.logging.LogUtils;
import com.arxyt.ratnations.api.FactionDescriptor;
import com.arxyt.ratnations.api.RatNationsFactionApi;
import com.arxyt.ratnations.entity.AbstractMouseSoldierEntity;
import com.arxyt.ratnations.entity.MouseCampaignDirective;
import com.arxyt.ratnations.entity.MouseCampaignPhase;
import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrol.core.BattleModes;
import com.arxyt.territorycontrol.core.capture.CaptureEntityIndex;
import com.arxyt.territorycontrol.core.data.Warzone;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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

/** Server-authoritative autonomous Rat Nations campaigns against loaded hostile warzones. */
public final class RatNationsCampaignDirector {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_PREFIX = "[RatCampaign]";
    private static final int MIN_SQUAD = 4;
    private static final int MAX_SQUAD = 8;
    private static final int CLOSE_THREAT_RANGE = 12;
    private static final long TICK_INTERVAL = 20L;
    private static final long PLAN_RETRY_TICKS = 1_200L;
    private static final long MUSTER_TIMEOUT_TICKS = 1_200L;
    private static final long STABILIZE_TICKS = 600L;
    private static final Map<RatNationsCampaignSavedData.Key, Long> NEXT_PLAN_ATTEMPT = new HashMap<>();
    /** Last bounded planning outcome per nation.  Exposed through the existing admin command so
     * a skipped campaign is never indistinguishable from a broken one. */
    private static final Map<RatNationsCampaignSavedData.Key, String> LAST_PLAN_OUTCOME = new HashMap<>();
    private static final Map<CampaignPlanningService.JobKey, ProbeSequence> PROBE_SEQUENCES = new HashMap<>();
    private static long planningAttempts, candidateWarzones, pathProbes, campaignsStarted, campaignsCompleted, campaignsFailed, pauses;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % TICK_INTERVAL != 0L) return;
        MinecraftServer server = event.getServer();
        if (!CompatSavedData.get(server.overworld()).config().ratNationsCampaigns()) {
            CampaignPlanningService.cancelDirector("rat");
            CampaignRouteProbeService.cancelDirector("rat");
            CampaignRallyPlacementService.cancelDirector("rat");
            PROBE_SEQUENCES.clear();
            for (ServerLevel level : server.getAllLevels()) { abortAll(level); CampaignDeploymentService.rollback(level, "RAT"); }
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
        for (ServerLevel level : server.getAllLevels()) tickLevel(level);
    }

    private static void tickLevel(ServerLevel level) {
        List<ServerPlayer> players = level.players().stream().filter(player -> player.isAlive() && !player.isSpectator()).toList();
        if (players.isEmpty()) return;
        RatNationsCampaignSavedData data = RatNationsCampaignSavedData.get(level);
        long now = level.getGameTime();
        CampaignWorldSnapshotCache.PlanningSnapshot snapshot = CampaignWorldSnapshotCache.planningSnapshot(level);
        for (RatNationsCampaignSavedData.Campaign campaign : data.campaigns(level)) tickCampaign(level, players, data, campaign, now);
        for (FactionDescriptor nation : RatNationsFactionApi.factions()) {
            if (TerritoryControlApi.factionForExternal(level, RatNationsFactionProvider.PROVIDER_ID,
                    nation.id().toString()).isEmpty()) continue;
            if (data.campaign(level, nation.id()) != null
                    || CampaignDeploymentSavedData.get(level).hasScope("RAT", nation.id().toString())) continue;
            RatNationsCampaignSavedData.Key key = RatNationsCampaignSavedData.Key.of(level, nation.id());
            if (snapshot == null) continue;
            CampaignPlanningService.JobKey jobKey = new CampaignPlanningService.JobKey(
                    level.dimension().location().toString(), "rat", nation.id().toString());
            if (PROBE_SEQUENCES.containsKey(jobKey)) continue;
            CampaignPlanningService.Completion completion = CampaignPlanningService.poll(jobKey, snapshot.generation());
            if (completion != null) {
                completeBackgroundPlan(level, data, nation.id(), key, completion, snapshot, now);
                continue;
            }
            long cooldown = data.cooldownUntil(level, nation.id());
            if (now < cooldown) continue;
            long retry = NEXT_PLAN_ATTEMPT.getOrDefault(key, 0L);
            if (now < retry) continue;
            CampaignStrategicPlanner.Request request = planningRequest(level, nation.id(), snapshot);
            if (request == null) {
                NEXT_PLAN_ATTEMPT.put(key, now + PLAN_RETRY_TICKS);
                continue;
            }
            if (CampaignPlanningService.submit(jobKey, snapshot.generation(), request)) {
                planningAttempts++;
                NEXT_PLAN_ATTEMPT.put(key, now + PLAN_RETRY_TICKS);
            }
        }
    }

    private static CampaignStrategicPlanner.Request planningRequest(ServerLevel level, ResourceLocation nation,
                                                                     CampaignWorldSnapshotCache.PlanningSnapshot snapshot) {
        String controller = TerritoryControlApi.factionForExternal(level, RatNationsFactionProvider.PROVIDER_ID, nation.toString())
                .map(TerritoryControlApi.FactionView::id).orElse("");
        if (controller.isBlank()) return null;
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
        RatCampaignUnitIndex.Snapshot unitSnapshot = RatCampaignUnitIndex.snapshotWithStats(level, nation, friendlyChunks);
        List<CampaignStrategicPlanner.Unit> units = unitSnapshot.units();
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level).warzoneConfig().normalized().sizeChunks();
        CampaignWorldSnapshotCache.markFrontierHot(level, CampaignStrategicPlanner.frontierTerrainChunks(cells, size));
        Set<Long> unsafeRallyChunks = CampaignRallySafety.unsafeRallyChunks(level,
                CampaignStrategicPlanner.potentialRallyChunks(cells, size), friendly);
        String cooldownAudit = CampaignCombatTracker.cooldownAudit(level, units.stream()
                .map(CampaignStrategicPlanner.Unit::id).toList());
        candidateWarzones += cells.size();
        return new CampaignStrategicPlanner.Request(CampaignStrategicPlanner.Kind.RAT, size, MIN_SQUAD, MAX_SQUAD, 0,
                cells, unsafeZones, units, List.of(), snapshot.terrain(), unsafeRallyChunks, cooldownAudit, unitSnapshot.stats(),
                CampaignStrategicPlanner.SourceStats.empty());
    }

    private static void completeBackgroundPlan(ServerLevel level, RatNationsCampaignSavedData data, ResourceLocation nation,
                                               RatNationsCampaignSavedData.Key key,
                                               CampaignPlanningService.Completion completion,
                                               CampaignWorldSnapshotCache.PlanningSnapshot snapshot, long now) {
        if (completion.expired()) return;
        if (completion.failure() != null) {
            LAST_PLAN_OUTCOME.put(key, "FAILURE: " + completion.failure().getClass().getSimpleName());
            CampaignFailureLog.record("RatCampaign", "selection result=FAILURE dimension=" + level.dimension().location()
                    + " nation=" + nation + " rejectedCandidates=0 reason=" + completion.failure());
            return;
        }
        CampaignStrategicPlanner.Result result = completion.result();
        if (result == null || !result.success()) {
            String reason = result == null ? "NO_RESULT" : result.reason();
            int rejected = result == null ? 0 : result.rejectedCandidates();
            String diagnostics = result == null ? "" : " diagnostics=" + result.diagnostics().compact();
            LAST_PLAN_OUTCOME.put(key, "FAILURE: " + reason);
            CampaignFailureLog.record("RatCampaign", "selection result=FAILURE dimension=" + level.dimension().location()
                    + " nation=" + nation + " rejectedCandidates=" + rejected + " reason=" + reason + diagnostics);
            return;
        }
        CampaignPlanningService.JobKey jobKey = new CampaignPlanningService.JobKey(
                level.dimension().location().toString(), "rat", nation.toString());
        ProbeSequence sequence = new ProbeSequence(new java.util.ArrayDeque<>(result.options()), result.rejectedCandidates(),
                result.diagnostics().cooldownAudit());
        PROBE_SEQUENCES.put(jobKey, sequence);
        startNextProbe(level, nation, key, jobKey, sequence);
    }

    private static void startNextProbe(ServerLevel level, ResourceLocation nation, RatNationsCampaignSavedData.Key key,
                                       CampaignPlanningService.JobKey jobKey, ProbeSequence sequence) {
        CampaignStrategicPlanner.Option option = sequence.options.poll();
        if (option == null) {
            CampaignUnitReservations.release(jobKey);
            PROBE_SEQUENCES.remove(jobKey);
            LAST_PLAN_OUTCOME.put(key, "FAILURE: ALL_ROUTE_PROBES_FAILED");
            CampaignFailureLog.record("RatCampaign", "selection result=FAILURE dimension=" + level.dimension().location()
                    + " nation=" + nation + " rejectedCandidates=" + sequence.rejected
                    + " reason=ALL_ROUTE_PROBES_FAILED probeFailures=" + sequence.failureSummary());
            return;
        }
        if (!CampaignUnitReservations.tryReserve(jobKey, option.memberIds())) {
            sequence.reject("RESERVATION_FAILED");
            startNextProbe(level, nation, key, jobKey, sequence);
            return;
        }
        List<AbstractMouseSoldierEntity> initialUnits = RatCampaignUnitIndex.resolve(level, option.memberIds(), nation);
        String controller = TerritoryControlApi.factionForExternal(level, RatNationsFactionProvider.PROVIDER_ID, nation.toString())
                .map(TerritoryControlApi.FactionView::id).orElse("");
        if (initialUnits.size() < MIN_SQUAD) {
            CampaignUnitReservations.release(jobKey);
            sequence.reject("PRE_PROBE_MIN_UNITS");
            startNextProbe(level, nation, key, jobKey, sequence);
            return;
        }
        CampaignPlanValidation.Result preProbeValidation = CampaignPlanValidation.validate(level, controller, option, initialUnits);
        if (preProbeValidation != CampaignPlanValidation.Result.VALID) {
            CampaignUnitReservations.release(jobKey);
            sequence.reject("PRE_PROBE_" + preProbeValidation);
            startNextProbe(level, nation, key, jobKey, sequence);
            return;
        }
        CampaignWorldSnapshotCache.markHot(level, option);
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level)
                .warzoneConfig().normalized().sizeChunks();
        int movers = (int) initialUnits.stream().filter(unit -> !CampaignStrategicPlanner.inPlace(
                option.candidate(), size, unit.getBlockX() >> 4, unit.getBlockZ() >> 4)).count();
        if (!CampaignRallyPlacementService.submit(level, jobKey, controller, option, movers, placement -> {
            if (!placement.success()) {
                CampaignUnitReservations.release(jobKey);
                if (targetResolvedReason(placement.reason())) {
                    sequence.rejectTarget(option.candidate().target(), placement.reason());
                } else {
                    sequence.reject(placement.reason());
                }
                startNextProbe(level, nation, key, jobKey, sequence);
                return;
            }
            CampaignPlanValidation.Result placementValidation = CampaignPlanValidation.validate(
                    level, controller, option, initialUnits);
            if (placementValidation != CampaignPlanValidation.Result.VALID) {
                CampaignUnitReservations.release(jobKey);
                if (placementValidation == CampaignPlanValidation.Result.TARGET_WARZONE_FULLY_FRIENDLY
                        || placementValidation == CampaignPlanValidation.Result.TARGET_NO_REACHABLE_OBJECTIVES) {
                    sequence.rejectTarget(option.candidate().target(), "PLACEMENT_" + placementValidation);
                } else {
                    sequence.reject("PLACEMENT_" + placementValidation);
                }
                startNextProbe(level, nation, key, jobKey, sequence);
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
                startNextProbe(level, nation, key, jobKey, sequence);
                return;
            }
            List<AbstractMouseSoldierEntity> units = RatCampaignUnitIndex.resolve(level, option.memberIds(), nation);
            String currentController = TerritoryControlApi.factionForExternal(level, RatNationsFactionProvider.PROVIDER_ID, nation.toString())
                    .map(TerritoryControlApi.FactionView::id).orElse("");
            if (units.size() < MIN_SQUAD) {
                CampaignUnitReservations.release(jobKey);
                sequence.reject("POST_PROBE_MIN_UNITS");
                startNextProbe(level, nation, key, jobKey, sequence);
                return;
            }
            CampaignPlanValidation.Result postProbeValidation = CampaignPlanValidation.validate(
                    level, currentController, option, units);
            if (postProbeValidation != CampaignPlanValidation.Result.VALID) {
                CampaignUnitReservations.release(jobKey);
                sequence.reject("POST_PROBE_" + postProbeValidation);
                startNextProbe(level, nation, key, jobKey, sequence);
                return;
            }
            CampaignDeploymentService.BeginResult deployment = CampaignDeploymentService.begin(
                    level, "RAT", nation.toString(), sequence.rejected, option, units, List.of(),
                    placement.positions().get(0),
                    movers == 0 ? List.of() : placement.positions());
            if (deployment != CampaignDeploymentService.BeginResult.STARTED) {
                CampaignUnitReservations.release(jobKey);
                sequence.reject("DEPLOYMENT_" + deployment);
                startNextProbe(level, nation, key, jobKey, sequence);
                return;
            }
            PROBE_SEQUENCES.remove(jobKey);
            LAST_PLAN_OUTCOME.put(key, "DEPLOYING");
            })) {
                CampaignUnitReservations.release(jobKey);
                sequence.reject("PROBE_QUEUE_REJECTED");
                startNextProbe(level, nation, key, jobKey, sequence);
            } else {
                sequence.probesSubmitted++;
            }
        })) {
            CampaignUnitReservations.release(jobKey);
            sequence.reject("RALLY_PLACEMENT_QUEUE_REJECTED");
            startNextProbe(level, nation, key, jobKey, sequence);
        }
    }

    static CampaignDeploymentService.FinalizationResult completeDeployment(ServerLevel level, CampaignDeploymentSavedData.Deployment deployment) {
        ResourceLocation nation;
        try { nation = new ResourceLocation(deployment.scope); } catch (RuntimeException invalid) {
            return CampaignDeploymentService.FinalizationResult.INVALID_SCOPE;
        }
        RatNationsCampaignSavedData data = RatNationsCampaignSavedData.get(level);
        if (data.campaign(level, nation) != null) return CampaignDeploymentService.FinalizationResult.CAMPAIGN_ALREADY_ACTIVE;
        String controller = TerritoryControlApi.factionForExternal(level, RatNationsFactionProvider.PROVIDER_ID, nation.toString())
                .map(TerritoryControlApi.FactionView::id).orElse("");
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
        List<AbstractMouseSoldierEntity> units = new ArrayList<>();
        for (CampaignDeploymentSavedData.Member member : deployment.members) {
            Entity entity = level.getEntity(member.id);
            if (!member.calamity && entity instanceof AbstractMouseSoldierEntity soldier && soldier.isAlive()
                    && nation.equals(soldier.ratNationsFactionId())) units.add(soldier);
        }
        if (units.size() < MIN_SQUAD) return CampaignDeploymentService.FinalizationResult.MINIMUM_UNITS;
        UUID campaignId = UUID.randomUUID();
        List<RatNationsCampaignSavedData.Member> members = units.stream()
                .map(unit -> new RatNationsCampaignSavedData.Member(unit.getUUID(), deployment.target)).toList();
        RatNationsCampaignSavedData.Campaign campaign = new RatNationsCampaignSavedData.Campaign(campaignId,
                RatNationsCampaignSavedData.Key.of(level, nation), deployment.minChunkX, deployment.minChunkZ,
                deployment.maxChunkX, deployment.maxChunkZ, deployment.rally, deployment.rally, members,
                MouseCampaignPhase.MUSTER, level.getGameTime(), level.getGameTime());
        data.put(campaign);
        campaignsStarted++;
        applyDirectives(level, campaign, units);
        labelLeader(units, MouseCampaignPhase.MUSTER);
        LOGGER.info("{} selection result=SUCCESS dimension={} nation={} rejectedCandidates={} campaign={}", LOG_PREFIX,
                level.dimension().location(), nation, deployment.rejectedCandidates, campaignId);
        LAST_PLAN_OUTCOME.put(campaign.key(), "SUCCESS");
        notifyNearby(level, deployment.rally, "鼠族 " + nation.getPath() + " 小队已完成战略部署，准备进攻战区。");
        return CampaignDeploymentService.FinalizationResult.STARTED;
    }

    private static void tickCampaign(ServerLevel level, List<ServerPlayer> players,
                                     RatNationsCampaignSavedData data, RatNationsCampaignSavedData.Campaign campaign, long now) {
        List<AbstractMouseSoldierEntity> members = members(level, campaign);
        if (!isCampaignOperational(players, level, campaign)) {
            if (!campaign.paused()) {
                campaign.setPaused(true);
                pauses++;
                data.markChanged();
                LOGGER.info("{} paused dimension={} nation={} campaign={} reason=required chunks/player view unavailable",
                        LOG_PREFIX, level.dimension().location(), campaign.nation(), campaign.id());
            }
            applyDirectives(level, campaign, members);
            return;
        }
        if (campaign.paused()) {
            campaign.setPaused(false);
            data.markChanged();
            LOGGER.info("{} resumed dimension={} nation={} campaign={} phase={}", LOG_PREFIX,
                    level.dimension().location(), campaign.nation(), campaign.id(), campaign.phase());
        }
        if (members.isEmpty()) {
            finish(level, data, campaign, members, now, false, "战役成员全部失联或阵亡");
            return;
        }
        if (campaign.phase() == MouseCampaignPhase.RETREAT) {
            MouseCampaignPhase resumed = members.stream().anyMatch(member -> campaign.contains(member.chunkPosition()))
                    ? MouseCampaignPhase.OCCUPY : MouseCampaignPhase.ADVANCE;
            transition(level, campaign, resumed, now, "撤退功能已关闭，恢复进攻");
            data.markChanged();
        }
        switch (campaign.phase()) {
            case MUSTER -> {
                if (now >= campaign.launchAt() && targetState(level, campaign) != CampaignTerritoryConditions.TargetState.ACTIVE) {
                    finish(level, data, campaign, members, now, true, "目标战区已完成或剩余区块不可达");
                    return;
                }
                if (now >= campaign.launchAt() && mustered(campaign, members)) transition(level, campaign, MouseCampaignPhase.ADVANCE, now, "集结完成");
                else if (now >= campaign.launchAt() && now - Math.max(campaign.phaseSince(), campaign.launchAt()) > MUSTER_TIMEOUT_TICKS) {
                    transition(level, campaign, MouseCampaignPhase.ADVANCE, now, "撤退已关闭，集结超时后直接进攻");
                }
            }
            case ADVANCE -> {
                if (members.stream().filter(member -> campaign.contains(member.chunkPosition())).count() * 2 >= members.size()) {
                    transition(level, campaign, MouseCampaignPhase.OCCUPY, now, "半数小队进入战区");
                }
            }
            case OCCUPY -> {
                List<ChunkPos> hostile = hostileControlChunks(level, campaign);
                if (hostile.isEmpty() && targetState(level, campaign) != CampaignTerritoryConditions.TargetState.ACTIVE) {
                    transition(level, campaign, MouseCampaignPhase.STABILIZE, now, "目标已清空或剩余区块不可达");
                }
                else if (!hostile.isEmpty()) {
                    hostile.sort(Comparator.comparingLong(ChunkPos::toLong));
                    List<BlockPos> objectives = hostile.stream()
                            .flatMap(chunk -> CampaignWorldSnapshotCache.safeAnchors(
                                    level, chunk.x, chunk.z, campaign.members().size()).stream())
                            .distinct().limit(campaign.members().size()).toList();
                    long currentDistinct = campaign.members().stream().map(RatNationsCampaignSavedData.Member::objective)
                            .distinct().count();
                    boolean invalidObjective = campaign.members().stream()
                            .anyMatch(member -> !hostile.contains(new ChunkPos(member.objective())));
                    if (!objectives.isEmpty() && (invalidObjective
                            || currentDistinct < Math.min(campaign.members().size(), objectives.size()))) {
                        campaign.assignObjectives(objectives);
                    }
                }
            }
            case STABILIZE -> {
                if (targetState(level, campaign) == CampaignTerritoryConditions.TargetState.ACTIVE) {
                    transition(level, campaign, MouseCampaignPhase.OCCUPY, now, "出现新的可达目标");
                }
                else if (now - campaign.phaseSince() >= STABILIZE_TICKS) {
                    finish(level, data, campaign, members, now, true, "战区已稳固");
                    return;
                }
            }
            case RETREAT -> {
                // Legacy saves are migrated above before entering the phase switch.
            }
            case PAUSED -> { }
        }
        applyDirectives(level, campaign, members);
        data.markChanged();
    }

    private static boolean hostile(ServerLevel level, String controller, TerritoryControlApi.TerritoryView value) {
        return hostileFaction(level, controller, value.ownerFaction()) || hostileFaction(level, controller, value.progressFaction())
                || hostileFaction(level, controller, value.contestFaction());
    }

    private static boolean hostileFaction(ServerLevel level, String controller, String other) {
        return other != null && !other.isBlank() && !other.equals(controller)
                && !TerritoryControlApi.areFactionsSameOrAllied(level, controller, other);
    }

    private static boolean isFriendlyBloc(ServerLevel level, String controller, String faction) {
        return faction != null && !faction.isBlank() && (controller.equals(faction)
                || TerritoryControlApi.areFactionsSameOrAllied(level, controller, faction));
    }

    private static List<AbstractMouseSoldierEntity> members(ServerLevel level, RatNationsCampaignSavedData.Campaign campaign) {
        List<AbstractMouseSoldierEntity> result = new ArrayList<>();
        for (RatNationsCampaignSavedData.Member member : campaign.members()) {
            var entity = level.getEntity(member.id());
            if (entity instanceof AbstractMouseSoldierEntity soldier && soldier.isAlive() && campaign.nation().equals(soldier.ratNationsFactionId())) result.add(soldier);
        }
        return result;
    }

    private static void applyDirectives(ServerLevel level, RatNationsCampaignSavedData.Campaign campaign,
                                        List<AbstractMouseSoldierEntity> members) {
        Map<UUID, BlockPos> objectives = new HashMap<>();
        for (RatNationsCampaignSavedData.Member member : campaign.members()) objectives.put(member.id(), member.objective());
        for (AbstractMouseSoldierEntity soldier : members) {
            BlockPos objective = campaign.phase() == MouseCampaignPhase.MUSTER && !campaign.contains(soldier.chunkPosition()) ? campaign.rally()
                    : objectives.getOrDefault(soldier.getUUID(), campaign.rally());
            soldier.setCampaignDirective(new MouseCampaignDirective(campaign.id(), campaign.phase(), campaign.minChunkX(), campaign.minChunkZ(),
                    campaign.maxChunkX(), campaign.maxChunkZ(), objective, campaign.fallback(), CLOSE_THREAT_RANGE));
        }
        labelLeader(members, campaign.phase());
    }

    private static boolean mustered(RatNationsCampaignSavedData.Campaign campaign, List<AbstractMouseSoldierEntity> members) {
        return members.stream().filter(member -> campaign.contains(member.chunkPosition())
                || member.distanceToSqr(campaign.rally().getX() + 0.5D,
                campaign.rally().getY(), campaign.rally().getZ() + 0.5D) <= 256.0D).count() * 4 >= members.size() * 3;
    }

    private static boolean hasHostileControl(ServerLevel level, RatNationsCampaignSavedData.Campaign campaign) {
        return targetState(level, campaign) == CampaignTerritoryConditions.TargetState.ACTIVE;
    }

    private static CampaignTerritoryConditions.TargetState targetState(
            ServerLevel level, RatNationsCampaignSavedData.Campaign campaign) {
        String controller = TerritoryControlApi.factionForExternal(level, RatNationsFactionProvider.PROVIDER_ID,
                campaign.nation().toString()).map(TerritoryControlApi.FactionView::id).orElse("");
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level)
                .warzoneConfig().normalized().sizeChunks();
        return CampaignTerritoryConditions.targetState(level, controller,
                CampaignStrategicPlanner.zone(campaign.minChunkX(), campaign.minChunkZ(), size));
    }

    private static List<ChunkPos> hostileControlChunks(ServerLevel level, RatNationsCampaignSavedData.Campaign campaign) {
        String controller = TerritoryControlApi.factionForExternal(level, RatNationsFactionProvider.PROVIDER_ID, campaign.nation().toString())
                .map(TerritoryControlApi.FactionView::id).orElse("");
        if (controller.isBlank()) return List.of();
        List<ChunkPos> result = new ArrayList<>();
        for (int x = campaign.minChunkX(); x <= campaign.maxChunkX(); x++) {
            for (int z = campaign.minChunkZ(); z <= campaign.maxChunkZ(); z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                if (campaign.isIgnored(chunk)
                        || CampaignTerritoryConditions.chunkOwnedByFriendlyBloc(level, controller, x, z)) continue;
                BlockPos anchor = CampaignWorldSnapshotCache.safeAnchor(level, x, z);
                if (anchor != null) result.add(chunk);
                else if (CampaignWorldSnapshotCache.hasTerrainSnapshot(level, x, z)) campaign.ignore(chunk);
            }
        }
        return result;
    }

    private static boolean isCampaignOperational(List<ServerPlayer> players, ServerLevel level, RatNationsCampaignSavedData.Campaign campaign) {
        int radius = Math.max(0, level.getServer().getPlayerList().getViewDistance());
        boolean intersects = false;
        for (ServerPlayer player : players) {
            ChunkPos chunk = player.chunkPosition();
            if (chunk.x + radius >= campaign.minChunkX() && chunk.x - radius <= campaign.maxChunkX()
                    && chunk.z + radius >= campaign.minChunkZ() && chunk.z - radius <= campaign.maxChunkZ()) {
                intersects = true;
                break;
            }
        }
        if (!intersects || !level.hasChunk(campaign.rally().getX() >> 4, campaign.rally().getZ() >> 4)
                || !level.hasChunk(campaign.fallback().getX() >> 4, campaign.fallback().getZ() >> 4)) return false;
        for (RatNationsCampaignSavedData.Member member : campaign.members()) {
            if (!level.hasChunk(member.objective().getX() >> 4, member.objective().getZ() >> 4)) return false;
        }
        return true;
    }

    private static void finish(ServerLevel level, RatNationsCampaignSavedData data,
                               RatNationsCampaignSavedData.Campaign campaign, List<AbstractMouseSoldierEntity> members,
                               long now, boolean victory, String reason) {
        for (AbstractMouseSoldierEntity soldier : members) {
            soldier.setCampaignDirective(null);
            soldier.restoreConfiguredDisplayName();
        }
        data.remove(level, campaign.nation());
        int minutes = victory ? CompatSavedData.get(level).config().ratNationsVictoryCooldownMinutes()
                : CompatSavedData.get(level).config().ratNationsFailureCooldownMinutes();
        data.setCooldownUntil(level, campaign.nation(), now + minutesToTicks(minutes));
        NEXT_PLAN_ATTEMPT.remove(campaign.key());
        if (victory) campaignsCompleted++; else campaignsFailed++;
        if (victory) {
            LOGGER.info("{} result=SUCCESS dimension={} nation={} campaign={} reason={} cooldownTicks={}", LOG_PREFIX,
                    level.dimension().location(), campaign.nation(), campaign.id(), reason, minutesToTicks(minutes));
        } else {
            CampaignFailureLog.record("RatCampaign", "campaign result=FAILURE dimension=" + level.dimension().location()
                    + " nation=" + campaign.nation() + " campaign=" + campaign.id() + " reason=" + reason);
        }
        notifyNearby(level, campaign.rally(), "鼠族战役" + (victory ? "胜利：" : "失败：") + reason);
    }

    public static List<String> status(ServerLevel level) {
        RatNationsCampaignSavedData data = RatNationsCampaignSavedData.get(level);
        List<String> lines = new ArrayList<>();
        Set<ResourceLocation> active = new HashSet<>();
        for (RatNationsCampaignSavedData.Campaign campaign : data.campaigns(level)) {
            active.add(campaign.nation());
            lines.add(campaign.nation() + " " + campaign.phase() + " zone=" + campaign.minChunkX() + "," + campaign.minChunkZ()
                    + "-" + campaign.maxChunkX() + "," + campaign.maxChunkZ() + " members=" + members(level, campaign).size());
        }
        long now = level.getGameTime();
        for (FactionDescriptor nation : RatNationsFactionApi.factions()) {
            if (active.contains(nation.id())) continue;
            RatNationsCampaignSavedData.Key key = RatNationsCampaignSavedData.Key.of(level, nation.id());
            long cooldown = data.cooldownUntil(level, nation.id());
            String cooldownText = cooldown > now ? "冷却 " + (cooldown - now) + " tick；" : "冷却就绪；";
            lines.add(nation.id() + " " + cooldownText + LAST_PLAN_OUTCOME.getOrDefault(key, "尚未执行规划扫描"));
        }
        return lines;
    }

    public static boolean abort(ServerLevel level, ResourceLocation nation) {
        RatNationsCampaignSavedData data = RatNationsCampaignSavedData.get(level);
        RatNationsCampaignSavedData.Campaign campaign = data.remove(level, nation);
        if (campaign == null) return false;
        for (AbstractMouseSoldierEntity soldier : members(level, campaign)) { soldier.setCampaignDirective(null); soldier.restoreConfiguredDisplayName(); }
        NEXT_PLAN_ATTEMPT.remove(campaign.key());
        return true;
    }

    private static void abortAll(ServerLevel level) {
        RatNationsCampaignSavedData data = RatNationsCampaignSavedData.get(level);
        for (RatNationsCampaignSavedData.Campaign campaign : data.campaigns(level)) abort(level, campaign.nation());
    }

    public static Metrics metrics() { return new Metrics(planningAttempts, candidateWarzones, pathProbes, campaignsStarted, campaignsCompleted, campaignsFailed, pauses); }

    static void retryAfterDeployment(ServerLevel level, String scope) {
        try {
            ResourceLocation nation = new ResourceLocation(scope);
            NEXT_PLAN_ATTEMPT.put(new RatNationsCampaignSavedData.Key(
                    level.dimension().location().toString(), nation), level.getGameTime());
        } catch (RuntimeException ignored) { }
    }

    private static boolean targetResolvedReason(String reason) {
        return "TARGET_WARZONE_FULLY_FRIENDLY".equals(reason)
                || "TARGET_NO_REACHABLE_OBJECTIVES".equals(reason);
    }

    private static void labelLeader(List<AbstractMouseSoldierEntity> members, MouseCampaignPhase phase) {
        if (members.isEmpty()) return;
        String label = switch (phase) { case MUSTER -> "【集结】"; case ADVANCE -> "【进攻】"; case OCCUPY, STABILIZE -> "【占领】"; case RETREAT -> "【撤退】"; case PAUSED -> "【待命】"; };
        AbstractMouseSoldierEntity leader = members.get(0);
        leader.setCustomName(Component.literal(label + leader.getName().getString().replaceAll("【[^】]+】", "")));
    }

    private static void notifyNearby(ServerLevel level, BlockPos pos, String text) {
        for (ServerPlayer player : level.players()) if (!player.isSpectator() && player.blockPosition().distSqr(pos) <= 128.0D * 128.0D) {
            player.displayClientMessage(Component.literal(text), false);
        }
    }

    private static long minutesToTicks(int minutes) { return Math.max(1L, Math.min(1440L, minutes)) * 1_200L; }

    private static void transition(ServerLevel level, RatNationsCampaignSavedData.Campaign campaign,
                                   MouseCampaignPhase phase, long now, String reason) {
        MouseCampaignPhase before = campaign.phase();
        campaign.setPhase(phase, now);
        LOGGER.info("{} phase dimension={} nation={} campaign={} from={} to={} reason={}", LOG_PREFIX,
                level.dimension().location(), campaign.nation(), campaign.id(), before, phase, reason);
    }

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
