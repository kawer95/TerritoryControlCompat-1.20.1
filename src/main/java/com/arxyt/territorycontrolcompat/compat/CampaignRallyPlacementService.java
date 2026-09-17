package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Incrementally reserves collision-safe rally positions before a route probe can start. */
public final class CampaignRallyPlacementService {
    private static final int MAX_COLUMNS_PER_TICK = 64;
    private static final long SOFT_BUDGET_NANOS = 250_000L;
    private static final ArrayDeque<Request> PENDING = new ArrayDeque<>();
    private static final Set<CampaignPlanningService.JobKey> KEYS = new HashSet<>();

    public CampaignRallyPlacementService() { }

    static boolean submit(ServerLevel level, CampaignPlanningService.JobKey key, String controller,
                          CampaignStrategicPlanner.Option option, int requiredSlots,
                          Consumer<Result> callback) {
        if (level == null || key == null || option == null || callback == null || !KEYS.add(key)) return false;
        PENDING.add(new Request(level, key, controller, option, Math.max(0, requiredSlots), callback));
        return true;
    }

    static void cancelDirector(String director) { cancel(request -> request.key.director().equals(director)); }
    static void cancelDimension(String dimension) { cancel(request -> request.key.dimension().equals(dimension)); }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        long deadline = System.nanoTime() + SOFT_BUDGET_NANOS;
        int checked = 0;
        while (!PENDING.isEmpty() && checked < MAX_COLUMNS_PER_TICK && System.nanoTime() < deadline) {
            Request request = PENDING.peek();
            if (request.level.getServer() != event.getServer()) {
                finish(request, Result.failure("SERVER_CHANGED"));
                continue;
            }
            String invalid = invalidationReason(request);
            if (invalid != null) {
                finish(request, Result.failure(invalid));
                continue;
            }
            if (request.requiredSlots == 0) {
                BlockPos start = exactAnchor(request);
                finish(request, start == null ? Result.failure("RALLY_NO_SAFE_SLOTS")
                        : Result.success(List.of(start)));
                continue;
            }
            if (request.nextColumn >= 256) {
                finish(request, Result.failure("RALLY_NO_SAFE_SLOTS"));
                continue;
            }
            BlockPos candidate = candidate(request, request.nextColumn++);
            checked++;
            if (candidate != null) request.add(candidate);
            if (request.positions.size() >= request.requiredSlots) {
                finish(request, Result.success(request.positions));
            }
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) cancel(request -> request.level == level);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        cancel(request -> true);
    }

    private static BlockPos exactAnchor(Request request) {
        CampaignStrategicPlanner.Point anchor = request.option.candidate().stagingAnchor();
        return safe(request, anchor.x(), anchor.z());
    }

    private static BlockPos candidate(Request request, int ordinal) {
        CampaignStrategicPlanner.Point preferred = request.option.candidate().stagingAnchor();
        int baseX = request.option.candidate().stagingChunk().x() << 4;
        int baseZ = request.option.candidate().stagingChunk().z() << 4;
        int preferredIndex = (Math.floorMod(preferred.z(), 16) << 4) | Math.floorMod(preferred.x(), 16);
        int index = (preferredIndex + ordinal) & 255;
        return safe(request, baseX + (index & 15), baseZ + (index >> 4));
    }

    private static BlockPos safe(Request request, int x, int z) {
        int y = request.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos candidate = new BlockPos(x, y, z);
        BlockPos ground = candidate.below();
        if (!request.level.getFluidState(candidate).isEmpty() || !request.level.getFluidState(ground).isEmpty()
                || !request.level.getBlockState(ground).isFaceSturdy(request.level, ground, Direction.UP)) return null;
        double half = request.option.probeWidth() / 2.0D;
        AABB box = new AABB(x + 0.5D - half, y, z + 0.5D - half,
                x + 0.5D + half, y + request.option.probeHeight(), z + 0.5D + half);
        if (!request.level.noCollision(box)) return null;
        for (AABB reserved : request.boxes) if (reserved.intersects(box)) return null;
        request.boxes.add(box);
        return candidate;
    }

    private static String invalidationReason(Request request) {
        CampaignStrategicPlanner.Candidate candidate = request.option.candidate();
        CampaignTerritoryConditions.TargetState targetState = CampaignTerritoryConditions.targetState(
                request.level, request.controller, candidate.target());
        if (targetState == CampaignTerritoryConditions.TargetState.FULLY_FRIENDLY) return "TARGET_WARZONE_FULLY_FRIENDLY";
        if (targetState == CampaignTerritoryConditions.TargetState.NO_REACHABLE_OBJECTIVES) return "TARGET_NO_REACHABLE_OBJECTIVES";
        if (!CampaignTerritoryConditions.rallyChunkOwnedByFriendlyBloc(
                request.level, request.controller, candidate.stagingChunk())) return "RALLY_OWNER_LOST";
        return null;
    }

    private static void finish(Request request, Result result) {
        PENDING.remove(request);
        KEYS.remove(request.key);
        request.callback.accept(result);
    }

    private static void cancel(java.util.function.Predicate<Request> predicate) {
        List<Request> removed = PENDING.stream().filter(predicate).toList();
        PENDING.removeAll(removed);
        removed.forEach(request -> {
            KEYS.remove(request.key);
            CampaignUnitReservations.release(request.key);
        });
    }

    record Result(boolean success, String reason, List<BlockPos> positions) {
        static Result success(List<BlockPos> positions) { return new Result(true, "SUCCESS", List.copyOf(positions)); }
        static Result failure(String reason) { return new Result(false, reason, List.of()); }
    }

    private static final class Request {
        private final ServerLevel level;
        private final CampaignPlanningService.JobKey key;
        private final String controller;
        private final CampaignStrategicPlanner.Option option;
        private final int requiredSlots;
        private final Consumer<Result> callback;
        private final List<BlockPos> positions = new ArrayList<>();
        private final List<AABB> boxes = new ArrayList<>();
        private int nextColumn;

        private Request(ServerLevel level, CampaignPlanningService.JobKey key, String controller,
                        CampaignStrategicPlanner.Option option, int requiredSlots, Consumer<Result> callback) {
            this.level = level; this.key = key; this.controller = controller; this.option = option;
            this.requiredSlots = requiredSlots; this.callback = callback;
        }

        private void add(BlockPos position) { positions.add(position.immutable()); }
    }
}
