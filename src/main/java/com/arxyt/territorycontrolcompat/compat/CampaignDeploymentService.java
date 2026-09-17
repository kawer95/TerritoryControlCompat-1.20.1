package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Iterator;

/** Moves at most two selected units per tick and finalizes or rolls back persisted deployments. */
public final class CampaignDeploymentService {
    private static final int MOVES_PER_TICK = 2;

    public CampaignDeploymentService() { }

    static BeginResult begin(ServerLevel level, String kind, String scope, int rejected,
                             CampaignStrategicPlanner.Option option, List<? extends Entity> units,
                             List<? extends Entity> calamities, BlockPos verifiedRally,
                             List<BlockPos> destinations) {
        if (level == null || kind == null || kind.isBlank() || scope == null || scope.isBlank()
                || option == null || units == null || calamities == null || verifiedRally == null
                || destinations == null) return BeginResult.INVALID_INPUT;
        CampaignDeploymentSavedData data = CampaignDeploymentSavedData.get(level);
        if (data.hasScope(kind, scope)) return BeginResult.SCOPE_ALREADY_PENDING;
        List<CampaignDeploymentSavedData.Member> members = new ArrayList<>();
        var candidate = option.candidate();
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level).warzoneConfig().normalized().sizeChunks();
        Iterator<BlockPos> slots = destinations.iterator();
        for (Entity entity : units) {
            boolean inPlace = CampaignStrategicPlanner.inPlace(candidate, size, entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
            if (!inPlace && !slots.hasNext()) return BeginResult.RALLY_PLACEMENT_MISMATCH;
            members.add(new CampaignDeploymentSavedData.Member(entity.getUUID(), entity.blockPosition(),
                    inPlace ? entity.blockPosition() : slots.next(), false, inPlace));
        }
        for (Entity entity : calamities) {
            boolean inPlace = CampaignStrategicPlanner.inPlace(candidate, size, entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
            if (!inPlace && !slots.hasNext()) return BeginResult.RALLY_PLACEMENT_MISMATCH;
            members.add(new CampaignDeploymentSavedData.Member(entity.getUUID(), entity.blockPosition(),
                    inPlace ? entity.blockPosition() : slots.next(), true, inPlace));
        }
        int minX = candidate.target().x() * size, minZ = candidate.target().z() * size;
        data.put(new CampaignDeploymentSavedData.Deployment(UUID.randomUUID(), kind, scope, rejected,
                minX, minZ, minX + size - 1, minZ + size - 1,
                verifiedRally, block(candidate.targetAnchor()), members,
                CampaignDeploymentSavedData.Mode.DEPLOY));
        return BeginResult.STARTED;
    }

    static int pending(ServerLevel level) { return CampaignDeploymentSavedData.get(level).deployments().size(); }

    static boolean isInPlace(ServerLevel level, CampaignDeploymentSavedData.Deployment deployment, Entity entity) {
        if (level == null || deployment == null || entity == null) return false;
        int size = com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level).warzoneConfig().normalized().sizeChunks();
        CampaignStrategicPlanner.ZoneKey target = CampaignStrategicPlanner.zone(
                deployment.target.getX() >> 4, deployment.target.getZ() >> 4, size);
        CampaignStrategicPlanner.ChunkKey rally = new CampaignStrategicPlanner.ChunkKey(
                deployment.rally.getX() >> 4, deployment.rally.getZ() >> 4);
        return CampaignStrategicPlanner.zone(entity.getBlockX() >> 4, entity.getBlockZ() >> 4, size).equals(target)
                || new CampaignStrategicPlanner.ChunkKey(entity.getBlockX() >> 4, entity.getBlockZ() >> 4).equals(rally);
    }

