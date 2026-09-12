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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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
    private static final int REGULAR_MIN = 8;
    private static final int REGULAR_MAX = 16;
    private static final int GRAND_MIN = 12;
    private static final int GRAND_MAX = 20;
    private static final long TICK_INTERVAL = 20L;
    private static final long PLAN_RETRY_TICKS = 1_200L;
    private static final long MUSTER_TIMEOUT_TICKS = 1_200L;
    private static final long STABILIZE_TICKS = 600L;
    private static final long RETREAT_TIMEOUT_TICKS = 1_800L;
    private static final long MOUND_TIMEOUT_TICKS = 1_200L;
    private static final Map<SporeCampaignSavedData.Key, Long> NEXT_PLAN_ATTEMPT = new HashMap<>();
    private static long planningAttempts, candidateWarzones, pathProbes, campaignsStarted, campaignsCompleted, campaignsFailed, pauses;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % TICK_INTERVAL != 0L) return;
        MinecraftServer server = event.getServer();
        CompatSavedData.Config config = CompatSavedData.get(server.overworld()).config();
        if (!config.sporeRegularCampaigns() && !config.sporeGrandCampaigns()) {
            for (ServerLevel level : server.getAllLevels()) abortAll(level);
            return;
        }
        if (BattleModes.isLayoutMode(server) || BattleModes.isCleanupMode(server)) return;
        for (ServerLevel level : server.getAllLevels()) tickLevel(level, config);
    }

    private static void tickLevel(ServerLevel level, CompatSavedData.Config config) {
        List<ServerPlayer> players = level.players().stream().filter(player -> player.isAlive() && !player.isSpectator()).toList();
        SporeCampaignSavedData data = SporeCampaignSavedData.get(level);
        if (!config.sporeRegularCampaigns()) abort(level, SporeCampaignSavedData.Type.REGULAR, false, "常规战役已关闭");
        if (!config.sporeGrandCampaigns()) abort(level, SporeCampaignSavedData.Type.GRAND, false, "大型远征已关闭");
        if (players.isEmpty()) return;

        long now = level.getGameTime();
        Map<ChunkPos, TerritoryControlApi.TerritoryView> visible = visibleTerritories(level, players);
        for (SporeCampaignSavedData.Campaign campaign : data.campaigns(level)) tickCampaign(level, players, visible, data, campaign, now, config);
        if (config.sporeRegularCampaigns()) prepare(level, visible, data, SporeCampaignSavedData.Type.REGULAR, now, config);
        if (config.sporeGrandCampaigns()) prepare(level, visible, data, SporeCampaignSavedData.Type.GRAND, now, config);
    }

    private static void tickCampaign(ServerLevel level, List<ServerPlayer> players,
                                     Map<ChunkPos, TerritoryControlApi.TerritoryView> visible,
                                     SporeCampaignSavedData data, SporeCampaignSavedData.Campaign campaign,
                                     long now, CompatSavedData.Config config) {
        List<Infected> members = members(level, campaign);
        List<Calamity> calamities = calamities(level, campaign);
        if (!isOperational(level, players, campaign)) {
            if (!campaign.paused()) { campaign.setPaused(true); pauses++; data.markChanged(); }
            return;
        }
        if (campaign.paused()) { campaign.setPaused(false); data.markChanged(); }
        int minimum = campaign.type() == SporeCampaignSavedData.Type.REGULAR ? REGULAR_MIN : GRAND_MIN;
        if (campaign.phase() != SporeCampaignSavedData.Phase.RETREAT && members.size() < minimum) {
            beginRetreat(level, data, campaign, members, calamities, now, "护卫兵力不足");
            return;
        }
        if (campaign.type() == SporeCampaignSavedData.Type.GRAND && campaign.phase() != SporeCampaignSavedData.Phase.RETREAT
                && calamities.isEmpty()) {
            beginRetreat(level, data, campaign, members, calamities, now, "灾厄单位失联");
            return;
        }

        switch (campaign.phase()) {
            case MUSTER -> {
                if (now >= campaign.launchAt() && mustered(campaign, members)) campaign.setPhase(SporeCampaignSavedData.Phase.ADVANCE, now);
                else if (now >= campaign.launchAt() && now - Math.max(campaign.phaseSince(), campaign.launchAt()) > MUSTER_TIMEOUT_TICKS) {
                    beginRetreat(level, data, campaign, members, calamities, now, "集结超时");
                    return;
                }
            }
            case ADVANCE -> {
                if (members.stream().filter(member -> campaign.contains(member.chunkPosition())).count() * 2 >= members.size()) {
                    campaign.setPhase(SporeCampaignSavedData.Phase.OCCUPY, now);
                }
            }
            case OCCUPY -> {
                List<Objective> hostile = hostileObjectives(level, campaign);
                if (hostile.isEmpty()) campaign.setPhase(SporeCampaignSavedData.Phase.STABILIZE, now);
                else if (needsObjectives(level, campaign, members, hostile)) {
                    List<BlockPos> objectives = reachableObjectives(level, campaign, members, hostile);
                    if (objectives.isEmpty()) campaign.setPhase(SporeCampaignSavedData.Phase.STABILIZE, now);
                    else campaign.assignObjectives(objectives);
                }
            }
            case STABILIZE -> {
                if (!hostileObjectives(level, campaign).isEmpty()) campaign.setPhase(SporeCampaignSavedData.Phase.OCCUPY, now);
                else if (now - campaign.phaseSince() >= STABILIZE_TICKS) {
                    if (campaign.type() == SporeCampaignSavedData.Type.GRAND && config.sporeCampaignMoundEstablishment()
                            && !calamities.isEmpty()) {
                        BlockPos mound = conqueredMoundAnchor(level, campaign);
                        if (mound != null) { campaign.setMoundAnchor(mound); campaign.setPhase(SporeCampaignSavedData.Phase.ESTABLISH_MOUND, now); }
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
                boolean home = members.stream().allMatch(member -> member.distanceToSqr(campaign.fallback().getX() + 0.5D,
                        campaign.fallback().getY(), campaign.fallback().getZ() + 0.5D) <= 256.0D);
                if (home || now - campaign.phaseSince() >= RETREAT_TIMEOUT_TICKS) {
                    finish(level, data, campaign, members, calamities, now, false, "小队撤退");
                    return;
                }
            }
        }
        applyDirectives(campaign, members, calamities);
        data.markChanged();
    }

    private static void prepare(ServerLevel level, Map<ChunkPos, TerritoryControlApi.TerritoryView> visible,
                                SporeCampaignSavedData data, SporeCampaignSavedData.Type type, long now,
                                CompatSavedData.Config config) {
        if (data.campaign(level, type) != null || now < data.cooldownUntil(level, type)) return;
        SporeCampaignSavedData.Key key = SporeCampaignSavedData.Key.of(level, type);
        if (now < NEXT_PLAN_ATTEMPT.getOrDefault(key, 0L)) return;
        NEXT_PLAN_ATTEMPT.put(key, now + PLAN_RETRY_TICKS);
        planningAttempts++;
        String controller = TerritoryControlApi.factionIdForMod(level, MOD_ID).orElse("");
        if (controller.isBlank()) return;
        List<ZoneCandidate> zones = frontierZones(level, visible, controller).stream()
                .filter(candidate -> data.campaigns(level).stream().noneMatch(active -> sharesBounds(active, candidate.bounds()))).toList();
        candidateWarzones += zones.size();
        if (zones.isEmpty()) return;
        List<Infected> available = availableInfected(level, visible, controller, data);
        int minimum = type == SporeCampaignSavedData.Type.REGULAR ? REGULAR_MIN : GRAND_MIN;
        if (available.size() < minimum) return;
        ZoneCandidate selected = zones.stream().min(Comparator.comparingDouble(zone -> nearestDistanceSqr(available, zone.rally()))).orElse(null);
        if (selected == null) return;
        available.sort(Comparator.comparingDouble(member -> member.distanceToSqr(selected.rally().getX() + 0.5D, selected.rally().getY(), selected.rally().getZ() + 0.5D)));
        int maximum = type == SporeCampaignSavedData.Type.REGULAR ? REGULAR_MAX : GRAND_MAX;
        List<Infected> members = selectReachable(available, selected, maximum);
        if (members.size() < minimum) return;

        List<Calamity> calamities = List.of();
        if (type == SporeCampaignSavedData.Type.GRAND) {
            List<Calamity> availableCalamities = availableCalamities(level, visible, controller, data);
            int required = availableCalamities.size() >= 4 ? 2 : 1;
            calamities = selectReachableCalamities(availableCalamities, selected, required);
            if (calamities.size() != required) return;
        }
        List<SporeCampaignSavedData.InfectedMember> snapshots = new ArrayList<>();
        for (int index = 0; index < members.size(); index++) {
            Infected member = members.get(index);
            snapshots.add(new SporeCampaignSavedData.InfectedMember(member.getUUID(), member.getLinked(), member.getSearchPos(),
                    selected.objectives().get(index % selected.objectives().size()).anchor()));
        }
        List<SporeCampaignSavedData.CalamityMember> calamitySnapshots = new ArrayList<>();
        for (int index = 0; index < calamities.size(); index++) {
            Calamity calamity = calamities.get(index);
            calamitySnapshots.add(new SporeCampaignSavedData.CalamityMember(calamity.getUUID(), calamity.getSearchArea(),
                    selected.objectives().get(index % selected.objectives().size()).anchor(), false, false));
        }
        SporeCampaignSavedData.Campaign campaign = new SporeCampaignSavedData.Campaign(UUID.randomUUID(), key,
                selected.bounds().minChunkX(), selected.bounds().minChunkZ(), selected.bounds().maxChunkX(), selected.bounds().maxChunkZ(),
                selected.rally(), selected.rally(), snapshots, calamitySnapshots, SporeCampaignSavedData.Phase.MUSTER, now, now);
        data.put(campaign);
        deployScent(level, campaign, config);
        campaignsStarted++;
        applyDirectives(campaign, members, calamities);
        notifyNearby(level, selected.rally(), (type == SporeCampaignSavedData.Type.GRAND ? "真菌大型远征" : "真菌蜂群") + "正在集结，准备进攻战区。");
    }

    private static List<Infected> selectReachable(List<Infected> available, ZoneCandidate selected, int maximum) {
        List<Infected> result = new ArrayList<>();
        for (Infected candidate : available) {
            if (result.size() == maximum) break;
            Objective objective = selected.objectives().get(result.size() % selected.objectives().size());
            pathProbes += 2;
            if (CampaignTerrainResolver.canReach(candidate, selected.rally()) && CampaignTerrainResolver.canReach(candidate, objective.anchor())) result.add(candidate);
        }
        return result;
    }

    private static List<Calamity> selectReachableCalamities(List<Calamity> available, ZoneCandidate selected, int required) {
        List<Calamity> result = new ArrayList<>();
        for (Calamity candidate : available) {
            if (result.size() == required) break;
            Objective objective = selected.objectives().get(result.size() % selected.objectives().size());
            pathProbes++;
            if (CampaignTerrainResolver.canReach(candidate, objective.anchor())) result.add(candidate);
        }
        return result;
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
                case MUSTER -> campaign.rally();
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
            if (state != null && !state.moundRequested() && CampaignTerrainResolver.canReach(calamity, anchor)) {
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
        for (var entry : TerritoryControlApi.territoriesInRange(level, campaign.minChunkX(), campaign.minChunkZ(), campaign.maxChunkX(), campaign.maxChunkZ())) {
            if (campaign.isIgnored(entry.getKey()) || !hostile(level, controller, entry.getValue())) continue;
            BlockPos anchor = CampaignTerrainResolver.findLandAnchor(level, entry.getKey(), campaign.rally()).orElse(null);
            if (anchor == null) { campaign.ignore(entry.getKey()); continue; }
            result.add(new Objective(entry.getKey(), anchor));
        }
        result.sort(Comparator.comparingDouble(value -> chunkDistanceSqr(value.chunk(), campaign.rally())));
        return result;
    }

    private static boolean needsObjectives(ServerLevel level, SporeCampaignSavedData.Campaign campaign,
                                           List<Infected> members, List<Objective> hostile) {
        Set<ChunkPos> chunks = hostile.stream().map(Objective::chunk).collect(java.util.stream.Collectors.toSet());
        if (campaign.members().stream().noneMatch(member -> chunks.contains(new ChunkPos(member.objective())))) return true;
        int reachable = 0;
        for (Infected member : members) {
            SporeCampaignSavedData.InfectedMember state = campaign.members().stream()
                    .filter(value -> value.id().equals(member.getUUID())).findFirst().orElse(null);
            if (state != null && CampaignTerrainResolver.canReach(member, state.objective())) reachable++;
        }
        return reachable < CampaignTerrainResolver.twoThirds(members.size());
    }

    private static List<BlockPos> reachableObjectives(ServerLevel level, SporeCampaignSavedData.Campaign campaign,
                                                       List<Infected> members, List<Objective> candidates) {
        List<BlockPos> result = new ArrayList<>();
        for (Objective candidate : candidates) {
            if (campaign.isIgnored(candidate.chunk())) continue;
            pathProbes += members.size();
            if (CampaignTerrainResolver.reachable(members, candidate.anchor()).size() < CampaignTerrainResolver.twoThirds(members.size())) {
                campaign.ignore(candidate.chunk());
                continue;
            }
            result.add(candidate.anchor());
            if (result.size() == 3) break;
        }
        return result;
    }

    private static BlockPos conqueredMoundAnchor(ServerLevel level, SporeCampaignSavedData.Campaign campaign) {
        for (SporeCampaignSavedData.InfectedMember member : campaign.members()) {
            BlockPos anchor = CampaignTerrainResolver.findLandAnchor(level, new ChunkPos(member.objective()), member.objective()).orElse(null);
            if (anchor != null && TerritoryControlApi.isOwnedByModFaction(level, anchor, MOD_ID)) return anchor;
        }
        return null;
    }

    private static void beginRetreat(ServerLevel level, SporeCampaignSavedData data, SporeCampaignSavedData.Campaign campaign,
                                     List<Infected> members, List<Calamity> calamities, long now, String reason) {
        if (campaign.phase() == SporeCampaignSavedData.Phase.RETREAT) return;
        campaign.setPhase(SporeCampaignSavedData.Phase.RETREAT, now);
        applyDirectives(campaign, members, calamities);
        data.markChanged();
        notifyNearby(level, campaign.rally(), "真菌战役撤退：" + reason);
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
        return members.stream().filter(member -> member.distanceToSqr(campaign.rally().getX() + 0.5D, campaign.rally().getY(), campaign.rally().getZ() + 0.5D) <= 256.0D).count() * 4 >= members.size() * 3;
    }

    private static Map<ChunkPos, TerritoryControlApi.TerritoryView> visibleTerritories(ServerLevel level, List<ServerPlayer> players) {
        Map<ChunkPos, TerritoryControlApi.TerritoryView> result = new LinkedHashMap<>();
        int radius = Math.max(0, level.getServer().getPlayerList().getViewDistance());
        for (ServerPlayer player : players) {
            ChunkPos center = player.chunkPosition();
            for (var entry : TerritoryControlApi.territoriesInRange(level, center.x - radius, center.z - radius, center.x + radius, center.z + radius)) {
                if (level.hasChunk(entry.getKey().x, entry.getKey().z)) result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static List<ZoneCandidate> frontierZones(ServerLevel level, Map<ChunkPos, TerritoryControlApi.TerritoryView> visible, String controller) {
        Map<Warzone.Bounds, ZoneBuilder> zones = new LinkedHashMap<>();
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level).warzoneConfig().normalized().sizeChunks();
        for (var entry : visible.entrySet()) {
            if (!hostile(level, controller, entry.getValue())) continue;
            ChunkPos friendly = friendlyNeighbor(visible, entry.getKey(), controller);
            if (friendly == null) continue;
            BlockPos rally = CampaignTerrainResolver.findLandAnchor(level, friendly, null).orElse(null);
            BlockPos objective = CampaignTerrainResolver.findLandAnchor(level, entry.getKey(), rally).orElse(null);
            if (rally == null || objective == null) continue;
            Warzone.Bounds bounds = Warzone.boundsFor(entry.getKey(), size);
            ZoneBuilder builder = zones.computeIfAbsent(bounds, ignored -> new ZoneBuilder(bounds, rally));
            builder.objectives.add(new Objective(entry.getKey(), objective));
        }
        List<ZoneCandidate> result = new ArrayList<>();
        for (ZoneBuilder builder : zones.values()) {
            List<Objective> objectives = builder.objectives.stream().distinct()
                    .sorted(Comparator.comparingDouble(value -> chunkDistanceSqr(value.chunk(), builder.rally))).limit(3).toList();
            if (!objectives.isEmpty()) result.add(new ZoneCandidate(builder.bounds, builder.rally, objectives));
        }
        return result;
    }

    private static boolean hostile(ServerLevel level, String controller, TerritoryControlApi.TerritoryView territory) {
        return hostileFaction(level, controller, territory.ownerFaction()) || hostileFaction(level, controller, territory.progressFaction())
                || hostileFaction(level, controller, territory.contestFaction());
    }
    private static boolean hostileFaction(ServerLevel level, String controller, String other) {
        return other != null && !other.isBlank() && !other.equals(controller) && !TerritoryControlApi.areFactionsSameOrAllied(level, controller, other);
    }
    private static ChunkPos friendlyNeighbor(Map<ChunkPos, TerritoryControlApi.TerritoryView> visible, ChunkPos enemy, String controller) {
        for (int[] offset : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            ChunkPos candidate = new ChunkPos(enemy.x + offset[0], enemy.z + offset[1]);
            TerritoryControlApi.TerritoryView view = visible.get(candidate);
            if (view != null && controller.equals(view.ownerFaction())) return candidate;
        }
        return null;
    }

    private static List<Infected> availableInfected(ServerLevel level, Map<ChunkPos, TerritoryControlApi.TerritoryView> visible,
                                                     String controller, SporeCampaignSavedData data) {
        Set<UUID> assigned = assignedIds(level, data);
        Set<UUID> seen = new HashSet<>();
        List<Infected> result = new ArrayList<>();
        for (var entry : visible.entrySet()) {
            if (!controller.equals(entry.getValue().ownerFaction())) continue;
            ChunkPos chunk = entry.getKey();
            AABB area = new AABB(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(), chunk.getMaxBlockX() + 1.0D, level.getMaxBuildHeight(), chunk.getMaxBlockZ() + 1.0D);
            for (Infected infected : level.getEntitiesOfClass(Infected.class, area, entity -> entity.isAlive() && !entity.isRemoved())) {
                if (seen.add(infected.getUUID()) && !assigned.contains(infected.getUUID()) && infected.getTarget() == null && infected.getSearchPos() == null) result.add(infected);
            }
        }
        return result;
    }

    private static List<Calamity> availableCalamities(ServerLevel level, Map<ChunkPos, TerritoryControlApi.TerritoryView> visible,
                                                       String controller, SporeCampaignSavedData data) {
        Set<UUID> assigned = assignedIds(level, data);
        Set<UUID> seen = new HashSet<>();
        List<Calamity> result = new ArrayList<>();
        for (var entry : visible.entrySet()) {
            if (!controller.equals(entry.getValue().ownerFaction())) continue;
            ChunkPos chunk = entry.getKey();
            AABB area = new AABB(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(), chunk.getMaxBlockX() + 1.0D, level.getMaxBuildHeight(), chunk.getMaxBlockZ() + 1.0D);
            for (Calamity calamity : level.getEntitiesOfClass(Calamity.class, area, entity -> entity.isAlive() && !entity.isRemoved())) {
                if (seen.add(calamity.getUUID()) && !assigned.contains(calamity.getUUID()) && calamity.getTarget() == null && calamity.getSearchArea().equals(BlockPos.ZERO)) result.add(calamity);
            }
        }
        return result;
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
    private static double nearestDistanceSqr(List<Infected> members, BlockPos point) { return members.stream().mapToDouble(member -> member.distanceToSqr(point.getX() + 0.5D, point.getY(), point.getZ() + 0.5D)).min().orElse(Double.MAX_VALUE); }
    private static double chunkDistanceSqr(ChunkPos chunk, BlockPos point) { double x = chunk.getMiddleBlockX() - point.getX(), z = chunk.getMiddleBlockZ() - point.getZ(); return x * x + z * z; }

    private record Objective(ChunkPos chunk, BlockPos anchor) { }
    private static final class ZoneBuilder { private final Warzone.Bounds bounds; private final BlockPos rally; private final List<Objective> objectives = new ArrayList<>(); private ZoneBuilder(Warzone.Bounds bounds, BlockPos rally) { this.bounds = bounds; this.rally = rally; } }
    private record ZoneCandidate(Warzone.Bounds bounds, BlockPos rally, List<Objective> objectives) { }
    public record Metrics(long planningAttempts, long candidateWarzones, long pathProbes, long campaignsStarted, long campaignsCompleted, long campaignsFailed, long pauses) { }
}
