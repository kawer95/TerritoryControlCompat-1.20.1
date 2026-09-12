package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves dry, standable campaign anchors. Territory Control captures chunks, but campaigns
 * must never order land armies to occupy a water surface just because that chunk has a territory.
 */
public final class CampaignTerrainResolver {
    private static final int[] SAMPLE_OFFSETS = {2, 6, 10, 14};

    private CampaignTerrainResolver() {
    }

    /** Finds the nearest sampled dry landing point in a loaded chunk. */
    public static Optional<BlockPos> findLandAnchor(ServerLevel level, ChunkPos chunk, BlockPos preferred) {
        if (level == null || chunk == null || !level.hasChunk(chunk.x, chunk.z)) return Optional.empty();
        return java.util.Arrays.stream(SAMPLE_OFFSETS).boxed()
                .flatMap(xOffset -> java.util.Arrays.stream(SAMPLE_OFFSETS).mapToObj(zOffset -> {
                    int x = chunk.getMinBlockX() + xOffset;
                    int z = chunk.getMinBlockZ() + zOffset;
                    int feetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos feet = new BlockPos(x, feetY, z);
                    return isSafeLandAnchor(level, feet) ? feet : null;
                }))
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparingDouble(pos -> distanceSqr(pos, preferred)));
    }

    /** A campaign anchor has dry, sturdy ground and two clear blocks for a land mob. */
    public static boolean isSafeLandAnchor(ServerLevel level, BlockPos feet) {
        if (level == null || feet == null || !level.hasChunk(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        BlockPos ground = feet.below();
        BlockState groundState = level.getBlockState(ground);
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        return hasDryClearance(groundState, feetState, headState)
                && groundState.isFaceSturdy(level, ground, Direction.UP)
                && feet.getY() >= level.getMinBuildHeight() && feet.getY() + 1 < level.getMaxBuildHeight();
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
        if (mob == null || target == null || !mob.isAlive() || !(mob.level() instanceof ServerLevel level)
                || !isSafeLandAnchor(level, target)) return false;
        Path path = mob.getNavigation().createPath(target, 0);
        return path != null && path.canReach();
    }

    public static <T extends Mob> List<T> reachable(List<T> mobs, BlockPos target) {
        return mobs.stream().filter(mob -> canReach(mob, target)).toList();
    }

    public static int twoThirds(int size) {
        return Math.max(1, (size * 2 + 2) / 3);
    }

    private static double distanceSqr(BlockPos first, BlockPos second) {
        if (second == null) return 0.0D;
        double x = first.getX() - second.getX();
        double y = first.getY() - second.getY();
        double z = first.getZ() - second.getZ();
        return x * x + y * y + z * z;
    }
}
