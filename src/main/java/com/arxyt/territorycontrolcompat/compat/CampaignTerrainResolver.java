package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Resolves dry, standable campaign anchors. Territory Control captures chunks, but campaigns
 * must never order land armies to occupy a water surface just because that chunk has a territory.
 */
public final class CampaignTerrainResolver {
    /** Dense enough to find a real clearing in a mixed water/forest chunk without scanning or
     * loading neighbouring chunks. */
    private static final int[] SAMPLE_OFFSETS = {1, 3, 5, 7, 9, 11, 13, 15};
    private static final int MAX_GROUND_SEARCH_DEPTH = 16;
    private static final int MAX_CAMPAIGN_ANCHORS_PER_CHUNK = 6;
    private static final double MOUSE_HALF_WIDTH = 0.3D;
    private static final double MOUSE_HEIGHT = 1.8D;

    private CampaignTerrainResolver() {
    }

    /** Finds a genuine ground-level landing point in a loaded chunk.  The motion heightmap is
     * merely a starting height: forest canopies and tall vegetation are scanned through rather
     * than accepted as the final floor. */
    public static Optional<BlockPos> findLandAnchor(ServerLevel level, ChunkPos chunk, BlockPos preferred) {
        if (level == null || chunk == null || !level.hasChunk(chunk.x, chunk.z)) return Optional.empty();
        BlockPos origin = preferred == null ? new BlockPos(chunk.getMiddleBlockX(), level.getMinBuildHeight(), chunk.getMiddleBlockZ()) : preferred;
        return java.util.Arrays.stream(SAMPLE_OFFSETS).boxed()
                .flatMap(xOffset -> java.util.Arrays.stream(SAMPLE_OFFSETS).mapToObj(zOffset -> {
                    int x = chunk.getMinBlockX() + xOffset;
                    int z = chunk.getMinBlockZ() + zOffset;
                    return findGroundBelowHeightmap(level, x, z);
                }))
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparingDouble(pos -> distanceSqr(pos, origin)));
    }

    /**
     * Campaign anchor stack. Town roofs are valid collision surfaces but poor rally
     * points: the first pass therefore ranks ground with material support beneath it before any
     * elevated roof/platform candidate.  The latter remains a bounded fallback for maps that
     * genuinely have no street-level route.  Every plane is still tested by the caller's real
     * navigator before being selected.
     */
    public static List<CampaignAnchor> findCampaignAnchors(ServerLevel level, ChunkPos chunk, BlockPos preferred) {
        if (level == null || chunk == null || !level.hasChunk(chunk.x, chunk.z)) return List.of();
        BlockPos origin = preferred == null ? new BlockPos(chunk.getMiddleBlockX(), level.getMinBuildHeight(), chunk.getMiddleBlockZ()) : preferred;
        LinkedHashMap<BlockPos, CampaignAnchor> candidates = new LinkedHashMap<>();
        for (int xOffset : SAMPLE_OFFSETS) {
            for (int zOffset : SAMPLE_OFFSETS) {
                int x = chunk.getMinBlockX() + xOffset;
                int z = chunk.getMinBlockZ() + zOffset;
                for (BlockPos feet : findGroundPlanesBelowHeightmap(level, x, z)) {
                    candidates.putIfAbsent(feet.immutable(), new CampaignAnchor(feet.immutable(), isElevatedSurface(level, feet)));
                }
            }
        }
        return candidates.values().stream()
                .sorted(Comparator.comparing(CampaignAnchor::elevated)
                        .thenComparingDouble(anchor -> distanceSqr(anchor.position(), origin))
                        .thenComparingInt(anchor -> anchor.position().getY()))
                .limit(MAX_CAMPAIGN_ANCHORS_PER_CHUNK)
                .toList();
    }

    private static BlockPos findGroundBelowHeightmap(ServerLevel level, int x, int z) {
        return findGroundPlanesBelowHeightmap(level, x, z).stream().findFirst().orElse(null);
    }

    private static List<BlockPos> findGroundPlanesBelowHeightmap(ServerLevel level, int x, int z) {
        java.util.ArrayList<BlockPos> result = new java.util.ArrayList<>();
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int floor = Math.max(level.getMinBuildHeight() + 1, top - MAX_GROUND_SEARCH_DEPTH);
        for (int feetY = top; feetY >= floor; feetY--) {
            BlockPos feet = new BlockPos(x, feetY, z);
            if (isSafeLandAnchor(level, feet)) result.add(feet);
        }
        return result;
    }

    /** A campaign anchor has dry natural/buildable ground, 1.8 blocks of collision clearance
     * and never rests on tree canopy, logs, leaves or replaceable vegetation. */
    public static boolean isSafeLandAnchor(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null || !level.hasChunk(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        BlockPos ground = feet.below();
        BlockState groundState = level.getBlockState(ground);
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        return hasDryClearance(groundState, feetState, headState)
                && isWalkableGround(level, ground, groundState)
                && hasMouseClearance(level, feet)
                && feet.getY() >= level.getMinBuildHeight() && feet.getY() + 1 < level.getMaxBuildHeight();
    }

    private static boolean isWalkableGround(ServerLevel level, BlockPos ground, BlockState state) {
        return state.isFaceSturdy(level, ground, Direction.UP)
                && !state.getCollisionShape(level, ground).isEmpty()
                && !state.is(BlockTags.LEAVES)
                && !state.is(BlockTags.LOGS)
                && !state.canBeReplaced()
                && state.getFluidState().isEmpty();
    }

    /** A deck/roof normally has air directly below the surface block.  Keep it as a last-resort
     * candidate, but prefer streets, terrain and supported construction floors. */
    private static boolean isElevatedSurface(ServerLevel level, BlockPos feet) {
        BlockPos underGround = feet.below(2);
        BlockState support = level.getBlockState(underGround);
        return support.isAir() || support.canBeReplaced() || !support.getFluidState().isEmpty()
                || support.is(BlockTags.LEAVES) || support.is(BlockTags.LOGS);
    }

    private static boolean hasMouseClearance(ServerLevel level, BlockPos feet) {
        double centerX = feet.getX() + 0.5D;
        double centerZ = feet.getZ() + 0.5D;
        AABB box = new AABB(centerX - MOUSE_HALF_WIDTH, feet.getY(), centerZ - MOUSE_HALF_WIDTH,
                centerX + MOUSE_HALF_WIDTH, feet.getY() + MOUSE_HEIGHT, centerZ + MOUSE_HALF_WIDTH);
        return level.noCollision(box);
    }

    /** Package-visible for the dependency-free verification harness. */
    static boolean hasDryClearance(BlockState ground, BlockState feet, BlockState head) {
        return ground != null && feet != null && head != null
                && hasDryColumn(!ground.getFluidState().isEmpty(), !feet.getFluidState().isEmpty(), !head.getFluidState().isEmpty(),
                feet.isAir(), head.isAir());
    }

    static boolean hasDryColumn(boolean groundFluid, boolean feetFluid, boolean headFluid, boolean feetClear, boolean headClear) {
        return !groundFluid && !feetFluid && !headFluid && feetClear && headClear;
    }

    /** Accepts only a completed navigator path, rather than a partial path ending at water or a wall. */
    public static boolean canReach(Mob mob, BlockPos target) {
        return assessPath(mob, target).reachable();
    }

    /** Explains the exact gateway that rejected a campaign path, for low-frequency battle logs. */
    public static PathAssessment assessPath(Mob mob, BlockPos target) {
        if (mob == null) return new PathAssessment(false, "missing_mob");
        if (target == null) return new PathAssessment(false, "missing_target");
        if (!mob.isAlive()) return new PathAssessment(false, "dead_mob");
        if (!(mob.level() instanceof ServerLevel level)) return new PathAssessment(false, "not_server_level");
        if (!isSafeLandAnchor(level, target)) return new PathAssessment(false, "unsafe_anchor:" + describeAnchor(level, target));
        Path path = mob.getNavigation().createPath(target, 0);
        if (path == null) return new PathAssessment(false, "path_null");
        if (!path.canReach()) return new PathAssessment(false, "path_partial:nodes=" + path.getNodeCount());
        return new PathAssessment(true, "reachable:nodes=" + path.getNodeCount());
    }

    public static <T extends Mob> List<T> reachable(List<T> mobs, BlockPos target) {
        return mobs.stream().filter(mob -> canReach(mob, target)).toList();
    }

    public static int twoThirds(int size) {
        return Math.max(1, (size * 2 + 2) / 3);
    }

    /** Stable terrain description for campaign diagnostics; it does not query or load neighbours. */
    public static String describeAnchor(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null || !level.hasChunk(feet.getX() >> 4, feet.getZ() >> 4)) return "unloaded";
        BlockPos ground = feet.below();
        BlockState groundState = level.getBlockState(ground);
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        return "ground=" + groundState.getBlock() + ",feet=" + feetState.getBlock() + ",head=" + headState.getBlock()
                + ",sturdy=" + groundState.isFaceSturdy(level, ground, Direction.UP)
                + ",leaves=" + groundState.is(BlockTags.LEAVES)
                + ",logs=" + groundState.is(BlockTags.LOGS)
                + ",replaceable=" + groundState.canBeReplaced()
                + ",clearance=" + hasMouseClearance(level, feet);
    }

    public record PathAssessment(boolean reachable, String reason) { }
    public record CampaignAnchor(BlockPos position, boolean elevated) { }

    private static double distanceSqr(BlockPos first, BlockPos second) {
        if (second == null) return 0.0D;
        double x = first.getX() - second.getX();
        double y = first.getY() - second.getY();
        double z = first.getZ() - second.getZ();
        return x * x + y * y + z * z;
    }
}
