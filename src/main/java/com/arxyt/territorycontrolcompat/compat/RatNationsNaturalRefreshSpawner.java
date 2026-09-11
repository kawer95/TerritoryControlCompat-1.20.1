package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratnations.api.FactionDescriptor;
import com.arxyt.ratnations.api.NationDefinition;
import com.arxyt.ratnations.api.RatNationsFactionApi;
import com.arxyt.ratnations.entity.AbstractMouseSoldierEntity;
import com.arxyt.ratnations.entity.MouseCivilianEntity;
import com.arxyt.ratnations.entity.MouseSoldierVariant;
import com.arxyt.ratnations.nation.NationCatalogManager;
import com.arxyt.ratnations.registry.ModEntities;
import com.arxyt.ratnations.troop.TroopPack;
import com.arxyt.ratnations.troop.TroopPackManager;
import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrol.core.BattleModes;
import com.arxyt.territorycontrol.core.data.TerritorySavedData;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-only natural refresh for Rat Nations countries bound to Territory Control factions.
 *
 * <p>The handler deliberately works from the loaded territory index around active players. It
 * never asks the chunk source to load a remote chunk, and the military and civilian pools are
 * counted and capped independently.</p>
 */
public final class RatNationsNaturalRefreshSpawner {
    public static final long INTERVAL_TICKS = 2400L;
    public static final int MILITARY_DIVISOR = 5;
    public static final int CIVILIAN_DIVISOR = 10;
    public static final int MILITARY_CAP = 30;
    public static final int CIVILIAN_CAP = 10;
    static final int MAX_POSITION_ATTEMPTS = 64;

