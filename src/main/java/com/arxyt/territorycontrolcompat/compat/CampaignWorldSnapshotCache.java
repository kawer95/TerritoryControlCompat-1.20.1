package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Maintains versioned territory and walkability snapshots for background campaign planning.
 * World reads are sliced into a strict per-tick budget; background consumers see primitive,
 * immutable records and never touch a level, chunk, block state, or entity.
 */
public final class CampaignWorldSnapshotCache {
    private static final CampaignWorldSnapshotCache INSTANCE = new CampaignWorldSnapshotCache();
    private static final int TERRAIN_COLUMNS_PER_TICK = 64;
    private static final long TERRAIN_BUDGET_NANOS = 250_000L;
    private static final long HOT_TERRAIN_TTL_TICKS = 100L;
    private static final long COLD_TERRAIN_TTL_TICKS = 6_000L;
    private static final int TILE_REQUESTS_PER_TICK = 2;

    private final Map<ServerLevel, LevelCache> levels = new IdentityHashMap<>();
    private final ConcurrentLinkedQueue<TileCompletion> tileCompletions = new ConcurrentLinkedQueue<>();
    private long generation = 1L;

    private CampaignWorldSnapshotCache() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        TerritoryControlApi.registerTerritoryChangeListener(INSTANCE::territoryChanged);
    }

    static boolean validate(ServerLevel level, long expectedGeneration,
                            Map<Long, Long> terrainRevisions) {
        LevelCache cache = INSTANCE.levels.get(level);
        if (cache == null || INSTANCE.generation != expectedGeneration) return false;
        for (Map.Entry<Long, Long> entry : terrainRevisions.entrySet()) {
            CampaignStrategicPlanner.TerrainChunk chunk = cache.terrain.get(entry.getKey());
            if (chunk == null || chunk.revision() != entry.getValue()) return false;
        }
        return true;
    }

    static TerritoryControlApi.TerritoryView territory(ServerLevel level, int chunkX, int chunkZ) {
        LevelCache cache = INSTANCE.levels.get(level);
        return cache == null ? null : cache.territories.get(CampaignStrategicPlanner.chunkKey(chunkX, chunkZ));
    }

    static BlockPos safeAnchor(ServerLevel level, int chunkX, int chunkZ) {
        List<BlockPos> anchors = safeAnchors(level, chunkX, chunkZ, 1);
        return anchors.isEmpty() ? null : anchors.get(0);
    }

    /** Deterministic, spaced destinations within one cached terrain chunk. */
    static List<BlockPos> safeAnchors(ServerLevel level, int chunkX, int chunkZ, int limit) {
        LevelCache cache = INSTANCE.levels.get(level);
        if (cache == null || limit <= 0) return List.of();
        CampaignStrategicPlanner.TerrainChunk chunk = cache.terrain.get(ChunkPos.asLong(chunkX, chunkZ));
        if (chunk == null) return List.of();
        List<BlockPos> result = new ArrayList<>(Math.min(limit, 64));
        int[] offsets = {1, 3, 5, 7, 9, 11, 13, 15};
        for (int z : offsets) for (int x : offsets) {
            int y = chunk.height(x, z);
            if (y != Integer.MIN_VALUE && chunk.clearance(x, z) >= 2) {
                result.add(new BlockPos((chunkX << 4) + x, y, (chunkZ << 4) + z));
                if (result.size() >= limit) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    static boolean hasTerrainSnapshot(ServerLevel level, int chunkX, int chunkZ) {
        LevelCache cache = INSTANCE.levels.get(level);
        return cache != null && cache.terrain.containsKey(ChunkPos.asLong(chunkX, chunkZ));
    }

    static void markHot(ServerLevel level, CampaignStrategicPlanner.Option option) {
        LevelCache cache = INSTANCE.levels.get(level);
        if (cache == null || option == null) return;
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level).warzoneConfig().normalized().sizeChunks();
        long until = level.getGameTime() + 2_400L;
        for (CampaignStrategicPlanner.ZoneKey zone : List.of(option.candidate().target())) {
            for (int x = zone.x() * size; x < zone.x() * size + size; x++) for (int z = zone.z() * size; z < zone.z() * size + size; z++) {
                long key = ChunkPos.asLong(x, z);
                cache.hotUntil.put(key, until);
                INSTANCE.queueTerrain(level, new ChunkPos(x, z));
            }
        }
        CampaignStrategicPlanner.ChunkKey rally = option.candidate().stagingChunk();
        long rallyKey = ChunkPos.asLong(rally.x(), rally.z());
        cache.hotUntil.put(rallyKey, until);
        INSTANCE.queueTerrain(level, new ChunkPos(rally.x(), rally.z()));
    }

    /** Queues only friendly-discovered frontier chunks for incremental terrain capture. */
    static void markFrontierHot(ServerLevel level, Set<Long> chunks) {
        LevelCache cache = INSTANCE.levels.get(level);
        if (cache == null || chunks == null || chunks.isEmpty()) return;
        long until = level.getGameTime() + 2_400L;
        for (long key : chunks) {
            cache.hotUntil.put(key, until);
            INSTANCE.queueTerrain(level, new ChunkPos(key));
        }
    }

    static Metrics metrics(ServerLevel level) {
        LevelCache cache = INSTANCE.levels.get(level);
        return cache == null ? new Metrics(0, 0, 0, 0, 0L)
                : new Metrics(cache.pendingTiles.size(), cache.terrainQueue.size(), cache.terrain.size(),
                cache.territories.size(), cache.maxTickNanos);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        applyTileCompletions();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            LevelCache cache = levels.computeIfAbsent(level, ignored -> new LevelCache(level.dimension().location().toString()));
            int configuredSize = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level)
                    .warzoneConfig().normalized().sizeChunks();
            if (cache.warzoneSize > 0 && cache.warzoneSize != configuredSize) {
                CampaignPlanningService.cancelDimension(level.dimension().location().toString());
                CampaignRouteProbeService.cancelDimension(level.dimension().location().toString());
                CampaignRallyPlacementService.cancelDimension(level.dimension().location().toString());
                CampaignDeploymentService.rollback(level, "");
                cache.loadedTiles.clear();
                cache.territories.clear();
                cache.pendingTiles.clear();
                cache.tileQueue.clear();
                cache.queuedTiles.clear();
                cache.revision++;
            }
            cache.warzoneSize = configuredSize;
            updateRequiredTiles(level, cache);
            requestTiles(level, cache);
            refreshTerrain(level, cache);
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        queueTerrain(level, event.getChunk().getPos());
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        LevelCache cache = levels.get(level);
        if (cache == null) return;
        long key = event.getChunk().getPos().toLong();
        cache.terrain.remove(key);
        cache.queuedTerrain.remove(key);
        cache.revision++;
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        invalidate(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        invalidate(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public void onFluidChange(BlockEvent.FluidPlaceBlockEvent event) {
        invalidate(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        levels.remove(level);
        generation++;
        CampaignPlanningService.cancelDimension(level.dimension().location().toString());
        CampaignRouteProbeService.cancelDimension(level.dimension().location().toString());
        CampaignRallyPlacementService.cancelDimension(level.dimension().location().toString());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        levels.clear();
        tileCompletions.clear();
        generation++;
        CampaignPlanningService.shutdown();
        CampaignUnitReservations.clear();
        CampaignFailureLog.shutdown();
    }

    private void territoryChanged(ServerLevel level, ChunkPos pos, TerritoryControlApi.TerritoryView view) {
        LevelCache cache = levels.computeIfAbsent(level, ignored -> new LevelCache(level.dimension().location().toString()));
        long key = pos.toLong();
        if (view == null) cache.territories.remove(key); else cache.territories.put(key, view);
        CampaignRouteProbeService.invalidateTerritory(level, pos);
        TileKey tile = TileKey.of(pos.x, pos.z);
        PendingTile pending = cache.pendingTiles.get(tile);
        if (pending != null) pending.deltas.put(key, view);
        cache.revision++;
    }

    private void updateRequiredTiles(ServerLevel level, LevelCache cache) {
        Set<TileKey> required = new LinkedHashSet<>();
        int radius = Math.max(0, level.getServer().getPlayerList().getViewDistance());
        int tileSize = TerritoryControlApi.TERRITORY_SNAPSHOT_TILE_SIZE;
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            ChunkPos center = player.chunkPosition();
            int minTileX = Math.floorDiv(center.x - radius, tileSize);
            int maxTileX = Math.floorDiv(center.x + radius, tileSize);
            int minTileZ = Math.floorDiv(center.z - radius, tileSize);
            int maxTileZ = Math.floorDiv(center.z + radius, tileSize);
            for (int x = minTileX; x <= maxTileX; x++) for (int z = minTileZ; z <= maxTileZ; z++) {
                required.add(new TileKey(x, z));
            }
        }
        cache.requiredTiles = Set.copyOf(required);
        List<TileKey> obsolete = cache.loadedTiles.stream().filter(tile -> !required.contains(tile)).toList();
        for (TileKey tile : obsolete) {
            cache.loadedTiles.remove(tile);
            int minX = tile.x * tileSize, minZ = tile.z * tileSize;
            for (int x = minX; x < minX + tileSize; x++) for (int z = minZ; z < minZ + tileSize; z++) {
                cache.territories.remove(ChunkPos.asLong(x, z));
            }
        }
        for (TileKey tile : required) {
            if (!cache.loadedTiles.contains(tile) && !cache.pendingTiles.containsKey(tile)
                    && cache.queuedTiles.add(tile)) cache.tileQueue.add(tile);
        }
    }

    private void requestTiles(ServerLevel level, LevelCache cache) {
        int submitted = 0;
        while (submitted < TILE_REQUESTS_PER_TICK && !cache.tileQueue.isEmpty()) {
            TileKey tile = cache.tileQueue.poll();
            cache.queuedTiles.remove(tile);
            if (!cache.requiredTiles.contains(tile) || cache.loadedTiles.contains(tile)
                    || cache.pendingTiles.containsKey(tile)) continue;
            PendingTile pending = new PendingTile();
            cache.pendingTiles.put(tile, pending);
            CompletionStage<TerritoryControlApi.TerritoryTileSnapshot> future =
                    TerritoryControlApi.requestTerritoryTileSnapshot(level, tile.x, tile.z);
            future.whenComplete((snapshot, failure) -> tileCompletions.add(
                    new TileCompletion(level, tile, snapshot, failure, pending)));
            submitted++;
        }
    }

    private void applyTileCompletions() {
        TileCompletion completion;
        while ((completion = tileCompletions.poll()) != null) {
            LevelCache cache = levels.get(completion.level);
            if (cache == null || cache.pendingTiles.get(completion.tile) != completion.pending) continue;
            cache.pendingTiles.remove(completion.tile);
            if (!cache.requiredTiles.contains(completion.tile)) continue;
            if (completion.failure != null) {
                CampaignFailureLog.record("CampaignSnapshot", "territory tile result=FAILURE dimension="
                        + cache.dimension + " tile=" + completion.tile + " reason=" + completion.failure);
                continue;
            }
            int size = TerritoryControlApi.TERRITORY_SNAPSHOT_TILE_SIZE;
            int minX = completion.tile.x * size;
            int minZ = completion.tile.z * size;
            for (int x = minX; x < minX + size; x++) for (int z = minZ; z < minZ + size; z++) {
                cache.territories.remove(CampaignStrategicPlanner.chunkKey(x, z));
            }
            for (TerritoryControlApi.TerritoryTileEntry entry : completion.snapshot.entries()) {
                cache.territories.put(CampaignStrategicPlanner.chunkKey(entry.chunkX(), entry.chunkZ()),
                        new TerritoryControlApi.TerritoryView(entry.ownerFaction(), entry.progressFaction(),
                                entry.contestFaction(), entry.progress()));
            }
            completion.pending.deltas.forEach((key, view) -> {
                if (view == null) cache.territories.remove(key); else cache.territories.put(key, view);
            });
            cache.loadedTiles.add(completion.tile);
            cache.revision++;
        }
    }

    private void refreshTerrain(ServerLevel level, LevelCache cache) {
        long now = level.getGameTime();
        while (!cache.refreshQueue.isEmpty() && cache.refreshQueue.peek().dueTick <= now) {
            Refresh refresh = cache.refreshQueue.poll();
                CampaignStrategicPlanner.TerrainChunk current = cache.terrain.get(refresh.chunkKey);
            if (current != null && current.revision() == refresh.revision) {
                queueTerrain(level, new ChunkPos(refresh.chunkKey));
                break;
            }
        }
        long started = System.nanoTime();
        int processed = 0;
        while (processed < TERRAIN_COLUMNS_PER_TICK && System.nanoTime() - started < TERRAIN_BUDGET_NANOS) {
            TerrainBuild build = cache.terrainQueue.peek();
            if (build == null) break;
            if (!level.hasChunk(build.chunk.x, build.chunk.z)) {
                cache.terrainQueue.poll();
                cache.queuedTerrain.remove(build.chunk.toLong());
                continue;
            }
            int localX = build.index & 15;
            int localZ = build.index >> 4;
            int x = build.chunk.getMinBlockX() + localX;
            int z = build.chunk.getMinBlockZ() + localZ;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            int arrayIndex = localZ * 16 + localX;
            if (CampaignTerrainResolver.isSafeLandAnchor(level, feet) && y >= Short.MIN_VALUE + 1 && y <= Short.MAX_VALUE) {
                build.heights[arrayIndex] = (short) y;
                int clearance = 0;
                for (int offset = 0; offset < 8; offset++) {
                    var state = level.getBlockState(feet.above(offset));
                    if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, feet.above(offset)).isEmpty()) break;
                    clearance++;
                }
                build.clearance[arrayIndex] = (byte) clearance;
            }
            build.index++;
            processed++;
            if (build.index == 256) {
                cache.terrainQueue.poll();
                long key = build.chunk.toLong();
                cache.queuedTerrain.remove(key);
                CampaignStrategicPlanner.TerrainChunk previous = cache.terrain.get(key);
                long revision = previous == null ? 1L : previous.revision() + 1L;
                CampaignStrategicPlanner.TerrainChunk snapshot = new CampaignStrategicPlanner.TerrainChunk(
                        build.chunk.x, build.chunk.z, revision, build.heights, build.clearance);
                if (previous == null || !java.util.Arrays.equals(previous.heights(), snapshot.heights())
                        || !java.util.Arrays.equals(previous.clearance(), snapshot.clearance())) {
                    cache.terrain.put(key, snapshot);
                    cache.revision++;
                } else {
                    snapshot = previous;
                }
                long ttl = cache.hotUntil.getOrDefault(key, 0L) >= now ? HOT_TERRAIN_TTL_TICKS : COLD_TERRAIN_TTL_TICKS;
                cache.refreshQueue.add(new Refresh(now + ttl, key, snapshot.revision()));
            }
        }
        cache.maxTickNanos = Math.max(cache.maxTickNanos, System.nanoTime() - started);
    }

    private void invalidate(net.minecraft.world.level.LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof ServerLevel level)) return;
        ChunkPos chunk = new ChunkPos(pos);
        queueTerrain(level, chunk);
        CampaignRouteProbeService.invalidateTerrain(level, chunk);
    }

    private void queueTerrain(ServerLevel level, ChunkPos chunk) {
        LevelCache cache = levels.computeIfAbsent(level, ignored -> new LevelCache(level.dimension().location().toString()));
        long key = chunk.toLong();
        if (cache.queuedTerrain.add(key)) cache.terrainQueue.add(new TerrainBuild(chunk));
    }

    /** Immutable territory list paired with the shared versioned terrain cache. */
    record PlanningSnapshot(long generation, long revision,
                            Map<Long, TerritoryControlApi.TerritoryView> territories,
                            Map<Long, CampaignStrategicPlanner.TerrainChunk> terrain) { }

    static PlanningSnapshot planningSnapshot(ServerLevel level) {
        LevelCache cache = INSTANCE.levels.get(level);
        if (cache == null || !cache.tilesReady()) return null;
        return new PlanningSnapshot(INSTANCE.generation, cache.revision, cache.territories, cache.terrain);
    }

    record Metrics(int pendingTiles, int terrainBacklog, int terrainChunks,
                   int territoryChunks, long maxTickNanos) { }

    private record TileKey(int x, int z) {
        private static TileKey of(int chunkX, int chunkZ) {
            int size = TerritoryControlApi.TERRITORY_SNAPSHOT_TILE_SIZE;
            return new TileKey(Math.floorDiv(chunkX, size), Math.floorDiv(chunkZ, size));
        }
    }
    private record TileCompletion(ServerLevel level, TileKey tile,
                                  TerritoryControlApi.TerritoryTileSnapshot snapshot,
                                  Throwable failure, PendingTile pending) { }
    private static final class PendingTile {
        private final Map<Long, TerritoryControlApi.TerritoryView> deltas = new HashMap<>();
    }
    private static final class TerrainBuild {
        private final ChunkPos chunk;
        private final short[] heights = new short[256];
        private final byte[] clearance = new byte[256];
        private int index;
        private TerrainBuild(ChunkPos chunk) {
            this.chunk = chunk;
            java.util.Arrays.fill(heights, Short.MIN_VALUE);
        }
    }
    private record Refresh(long dueTick, long chunkKey, long revision) implements Comparable<Refresh> {
        @Override public int compareTo(Refresh other) { return Long.compare(dueTick, other.dueTick); }
    }
    private static final class LevelCache {
        private final String dimension;
        private final ConcurrentHashMap<Long, TerritoryControlApi.TerritoryView> territories = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, CampaignStrategicPlanner.TerrainChunk> terrain = new ConcurrentHashMap<>();
        private final Map<TileKey, PendingTile> pendingTiles = new HashMap<>();
        private final Set<TileKey> loadedTiles = new HashSet<>();
        private final ArrayDeque<TileKey> tileQueue = new ArrayDeque<>();
        private final Set<TileKey> queuedTiles = new HashSet<>();
        private final ArrayDeque<TerrainBuild> terrainQueue = new ArrayDeque<>();
        private final Set<Long> queuedTerrain = new HashSet<>();
        private final PriorityQueue<Refresh> refreshQueue = new PriorityQueue<>();
        private final Map<Long, Long> hotUntil = new HashMap<>();
        private Set<TileKey> requiredTiles = Set.of();
        private int warzoneSize;
        private long revision;
        private long maxTickNanos;
        private LevelCache(String dimension) { this.dimension = dimension; }
        private boolean tilesReady() { return !requiredTiles.isEmpty() && loadedTiles.containsAll(requiredTiles); }
    }
}
