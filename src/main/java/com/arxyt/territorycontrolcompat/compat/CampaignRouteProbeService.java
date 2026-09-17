package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Starts at most one route-probe entity per tick and at most two across the server. */
public final class CampaignRouteProbeService {
    private static final int MAX_ACTIVE = 2;
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    public CampaignRouteProbeService() { }

    static boolean submit(ServerLevel level, CampaignPlanningService.JobKey key, String controller,
                          CampaignStrategicPlanner.Option option, net.minecraft.core.BlockPos start,
                          Consumer<Outcome> callback) {
        if (ACTIVE.values().stream().anyMatch(active -> active.key.equals(key))
                || PENDING.stream().anyMatch(pending -> pending.key.equals(key))) return false;
        PENDING.add(new Pending(level, key, controller, option, start.immutable(), callback));
        return true;
    }

    static void complete(UUID probeId, boolean success, String reason) {
        Active active = ACTIVE.remove(probeId);
        if (active != null) active.callback.accept(new Outcome(active.option, success, reason));
    }

    static int activeCount() { return ACTIVE.size(); }
    static int pendingCount() { return PENDING.size(); }

    static void invalidateTerritory(ServerLevel level, net.minecraft.world.level.ChunkPos chunk) {
        java.util.List<Active> invalidated = new java.util.ArrayList<>();
        ACTIVE.entrySet().removeIf(entry -> {
            Active active = entry.getValue();
            if (active.probe.level() != level) return false;
            String reason = invalidationReason(level, active.controller, active.option, chunk);
            if (reason == null) return false;
            active.probe.discard();
            CampaignUnitReservations.release(active.key);
            invalidated.add(active.withReason(reason));
            return true;
        });
        invalidated.forEach(active -> active.callback.accept(new Outcome(active.option, false, active.reason)));
    }

    static void invalidateTerrain(ServerLevel level, net.minecraft.world.level.ChunkPos chunk) {
        // The native probe can reroute around dynamic block changes. Do not turn routine combat
        // terrain updates inside a hostile target into a false territory-condition failure.
    }

    static void cancelDirector(String director) {
        PENDING.removeIf(pending -> {
            if (!pending.key.director().equals(director)) return false;
            CampaignUnitReservations.release(pending.key);
            return true;
        });
        ACTIVE.entrySet().removeIf(entry -> {
            if (!entry.getValue().key.director().equals(director)) return false;
            CampaignUnitReservations.release(entry.getValue().key);
            entry.getValue().probe.discard();
            return true;
        });
    }

    static void cancelDimension(String dimension) {
        PENDING.removeIf(pending -> {
            if (!pending.key.dimension().equals(dimension)) return false;
            CampaignUnitReservations.release(pending.key);
            return true;
        });
        ACTIVE.entrySet().removeIf(entry -> {
            if (!entry.getValue().key.dimension().equals(dimension)) return false;
            CampaignUnitReservations.release(entry.getValue().key);
            entry.getValue().probe.discard();
            return true;
        });
    }

    private static boolean contains(CampaignStrategicPlanner.ZoneKey zone,
                                    net.minecraft.world.level.ChunkPos chunk, int size) {
        return chunk.x >= zone.x() * size && chunk.x < zone.x() * size + size
                && chunk.z >= zone.z() * size && chunk.z < zone.z() * size + size;
    }

    private static boolean contains(CampaignStrategicPlanner.ChunkKey expected,
                                    net.minecraft.world.level.ChunkPos chunk) {
        return expected.x() == chunk.x && expected.z() == chunk.z;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.size() >= MAX_ACTIVE || PENDING.isEmpty()) return;
        Pending pending = PENDING.poll();
        if (pending.level.getServer() != event.getServer()) return;
        String invalid = invalidationReason(pending.level, pending.controller, pending.option, null);
        if (invalid != null) {
            CampaignUnitReservations.release(pending.key);
            pending.callback.accept(new Outcome(pending.option, false, invalid));
            return;
        }
        net.minecraft.core.BlockPos start = pending.start;
        CampaignStrategicPlanner.Point target = pending.option.candidate().targetAnchor();
        if (!pending.level.hasChunk(start.getX() >> 4, start.getZ() >> 4)
                || !pending.level.hasChunk(target.x() >> 4, target.z() >> 4)) {
            pending.callback.accept(new Outcome(pending.option, false, "PROBE_CHUNK_UNLOADED"));
            return;
        }
        CampaignRouteProbeEntity probe = CampaignProbeRegistry.ROUTE_PROBE.get().create(pending.level);
        if (probe == null) {
            pending.callback.accept(new Outcome(pending.option, false, "PROBE_CREATE_FAILED"));
            return;
        }
        UUID id = UUID.randomUUID();
        probe.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
        probe.configure(id, new net.minecraft.core.BlockPos(target.x(), target.y(), target.z()),
                pending.option.probeWidth(), pending.option.probeHeight());
        ACTIVE.put(id, new Active(pending.key, pending.controller, pending.option, pending.callback, probe, null));
        if (!pending.level.addFreshEntity(probe)) {
            ACTIVE.remove(id);
            pending.callback.accept(new Outcome(pending.option, false, "PROBE_ADD_FAILED"));
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        PENDING.removeIf(pending -> pending.level == level);
        ACTIVE.entrySet().removeIf(entry -> {
            if (entry.getValue().probe.level() != level) return false;
            entry.getValue().probe.discard();
            return true;
        });
    }

    record Outcome(CampaignStrategicPlanner.Option option, boolean success, String reason) { }
    private record Pending(ServerLevel level, CampaignPlanningService.JobKey key, String controller,
                           CampaignStrategicPlanner.Option option, net.minecraft.core.BlockPos start,
                           Consumer<Outcome> callback) { }
    private record Active(CampaignPlanningService.JobKey key, String controller,
                          CampaignStrategicPlanner.Option option, Consumer<Outcome> callback,
                          CampaignRouteProbeEntity probe, String reason) {
        Active withReason(String value) { return new Active(key, controller, option, callback, probe, value); }
    }

    private static String invalidationReason(ServerLevel level, String controller,
                                             CampaignStrategicPlanner.Option option,
                                             net.minecraft.world.level.ChunkPos changed) {
        CampaignStrategicPlanner.Candidate candidate = option.candidate();
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level)
                .warzoneConfig().normalized().sizeChunks();
        if (changed == null || contains(candidate.target(), changed, size)) {
            CampaignTerritoryConditions.TargetState targetState = CampaignTerritoryConditions.targetState(
                    level, controller, candidate.target());
            if (targetState == CampaignTerritoryConditions.TargetState.FULLY_FRIENDLY) {
                return "TARGET_WARZONE_FULLY_FRIENDLY";
            }
            if (targetState == CampaignTerritoryConditions.TargetState.NO_REACHABLE_OBJECTIVES) {
                return "TARGET_NO_REACHABLE_OBJECTIVES";
            }
        }
        if ((changed == null || contains(candidate.stagingChunk(), changed))
                && !CampaignTerritoryConditions.rallyChunkOwnedByFriendlyBloc(
                level, controller, candidate.stagingChunk())) {
            return "RALLY_OWNER_LOST";
        }
        return null;
    }
}