    static void rollback(ServerLevel level, String kindPrefix) {
        CampaignDeploymentSavedData data = CampaignDeploymentSavedData.get(level);
        boolean changed = false;
        for (CampaignDeploymentSavedData.Deployment deployment : data.deployments()) {
            if (deployment.kind.startsWith(kindPrefix) && deployment.mode != CampaignDeploymentSavedData.Mode.ROLLBACK) {
                deployment.mode = CampaignDeploymentSavedData.Mode.ROLLBACK;
                changed = true;
            }
        }
        if (changed) data.changed();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int remaining = MOVES_PER_TICK;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            CampaignDeploymentSavedData data = CampaignDeploymentSavedData.get(level);
            for (CampaignDeploymentSavedData.Deployment deployment : data.deployments()) {
                if (remaining <= 0) return;
                if (deployment.mode == CampaignDeploymentSavedData.Mode.DEPLOY) {
                    remaining -= deploy(level, data, deployment, remaining);
                } else {
                    remaining -= rollback(level, data, deployment, remaining);
                }
            }
        }
    }

    private static int deploy(ServerLevel level, CampaignDeploymentSavedData data,
                              CampaignDeploymentSavedData.Deployment deployment, int budget) {
        String controller = controller(level, deployment);
        CampaignStrategicPlanner.ZoneKey target = CampaignStrategicPlanner.zone(
                deployment.minChunkX, deployment.minChunkZ,
                com.arxyt.territorycontrol.core.data.TerritorySavedData.get(level).warzoneConfig().normalized().sizeChunks());
        CampaignStrategicPlanner.ChunkKey rally = new CampaignStrategicPlanner.ChunkKey(
                deployment.rally.getX() >> 4, deployment.rally.getZ() >> 4);
        CampaignTerritoryConditions.TargetState targetState = CampaignTerritoryConditions.targetState(level, controller, target);
        if (targetState != CampaignTerritoryConditions.TargetState.ACTIVE) {
            failDeployment(level, data, deployment, targetState == CampaignTerritoryConditions.TargetState.FULLY_FRIENDLY
                    ? "TARGET_WARZONE_FULLY_FRIENDLY" : "TARGET_NO_REACHABLE_OBJECTIVES", true);
            return 0;
        }
        if (!CampaignTerritoryConditions.rallyChunkOwnedByFriendlyBloc(level, controller, rally)) {
            failDeployment(level, data, deployment, "RALLY_OWNER_LOST", true);
            return 0;
        }
        int moved = 0;
        for (int index = 0; index < deployment.members.size() && moved < budget; index++) {
            CampaignDeploymentSavedData.Member member = deployment.members.get(index);
            if (member.moved) continue;
            Entity entity = level.getEntity(member.id);
            if (!(entity instanceof Mob mob) || !entity.isAlive() || CampaignCombatTracker.blocksMobilization(level, mob)) {
                failDeployment(level, data, deployment, "MEMBER_UNAVAILABLE_OR_BUSY", false);
                return moved;
            }
            BlockPos slot = member.destination;
            if (slot == null || !safeSlot(level, mob, slot)) {
                failDeployment(level, data, deployment, "RALLY_NO_SAFE_SLOTS", true);
                return moved;
            }
            teleport(mob, slot);
            member.moved = true;
            data.changed();
            moved++;
        }
        if (deployment.members.stream().allMatch(member -> member.moved)) {
            FinalizationResult complete = switch (deployment.kind) {
                case "RAT" -> RatNationsCampaignDeploymentBridge.complete(level, deployment);
                case "SPORE_REGULAR", "SPORE_GRAND" -> SporeCampaignDirector.completeDeployment(level, deployment);
                default -> FinalizationResult.UNKNOWN_DEPLOYMENT_KIND;
            };
            if (complete == FinalizationResult.STARTED) {
                CampaignUnitReservations.release(jobKey(level, deployment));
                data.remove(deployment.id);
            } else {
                boolean retry = complete == FinalizationResult.TARGET_WARZONE_FULLY_FRIENDLY
                        || complete == FinalizationResult.TARGET_NO_REACHABLE_OBJECTIVES
                        || complete == FinalizationResult.RALLY_OWNER_LOST;
                failDeployment(level, data, deployment, "FINALIZATION_" + complete, retry);
            }
        }
        return moved;
    }

    private static int rollback(ServerLevel level, CampaignDeploymentSavedData data,
                                CampaignDeploymentSavedData.Deployment deployment, int budget) {
        int moved = 0;
        for (CampaignDeploymentSavedData.Member member : deployment.members) {
            if (!member.moved || moved >= budget) continue;
            if (!level.hasChunk(member.origin.getX() >> 4, member.origin.getZ() >> 4)) continue;
            Entity entity = level.getEntity(member.id);
            if (entity instanceof Mob mob && entity.isAlive()) {
                BlockPos slot = safeSlot(level, mob, member.origin, 0);
                if (slot == null) continue;
                teleport(mob, slot);
            }
            member.moved = false;
            data.changed();
            moved++;
        }
        if (deployment.members.stream().noneMatch(member -> member.moved)) {
            CampaignUnitReservations.release(jobKey(level, deployment));
            data.remove(deployment.id);
            if (deployment.retryAfterRollback) scheduleRetry(level, deployment);
        }
        return moved;
    }

    private static void teleport(Mob mob, BlockPos pos) {
        mob.getNavigation().stop();
        mob.stopRiding();
        mob.setDeltaMovement(0.0D, 0.0D, 0.0D);
        mob.fallDistance = 0.0F;
        mob.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, mob.getYRot(), mob.getXRot());
    }

    private static BlockPos safeSlot(ServerLevel level, Mob mob, BlockPos center, int index) {
        // Probe a deterministic 8x8 grid across the whole rally/origin chunk. The old five-block
        // strip could reject a perfectly usable rally chunk merely because that one row was water,
        // foliage, a wall, or occupied.
        int chunkX = center.getX() >> 4, chunkZ = center.getZ() >> 4;
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        int start = Math.floorMod(index * 17, 64);
        for (int offset = 0; offset < 64; offset++) {
            int grid = (start + offset) & 63;
            int x = baseX + ((grid & 7) << 1) + 1;
            int z = baseZ + ((grid >> 3) << 1) + 1;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            BlockPos ground = candidate.below();
            if (!level.getFluidState(candidate).isEmpty() || !level.getFluidState(ground).isEmpty()
                    || !level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) continue;
            AABB moved = mob.getBoundingBox().move(candidate.getX() + 0.5D - mob.getX(),
                    candidate.getY() - mob.getY(), candidate.getZ() + 0.5D - mob.getZ());
            if (level.noCollision(mob, moved)) return candidate;
        }
        return null;
    }

    private static boolean safeSlot(ServerLevel level, Mob mob, BlockPos candidate) {
        BlockPos ground = candidate.below();
        if (!level.getFluidState(candidate).isEmpty() || !level.getFluidState(ground).isEmpty()
                || !level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) return false;
        AABB moved = mob.getBoundingBox().move(candidate.getX() + 0.5D - mob.getX(),
                candidate.getY() - mob.getY(), candidate.getZ() + 0.5D - mob.getZ());
        return level.noCollision(mob, moved);
    }

    private static BlockPos block(CampaignStrategicPlanner.Point point) { return new BlockPos(point.x(), point.y(), point.z()); }
    private static CampaignPlanningService.JobKey jobKey(ServerLevel level, CampaignDeploymentSavedData.Deployment deployment) {
        return new CampaignPlanningService.JobKey(level.dimension().location().toString(),
                deployment.kind.equals("RAT") ? "rat" : "spore", deployment.scope);
    }

    private static void failDeployment(ServerLevel level, CampaignDeploymentSavedData data,
                                       CampaignDeploymentSavedData.Deployment deployment, String reason,
                                       boolean retryAfterRollback) {
        if (deployment.mode == CampaignDeploymentSavedData.Mode.ROLLBACK) return;
        deployment.mode = CampaignDeploymentSavedData.Mode.ROLLBACK;
        deployment.retryAfterRollback = retryAfterRollback;
        data.changed();
        if (!retryAfterRollback) {
            CampaignFailureLog.record("CampaignDeployment", "result=FAILURE dimension=" + level.dimension().location()
                    + " kind=" + deployment.kind + " scope=" + deployment.scope + " reason=" + reason);
        }
    }

    private static String controller(ServerLevel level, CampaignDeploymentSavedData.Deployment deployment) {
        if ("RAT".equals(deployment.kind)) {
            return RatNationsCampaignDeploymentBridge.controller(level, deployment.scope);
        }
        return com.arxyt.territorycontrol.api.TerritoryControlApi.factionIdForMod(level, "spore").orElse("");
    }

    private static void scheduleRetry(ServerLevel level, CampaignDeploymentSavedData.Deployment deployment) {
        if ("RAT".equals(deployment.kind)) RatNationsCampaignDeploymentBridge.retry(level, deployment.scope);
        else SporeCampaignDirector.retryAfterDeployment(level, deployment.scope);
    }

    enum BeginResult { STARTED, INVALID_INPUT, SCOPE_ALREADY_PENDING, RALLY_PLACEMENT_MISMATCH }
    enum FinalizationResult {
        STARTED,
        UNKNOWN_DEPLOYMENT_KIND,
        INVALID_SCOPE,
        CAMPAIGN_ALREADY_ACTIVE,
        CONTROLLER_MISSING,
        TARGET_WARZONE_FULLY_FRIENDLY,
        TARGET_NO_REACHABLE_OBJECTIVES,
        RALLY_OWNER_LOST,
        RALLY_SAFETY_BLOCKED,
        MEMBER_BUSY,
        MINIMUM_UNITS
    }
}