    static final List<RoleWeight> ROLE_WEIGHTS = List.of(
            new RoleWeight("rifleman", 20),
            new RoleWeight("pistolman", 10),
            new RoleWeight("assault", 2),
            new RoleWeight("machine_gunner", 1),
            new RoleWeight("sniper", 5));

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || event.getServer().getTickCount() % INTERVAL_TICKS != 0L) {
            return;
        }

        MinecraftServer server = event.getServer();
        if (!CompatSavedData.get(server.overworld()).config().ratNationsNaturalRefresh()
                || BattleModes.isLayoutMode(server)
                || BattleModes.isCleanupMode(server)) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            if (level.getDifficulty() == Difficulty.PEACEFUL
                    || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
                continue;
            }
            List<ServerPlayer> players = activePlayers(level);
            if (players.isEmpty()) {
                continue;
            }
            refreshLevel(level, players);
        }
    }

    private static List<ServerPlayer> activePlayers(ServerLevel level) {
        return level.players().stream()
                .filter(player -> player.isAlive() && !player.isSpectator())
                .toList();
    }

    private static void refreshLevel(ServerLevel level, List<ServerPlayer> players) {
        for (FactionDescriptor descriptor : RatNationsFactionApi.factions()) {
            NationTarget target = targetFor(level, descriptor);
            if (target == null || (target.militaryCapacity() == 0 && target.civilianCapacity() == 0)) {
                continue;
            }

            int militaryCount = countUnits(level, target.nationId(), AbstractMouseSoldierEntity.class);
            int civilianCount = countUnits(level, target.nationId(), MouseCivilianEntity.class);
            int militaryRemaining = Math.max(0, target.militaryCapacity() - militaryCount);
            int civilianRemaining = Math.max(0, target.civilianCapacity() - civilianCount);
            if (militaryRemaining == 0 && civilianRemaining == 0) {
                continue;
            }

            List<ChunkPos> chunks = eligibleChunks(level, players, target.eligibleFactionIds());
            if (chunks.isEmpty()) {
                continue;
            }

            RandomSource random = level.random;
            RefreshCounts requested = requestedCounts(random,
                    militaryRemaining > 0 && !target.militaryPacks().isEmpty() ? militaryRemaining : 0,
                    civilianRemaining > 0 && target.civilianPackId() != null ? civilianRemaining : 0);
            for (int i = 0; i < requested.military(); i++) {
                spawnMilitary(level, target, chunks, random);
            }
            for (int i = 0; i < requested.civilian(); i++) {
                spawnCivilian(level, target, chunks, random);
            }
        }
    }

    private static NationTarget targetFor(ServerLevel level, FactionDescriptor descriptor) {
        String nationKey = descriptor.id().toString();
        var controller = TerritoryControlApi.factionForExternal(
                level, RatNationsFactionProvider.PROVIDER_ID, nationKey).orElse(null);
        if (controller == null) {
            return null;
        }

        Set<String> eligibleFactionIds = new LinkedHashSet<>();
        eligibleFactionIds.add(controller.id());
        TerritoryControlApi.allianceForFaction(level, controller.id())
                .ifPresent(alliance -> eligibleFactionIds.addAll(alliance.factionIds()));

        int directTerritoryCount = TerritorySavedData.get(level)
                .ownedChunkCount(level, controller.id());
        int militaryCapacity = capacity(directTerritoryCount, MILITARY_DIVISOR, MILITARY_CAP);
        int civilianCapacity = capacity(directTerritoryCount, CIVILIAN_DIVISOR, CIVILIAN_CAP);

        NationDefinition definition = NationCatalogManager.find(descriptor.id()).orElse(null);
        if (definition == null) {
            return null;
        }
        return new NationTarget(descriptor.id(), Set.copyOf(eligibleFactionIds), militaryCapacity,
                civilianCapacity, officialPacks(descriptor.id()), definition.defaultCivilianId());
    }

    static int capacity(int directTerritoryCount, int divisor, int cap) {
        if (directTerritoryCount <= 0 || divisor <= 0 || cap <= 0) {
            return 0;
        }
        return Math.min(directTerritoryCount / divisor, cap);
    }

    static int requestedCount(RandomSource random, int remainingCapacity) {
        if (remainingCapacity <= 0) {
            return 0;
        }
        return Math.min(random.nextInt(2) + 1, remainingCapacity);
    }

    /**
     * Rolls the military and civilian batch sizes independently. A zero remaining capacity (or
     * an unavailable pool, as supplied by the caller) consumes no random roll for that pool.
     */
    static RefreshCounts requestedCounts(RandomSource random, int militaryRemaining, int civilianRemaining) {
        return new RefreshCounts(requestedCount(random, militaryRemaining),
                requestedCount(random, civilianRemaining));
    }

    private static int countUnits(ServerLevel level, ResourceLocation nationId,
                                  Class<? extends Mob> type) {
        int count = 0;
        for (var entity : level.getAllEntities()) {
            if (entity.isAlive() && type.isInstance(entity)
                    && RatNationsFactionApi.factionOf(entity).filter(nationId::equals).isPresent()) {
                count++;
            }
        }
        return count;
    }

    private static List<ChunkPos> eligibleChunks(ServerLevel level, List<ServerPlayer> players,
                                                  Set<String> eligibleFactionIds) {
        Set<Long> seen = new HashSet<>();
        List<ChunkPos> result = new ArrayList<>();
        int radius = Math.max(0, level.getServer().getPlayerList().getViewDistance());
        for (ServerPlayer player : players) {
            ChunkPos center = player.chunkPosition();
            List<java.util.Map.Entry<ChunkPos, TerritoryControlApi.TerritoryView>> entries =
                    TerritoryControlApi.territoriesInRange(level, center.x - radius, center.z - radius,
                            center.x + radius, center.z + radius);
            for (var entry : entries) {
                ChunkPos chunk = entry.getKey();
                if (eligibleFactionIds.contains(entry.getValue().ownerFaction())
                        && level.hasChunk(chunk.x, chunk.z)
                        && seen.add(chunk.toLong())) {
                    result.add(chunk);
                }
            }
        }
        return result;
    }

    static List<WeightedPack> officialPacks(ResourceLocation nationId) {
        String prefix = officialPrefix(nationId);
        if (prefix == null) {
            return List.of();
        }
        List<WeightedPack> result = new ArrayList<>();
        for (RoleWeight role : ROLE_WEIGHTS) {
            TroopPack pack = TroopPackManager.find(ResourceLocation.fromNamespaceAndPath(
                    RatNationsFactionProvider.MOD_ID, prefix + "_" + role.suffix())).orElse(null);
            if (pack == null || !nationId.equals(pack.nationId())
                    || !nationId.equals(ModEntities.fixedNation(pack.entityId()))) {
                continue;
            }
            result.add(new WeightedPack(pack, role.weight()));
        }
        return List.copyOf(result);
    }

    static int officialRoleWeightTotal() {
        return ROLE_WEIGHTS.stream().mapToInt(RoleWeight::weight).sum();
    }

    private static String officialPrefix(ResourceLocation nationId) {
        if (RatNationsFactionApi.RAT_FEDERATION.equals(nationId)) {
            return "federation";
        }
        if (RatNationsFactionApi.RAT_EMPIRE.equals(nationId)) {
            return "empire";
        }
        return null;
    }

    static WeightedPack pickWeighted(List<WeightedPack> packs, RandomSource random) {
        int total = packs.stream().mapToInt(WeightedPack::weight).sum();
        if (total <= 0) {
            return null;
        }
        int selected = random.nextInt(total);
        for (WeightedPack pack : packs) {
            selected -= pack.weight();
            if (selected < 0) {
                return pack;
            }
        }
        return packs.get(packs.size() - 1);
    }

    private static boolean spawnMilitary(ServerLevel level, NationTarget target, List<ChunkPos> chunks,
                                         RandomSource random) {
        WeightedPack selected = pickWeighted(target.militaryPacks(), random);
        if (selected == null) {
            return false;
        }
        AbstractMouseSoldierEntity entity = ModEntities.createTroop(selected.pack().entityId(), level);
        if (entity == null || !entity.configureFromPackId(selected.pack().id())) {
            if (entity != null) {
                entity.discard();
            }
            return false;
        }
        entity.setModelVariant(random.nextBoolean() ? MouseSoldierVariant.FEMALE : MouseSoldierVariant.MALE);
        return addConfiguredMob(level, entity, chunks, random);
    }

    private static boolean spawnCivilian(ServerLevel level, NationTarget target, List<ChunkPos> chunks,
                                         RandomSource random) {
        MouseCivilianEntity entity = ModEntities.MOUSE_CIVILIAN.get().create(level);
        if (entity == null || !entity.configureFromPackId(target.civilianPackId(), target.nationId())) {
            if (entity != null) {
                entity.discard();
            }
            return false;
        }
        return addConfiguredMob(level, entity, chunks, random);
    }

    private static boolean addConfiguredMob(ServerLevel level, Mob entity, List<ChunkPos> chunks,
                                             RandomSource random) {
        BlockPos position = findSpawnPosition(level, chunks, entity, random);
        if (position == null) {
            entity.discard();
            return false;
        }

        entity.finalizeSpawn(level, level.getCurrentDifficultyAt(position), MobSpawnType.SPAWN_EGG,
                null, null);
        if (entity.isSpawnCancelled()) {
            entity.discard();
            return false;
        }
        entity.setPersistenceRequired();
        level.addFreshEntityWithPassengers(entity);
        return !entity.isRemoved() && level.getEntity(entity.getUUID()) != null;
    }

    private static BlockPos findSpawnPosition(ServerLevel level, List<ChunkPos> chunks, Mob entity,
                                               RandomSource random) {
        if (chunks.isEmpty()) {
            return null;
        }
        for (int attempt = 0; attempt < MAX_POSITION_ATTEMPTS; attempt++) {
            ChunkPos chunk = chunks.get(random.nextInt(chunks.size()));
            int x = chunk.getMinBlockX() + random.nextInt(14);
            int z = chunk.getMinBlockZ() + random.nextInt(14);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 1, z + 1);
            if (y <= level.getMinBuildHeight() || y + 2 >= level.getMaxBuildHeight()) {
                continue;
            }
            if (!level.hasChunk(x >> 4, z >> 4) || !isSurfaceHeight(level, x, y, z)
                    || !hasSolidFloor(level, x, y, z)
                    || (!level.dimensionType().hasCeiling() && !hasOpenSkyAbove(level, x + 1, y + 3, z + 1))
                    || !hasAirVolume(level, x, y, z)) {
                continue;
            }

            entity.moveTo(x + 1.5D, y, z + 1.5D, random.nextFloat() * 360.0F, 0.0F);
            if (level.noCollision(entity)) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    private static boolean isSurfaceHeight(ServerLevel level, int x, int y, int z) {
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz) != y) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasOpenSkyAbove(ServerLevel level, int x, int startY, int z) {
        for (int y = startY; y < level.getMaxBuildHeight(); y++) {
            var state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && !state.is(BlockTags.LEAVES)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSolidFloor(ServerLevel level, int x, int y, int z) {
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                BlockPos floor = new BlockPos(x + dx, y - 1, z + dz);
                if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasAirVolume(ServerLevel level, int x, int y, int z) {
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    if (!level.getBlockState(new BlockPos(x + dx, y + dy, z + dz)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    record WeightedPack(TroopPack pack, int weight) {
    }

    record RoleWeight(String suffix, int weight) {
    }

    record RefreshCounts(int military, int civilian) {
    }

    private record NationTarget(ResourceLocation nationId,
                                Set<String> eligibleFactionIds,
                                int militaryCapacity,
                                int civilianCapacity,
                                List<WeightedPack> militaryPacks,
                                ResourceLocation civilianPackId) {
        private NationTarget {
            eligibleFactionIds = Set.copyOf(eligibleFactionIds);
            militaryPacks = List.copyOf(militaryPacks);
        }
    }
}
