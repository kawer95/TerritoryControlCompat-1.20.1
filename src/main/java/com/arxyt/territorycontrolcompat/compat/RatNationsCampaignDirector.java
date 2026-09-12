package com.arxyt.territorycontrolcompat.compat;

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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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

/** Server-authoritative autonomous Rat Nations campaigns against loaded hostile warzones. */
public final class RatNationsCampaignDirector {
    private static final int MIN_SQUAD = 4;
    private static final int MAX_SQUAD = 8;
    private static final int CLOSE_THREAT_RANGE = 12;
    private static final long TICK_INTERVAL = 20L;
    private static final long PLAN_RETRY_TICKS = 1_200L;
    private static final long MUSTER_TIMEOUT_TICKS = 1_200L;
    private static final long STABILIZE_TICKS = 600L;
    private static final long RETREAT_TIMEOUT_TICKS = 1_800L;
    private static final Map<RatNationsCampaignSavedData.Key, Long> NEXT_PLAN_ATTEMPT = new HashMap<>();
    private static long planningAttempts, candidateWarzones, pathProbes, campaignsStarted, campaignsCompleted, campaignsFailed, pauses;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % TICK_INTERVAL != 0L) return;
        MinecraftServer server = event.getServer();
        if (!CompatSavedData.get(server.overworld()).config().ratNationsCampaigns()) {
            for (ServerLevel level : server.getAllLevels()) abortAll(level);
            return;
        }
        if (BattleModes.isLayoutMode(server) || BattleModes.isCleanupMode(server)) return;
        for (ServerLevel level : server.getAllLevels()) tickLevel(level);
    }

    private static void tickLevel(ServerLevel level) {
        List<ServerPlayer> players = level.players().stream().filter(player -> player.isAlive() && !player.isSpectator()).toList();
        if (players.isEmpty()) return;
        RatNationsCampaignSavedData data = RatNationsCampaignSavedData.get(level);
        long now = level.getGameTime();
        Map<ChunkPos, TerritoryControlApi.TerritoryView> visible = visibleTerritories(level, players);
        for (RatNationsCampaignSavedData.Campaign campaign : data.campaigns(level)) tickCampaign(level, players, visible, data, campaign, now);
        for (FactionDescriptor nation : RatNationsFactionApi.factions()) {
            if (data.campaign(level, nation.id()) != null) continue;
            RatNationsCampaignSavedData.Key key = RatNationsCampaignSavedData.Key.of(level, nation.id());
            long cooldown = data.cooldownUntil(level, nation.id());
            if (cooldown == 0L) {
                cooldown = now + minutesToTicks(CompatSavedData.get(level).config().ratNationsVictoryCooldownMinutes());
                data.setCooldownUntil(level, nation.id(), cooldown);
            }
            long retry = NEXT_PLAN_ATTEMPT.getOrDefault(key, 0L);
            if (now < retry) continue;
            NEXT_PLAN_ATTEMPT.put(key, now + PLAN_RETRY_TICKS);
            prepareCampaign(level, visible, data, nation.id(), cooldown, now);
        }
    }

    private static void tickCampaign(ServerLevel level, List<ServerPlayer> players,
                                     Map<ChunkPos, TerritoryControlApi.TerritoryView> visible,
                                     RatNationsCampaignSavedData data, RatNationsCampaignSavedData.Campaign campaign, long now) {
        List<AbstractMouseSoldierEntity> members = members(level, campaign);
        if (!isCampaignOperational(players, level, campaign)) {
            if (!campaign.paused()) { campaign.setPaused(true); pauses++; data.markChanged(); }
            applyDirectives(level, campaign, members);
            return;
        }
        if (campaign.paused()) { campaign.setPaused(false); data.markChanged(); }
        if (members.size() < (campaign.phase() == MouseCampaignPhase.RETREAT ? 1 : 3)) {
            beginRetreat(level, data, campaign, members, now, "战役兵力不足");
            return;
        }
        switch (campaign.phase()) {
            case MUSTER -> {
                if (now >= campaign.launchAt() && !hasHostileControl(level, campaign)) {
                    finish(level, data, campaign, members, now, true, "目标战区已被盟友稳固");
                    return;
                }
                if (now >= campaign.launchAt() && mustered(campaign, members)) campaign.setPhase(MouseCampaignPhase.ADVANCE, now);
                else if (now >= campaign.launchAt() && now - Math.max(campaign.phaseSince(), campaign.launchAt()) > MUSTER_TIMEOUT_TICKS) {
                    beginRetreat(level, data, campaign, members, now, "集结超时");
                    return;
                }
            }
            case ADVANCE -> {
                if (members.stream().filter(member -> campaign.contains(member.chunkPosition())).count() * 2 >= members.size()) {
                    campaign.setPhase(MouseCampaignPhase.OCCUPY, now);
                }
            }
            case OCCUPY -> {
                List<ChunkPos> hostile = hostileControlChunks(level, campaign);
                if (hostile.isEmpty()) campaign.setPhase(MouseCampaignPhase.STABILIZE, now);
                else if (campaign.members().stream().noneMatch(member -> hostile.contains(new ChunkPos(member.objective())))) {
                    hostile.sort(Comparator.comparingDouble(chunk -> chunkDistSqr(chunk, campaign.rally())));
                    List<BlockPos> objectives = reachableObjectives(level, campaign, members, hostile);
                    if (objectives.isEmpty()) campaign.setPhase(MouseCampaignPhase.STABILIZE, now);
                    else campaign.assignObjectives(objectives);
                }
            }
            case STABILIZE -> {
                if (hasHostileControl(level, campaign)) campaign.setPhase(MouseCampaignPhase.OCCUPY, now);
                else if (now - campaign.phaseSince() >= STABILIZE_TICKS) {
                    finish(level, data, campaign, members, now, true, "战区已稳固");
                    return;
                }
            }
            case RETREAT -> {
                boolean home = members.stream().allMatch(member -> member.distanceToSqr(campaign.fallback().getX() + 0.5D,
                        campaign.fallback().getY(), campaign.fallback().getZ() + 0.5D) <= 256.0D);
                if (home || now - campaign.phaseSince() >= RETREAT_TIMEOUT_TICKS) {
                    finish(level, data, campaign, members, now, false, "小队撤退");
                    return;
                }
            }
            case PAUSED -> { }
        }
        applyDirectives(level, campaign, members);
        data.markChanged();
    }

    private static void prepareCampaign(ServerLevel level, Map<ChunkPos, TerritoryControlApi.TerritoryView> visible,
                                        RatNationsCampaignSavedData data, ResourceLocation nation, long launchAt, long now) {
        planningAttempts++;
        String controller = TerritoryControlApi.factionForExternal(level, RatNationsFactionProvider.PROVIDER_ID, nation.toString())
                .map(TerritoryControlApi.FactionView::id).orElse("");
        if (controller.isBlank()) return;
        List<ZoneCandidate> zones = hostileFrontierZones(level, visible, controller);
        candidateWarzones += zones.size();
        if (zones.isEmpty()) return;
        List<AbstractMouseSoldierEntity> available = availableSoldiers(level, visible, nation, controller);
        if (available.size() < MIN_SQUAD) return;
        ZoneCandidate selected = zones.stream().min(Comparator.comparingDouble(zone -> nearestDistanceSqr(available, zone.rally()))).orElse(null);
        if (selected == null) return;
        available.sort(Comparator.comparingDouble(member -> member.distanceToSqr(selected.rally().getX() + 0.5D,
                selected.rally().getY(), selected.rally().getZ() + 0.5D)));
        List<AbstractMouseSoldierEntity> squad = new ArrayList<>(available.subList(0, Math.min(MAX_SQUAD, available.size())));
        if (squad.size() < MIN_SQUAD) return;
        pathProbes += squad.size();
        if (CampaignTerrainResolver.reachable(squad, selected.rally()).size() < CampaignTerrainResolver.twoThirds(squad.size())) return;
        RatNationsCampaignSavedData.Campaign campaign = new RatNationsCampaignSavedData.Campaign(UUID.randomUUID(),
                RatNationsCampaignSavedData.Key.of(level, nation), selected.bounds().minChunkX(), selected.bounds().minChunkZ(),
                selected.bounds().maxChunkX(), selected.bounds().maxChunkZ(), selected.rally(), selected.rally(), List.of(),
                MouseCampaignPhase.MUSTER, launchAt, now);
        List<BlockPos> objectives = reachableObjectives(level, campaign, squad, selected.objectives());
        if (objectives.isEmpty()) return;
        List<RatNationsCampaignSavedData.Member> members = new ArrayList<>();
        for (int index = 0; index < squad.size(); index++) members.add(new RatNationsCampaignSavedData.Member(squad.get(index).getUUID(),
                objectives.get(index % objectives.size())));
        RatNationsCampaignSavedData.Campaign draft = campaign;
        campaign = new RatNationsCampaignSavedData.Campaign(draft.id(), draft.key(), draft.minChunkX(), draft.minChunkZ(),
                draft.maxChunkX(), draft.maxChunkZ(), draft.rally(), draft.fallback(), members, MouseCampaignPhase.MUSTER, launchAt, now);
        for (long ignored : draft.ignoredChunks()) campaign.ignore(new ChunkPos(ignored));
        data.put(campaign);
        campaignsStarted++;
        applyDirectives(level, campaign, squad);
        labelLeader(squad, MouseCampaignPhase.MUSTER);
        notifyNearby(level, selected.rally(), "鼠族 " + nation.getPath() + " 小队正在集结，准备进攻战区。");
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

    private static List<ZoneCandidate> hostileFrontierZones(ServerLevel level, Map<ChunkPos, TerritoryControlApi.TerritoryView> visible, String controller) {
        Map<Warzone.Bounds, ZoneBuilder> zones = new LinkedHashMap<>();
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level).warzoneConfig().normalized().sizeChunks();
        for (var entry : visible.entrySet()) {
            if (!hostile(level, controller, entry.getValue())) continue;
            ChunkPos friendly = friendlyNeighbor(level, visible, entry.getKey(), controller);
            if (friendly == null) continue;
            BlockPos rally = CampaignTerrainResolver.findLandAnchor(level, friendly, null).orElse(null);
            if (rally == null) continue;
            Warzone.Bounds bounds = Warzone.boundsFor(entry.getKey(), size);
            ZoneBuilder builder = zones.computeIfAbsent(bounds, ignored -> new ZoneBuilder(bounds, rally));
            builder.objectives.add(entry.getKey());
        }
        List<ZoneCandidate> result = new ArrayList<>();
        for (ZoneBuilder builder : zones.values()) {
            builder.objectives.sort(Comparator.comparingDouble(chunk -> chunkDistSqr(chunk, builder.rally)));
            List<ChunkPos> distinct = builder.objectives.stream().distinct().filter(chunk -> landPosition(level, chunk, 0) != null).limit(3).toList();
            if (!distinct.isEmpty()) result.add(new ZoneCandidate(builder.bounds, builder.rally, distinct));
        }
        return result;
    }

    private static boolean hostile(ServerLevel level, String controller, TerritoryControlApi.TerritoryView value) {
        return hostileFaction(level, controller, value.ownerFaction()) || hostileFaction(level, controller, value.progressFaction())
                || hostileFaction(level, controller, value.contestFaction());
    }

    private static boolean hostileFaction(ServerLevel level, String controller, String other) {
        return other != null && !other.isBlank() && !other.equals(controller)
                && !TerritoryControlApi.areFactionsSameOrAllied(level, controller, other);
    }

    private static ChunkPos friendlyNeighbor(ServerLevel level, Map<ChunkPos, TerritoryControlApi.TerritoryView> visible, ChunkPos enemy, String controller) {
        for (int[] offset : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            ChunkPos candidate = new ChunkPos(enemy.x + offset[0], enemy.z + offset[1]);
            TerritoryControlApi.TerritoryView view = visible.get(candidate);
            if (view != null && controller.equals(view.ownerFaction()) && landPosition(level, candidate, 0) != null) return candidate;
        }
        return null;
    }

    private static List<AbstractMouseSoldierEntity> availableSoldiers(ServerLevel level,
                                                                        Map<ChunkPos, TerritoryControlApi.TerritoryView> visible,
                                                                        ResourceLocation nation, String controller) {
        Set<UUID> seen = new HashSet<>();
        List<AbstractMouseSoldierEntity> result = new ArrayList<>();
        for (ChunkPos chunk : visible.keySet()) {
            TerritoryControlApi.TerritoryView view = visible.get(chunk);
            if (view == null || !controller.equals(view.ownerFaction())) continue;
            AABB area = new AABB(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(),
                    chunk.getMaxBlockX() + 1.0D, level.getMaxBuildHeight(), chunk.getMaxBlockZ() + 1.0D);
            for (AbstractMouseSoldierEntity soldier : level.getEntitiesOfClass(AbstractMouseSoldierEntity.class, area,
                    entity -> entity.isAlive() && !entity.isRemoved())) {
                if (seen.add(soldier.getUUID()) && nation.equals(soldier.ratNationsFactionId()) && !soldier.isCampaignAssigned()
                        && soldier.getTarget() == null) result.add(soldier);
            }
        }
        return result;
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
            BlockPos objective = campaign.phase() == MouseCampaignPhase.MUSTER ? campaign.rally()
                    : objectives.getOrDefault(soldier.getUUID(), campaign.rally());
            soldier.setCampaignDirective(new MouseCampaignDirective(campaign.id(), campaign.phase(), campaign.minChunkX(), campaign.minChunkZ(),
                    campaign.maxChunkX(), campaign.maxChunkZ(), objective, campaign.fallback(), CLOSE_THREAT_RANGE));
        }
        labelLeader(members, campaign.phase());
    }

    private static boolean mustered(RatNationsCampaignSavedData.Campaign campaign, List<AbstractMouseSoldierEntity> members) {
        return members.stream().filter(member -> member.distanceToSqr(campaign.rally().getX() + 0.5D,
                campaign.rally().getY(), campaign.rally().getZ() + 0.5D) <= 256.0D).count() * 4 >= members.size() * 3;
    }

    private static boolean hasHostileControl(ServerLevel level, RatNationsCampaignSavedData.Campaign campaign) {
        return !hostileControlChunks(level, campaign).isEmpty();
    }

    private static List<ChunkPos> hostileControlChunks(ServerLevel level, RatNationsCampaignSavedData.Campaign campaign) {
        String controller = TerritoryControlApi.factionForExternal(level, RatNationsFactionProvider.PROVIDER_ID, campaign.nation().toString())
                .map(TerritoryControlApi.FactionView::id).orElse("");
        if (controller.isBlank()) return List.of();
        List<ChunkPos> result = new ArrayList<>();
        for (var entry : TerritoryControlApi.territoriesInRange(level, campaign.minChunkX(), campaign.minChunkZ(),
                campaign.maxChunkX(), campaign.maxChunkZ())) if (hostile(level, controller, entry.getValue())) {
            if (campaign.isIgnored(entry.getKey())) continue;
            if (landPosition(level, entry.getKey(), 0) == null) { campaign.ignore(entry.getKey()); continue; }
            result.add(entry.getKey());
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

    private static void beginRetreat(ServerLevel level, RatNationsCampaignSavedData data,
                                     RatNationsCampaignSavedData.Campaign campaign, List<AbstractMouseSoldierEntity> members,
                                     long now, String reason) {
        if (campaign.phase() == MouseCampaignPhase.RETREAT) return;
        campaign.setPhase(MouseCampaignPhase.RETREAT, now);
        applyDirectives(level, campaign, members);
        notifyNearby(level, campaign.rally(), "鼠族战役撤退：" + reason);
        data.markChanged();
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
        notifyNearby(level, campaign.rally(), "鼠族战役" + (victory ? "胜利：" : "失败：") + reason);
    }

    public static List<String> status(ServerLevel level) {
        RatNationsCampaignSavedData data = RatNationsCampaignSavedData.get(level);
        List<String> lines = new ArrayList<>();
        for (RatNationsCampaignSavedData.Campaign campaign : data.campaigns(level)) {
            lines.add(campaign.nation() + " " + campaign.phase() + " zone=" + campaign.minChunkX() + "," + campaign.minChunkZ()
                    + "-" + campaign.maxChunkX() + "," + campaign.maxChunkZ() + " members=" + members(level, campaign).size());
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

    private static List<BlockPos> reachableObjectives(ServerLevel level, RatNationsCampaignSavedData.Campaign campaign,
                                                       List<? extends Mob> members, List<ChunkPos> candidates) {
        List<BlockPos> result = new ArrayList<>();
        int index = 0;
        for (ChunkPos chunk : candidates) {
            if (campaign.isIgnored(chunk)) continue;
            BlockPos point = landPosition(level, chunk, index++);
            if (point == null) {
                campaign.ignore(chunk);
                continue;
            }
            pathProbes += members.size();
            if (CampaignTerrainResolver.reachable(members, point).size() < CampaignTerrainResolver.twoThirds(members.size())) {
                campaign.ignore(chunk);
                continue;
            }
            result.add(point);
            if (result.size() == 3) break;
        }
        return result;
    }

    private static BlockPos landPosition(ServerLevel level, ChunkPos chunk, int index) {
        int x = chunk.getMiddleBlockX() + ((index & 1) == 0 ? -3 : 3);
        int z = chunk.getMiddleBlockZ() + ((index & 2) == 0 ? -3 : 3);
        return CampaignTerrainResolver.findLandAnchor(level, chunk, new BlockPos(x, level.getMinBuildHeight(), z)).orElse(null);
    }
    private static double nearestDistanceSqr(List<AbstractMouseSoldierEntity> soldiers, BlockPos point) { return soldiers.stream().mapToDouble(soldier -> soldier.distanceToSqr(point.getX() + 0.5D, point.getY(), point.getZ() + 0.5D)).min().orElse(Double.MAX_VALUE); }
    private static double chunkDistSqr(ChunkPos chunk, BlockPos point) { double x = chunk.getMiddleBlockX() - point.getX(), z = chunk.getMiddleBlockZ() - point.getZ(); return x * x + z * z; }

    private record ZoneBuilder(Warzone.Bounds bounds, BlockPos rally, List<ChunkPos> objectives) { private ZoneBuilder(Warzone.Bounds bounds, BlockPos rally) { this(bounds, rally, new ArrayList<>()); } }
    private record ZoneCandidate(Warzone.Bounds bounds, BlockPos rally, List<ChunkPos> objectives) { }
    public record Metrics(long planningAttempts, long candidateWarzones, long pathProbes, long campaignsStarted, long campaignsCompleted, long campaignsFailed, long pauses) { }
}
