package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persists partially transferred squads so deployment can resume or roll back after a restart. */
final class CampaignDeploymentSavedData extends SavedData {
    private static final String DATA_NAME = "territorycontrolcompat_campaign_deployments";
    private final Map<UUID, Deployment> deployments = new LinkedHashMap<>();

    static CampaignDeploymentSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(CampaignDeploymentSavedData::load,
                CampaignDeploymentSavedData::new, DATA_NAME);
    }

    List<Deployment> deployments() { return List.copyOf(deployments.values()); }
    boolean hasScope(String kind, String scope) { return deployments.values().stream().anyMatch(value -> value.kind.equals(kind) && value.scope.equals(scope)); }
    boolean containsMember(UUID id) { return deployments.values().stream().flatMap(value -> value.members.stream()).anyMatch(member -> member.id.equals(id)); }
    void put(Deployment deployment) { deployments.put(deployment.id, deployment); setDirty(); }
    void remove(UUID id) { if (deployments.remove(id) != null) setDirty(); }
    void changed() { setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        deployments.values().forEach(value -> list.add(value.save()));
        tag.put("Deployments", list);
        return tag;
    }

    private static CampaignDeploymentSavedData load(CompoundTag tag) {
        CampaignDeploymentSavedData data = new CampaignDeploymentSavedData();
        ListTag list = tag.getList("Deployments", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            Deployment deployment = Deployment.load(list.getCompound(index));
            if (deployment != null) data.deployments.put(deployment.id, deployment);
        }
        return data;
    }

    enum Mode { DEPLOY, ROLLBACK }

    static final class Deployment {
        final UUID id;
        final String kind;
        final String scope;
        final int rejectedCandidates;
        final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
        final BlockPos rally, target;
        final List<Member> members;
        Mode mode;
        boolean retryAfterRollback;

        Deployment(UUID id, String kind, String scope, int rejectedCandidates,
                   int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                   BlockPos rally, BlockPos target, List<Member> members, Mode mode) {
            this.id = id; this.kind = kind; this.scope = scope; this.rejectedCandidates = rejectedCandidates;
            this.minChunkX = minChunkX; this.minChunkZ = minChunkZ; this.maxChunkX = maxChunkX; this.maxChunkZ = maxChunkZ;
            this.rally = rally.immutable(); this.target = target.immutable(); this.members = new ArrayList<>(members); this.mode = mode;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id); tag.putString("Kind", kind); tag.putString("Scope", scope);
            tag.putInt("Rejected", rejectedCandidates); tag.putInt("MinX", minChunkX); tag.putInt("MinZ", minChunkZ);
            tag.putInt("MaxX", maxChunkX); tag.putInt("MaxZ", maxChunkZ); tag.putLong("Rally", rally.asLong());
            tag.putLong("Target", target.asLong()); tag.putString("Mode", mode.name());
            tag.putBoolean("RetryAfterRollback", retryAfterRollback);
            ListTag memberList = new ListTag(); members.forEach(value -> memberList.add(value.save())); tag.put("Members", memberList);
            return tag;
        }

        static Deployment load(CompoundTag tag) {
            if (!tag.hasUUID("Id") || !tag.contains("Kind") || !tag.contains("Scope")) return null;
            List<Member> members = new ArrayList<>();
            ListTag list = tag.getList("Members", Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) { Member member = Member.load(list.getCompound(index)); if (member != null) members.add(member); }
            Mode mode;
            try { mode = Mode.valueOf(tag.getString("Mode")); } catch (IllegalArgumentException ignored) { mode = Mode.ROLLBACK; }
            Deployment deployment = new Deployment(tag.getUUID("Id"), tag.getString("Kind"), tag.getString("Scope"), tag.getInt("Rejected"),
                    tag.getInt("MinX"), tag.getInt("MinZ"), tag.getInt("MaxX"), tag.getInt("MaxZ"),
                    BlockPos.of(tag.getLong("Rally")), BlockPos.of(tag.getLong("Target")), members, mode);
            deployment.retryAfterRollback = tag.getBoolean("RetryAfterRollback");
            if (members.stream().anyMatch(member -> !member.moved && member.destination == null)) {
                deployment.mode = Mode.ROLLBACK;
                deployment.retryAfterRollback = true;
            }
            return deployment;
        }
    }

    static final class Member {
        final UUID id;
        final BlockPos origin;
        final BlockPos destination;
        final boolean calamity;
        boolean moved;

        Member(UUID id, BlockPos origin, BlockPos destination, boolean calamity, boolean moved) {
            this.id = id; this.origin = origin.immutable(); this.destination = destination == null ? null : destination.immutable();
            this.calamity = calamity; this.moved = moved;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag(); tag.putUUID("Id", id); tag.putLong("Origin", origin.asLong());
            if (destination != null) tag.putLong("Destination", destination.asLong());
            tag.putBoolean("Calamity", calamity); tag.putBoolean("Moved", moved); return tag;
        }

        static Member load(CompoundTag tag) {
            return tag.hasUUID("Id") ? new Member(tag.getUUID("Id"), BlockPos.of(tag.getLong("Origin")),
                    tag.contains("Destination", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("Destination")) : null,
                    tag.getBoolean("Calamity"), tag.getBoolean("Moved")) : null;
        }
    }
}
