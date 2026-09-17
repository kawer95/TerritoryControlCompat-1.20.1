package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Invisible, non-persistent ground navigator that validates one staging-to-target route over time. */
public final class CampaignRouteProbeEntity extends PathfinderMob {
    private UUID probeId;
    private BlockPos target;
    private float probeWidth = 0.6F;
    private float probeHeight = 1.8F;
    private int failedStarts;
    private int stagnantTicks;
    private double lastX;
    private double lastZ;

    public CampaignRouteProbeEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setInvisible(true);
        setSilent(true);
        setInvulnerable(true);
        noCulling = true;
    }

    static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D).add(Attributes.FOLLOW_RANGE, 128.0D);
    }

    void configure(UUID id, BlockPos target, float width, float height) {
        this.probeId = id;
        this.target = target.immutable();
        this.probeWidth = Math.max(0.3F, width);
        this.probeHeight = Math.max(0.6F, height);
        this.lastX = getX();
        this.lastZ = getZ();
        refreshDimensions();
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(level() instanceof ServerLevel serverLevel) || probeId == null || target == null) return;
        if (!serverLevel.hasChunk(target.getX() >> 4, target.getZ() >> 4)) {
            finish(false, "TARGET_UNLOADED");
            return;
        }
        if (distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D) <= 4.0D) {
            finish(true, "REACHED_TARGET");
            return;
        }
        if (tickCount >= 1_200) {
            finish(false, "TIMEOUT");
            return;
        }
        if (tickCount % 20 == 1 && (getNavigation().isDone() || failedStarts > 0)) {
            if (getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D)) {
                failedStarts = 0;
            } else if (++failedStarts >= 3) {
                finish(false, "NO_PATH");
                return;
            }
        }
        if (tickCount % 20 == 0) {
            double moved = (getX() - lastX) * (getX() - lastX) + (getZ() - lastZ) * (getZ() - lastZ);
            if (moved < 0.0625D) stagnantTicks += 20; else stagnantTicks = 0;
            lastX = getX();
            lastZ = getZ();
            if (stagnantTicks >= 200) finish(false, "STUCK");
        }
    }

    private void finish(boolean success, String reason) {
        UUID id = probeId;
        probeId = null;
        CampaignRouteProbeService.complete(id, success, reason);
        discard();
    }

    @Override public EntityDimensions getDimensions(Pose pose) { return EntityDimensions.scalable(probeWidth, probeHeight); }
    @Override public boolean isPickable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public boolean shouldBeSaved() { return false; }
    @Override protected void registerGoals() { }
    @Override public void readAdditionalSaveData(CompoundTag tag) { }
    @Override public void addAdditionalSaveData(CompoundTag tag) { }
}
