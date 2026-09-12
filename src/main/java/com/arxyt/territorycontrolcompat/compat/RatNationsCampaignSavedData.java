package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratnations.entity.MouseCampaignPhase;
import com.arxyt.territorycontrolcompat.TerritoryControlCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent campaign membership and cooldown state; configuration remains in CompatSavedData. */
public final class RatNationsCampaignSavedData extends SavedData {
    private static final String DATA_NAME = TerritoryControlCompat.MODID + "_rat_campaigns";
    private final Map<Key, Campaign> campaigns = new LinkedHashMap<>();
    private final Map<Key, Long> cooldowns = new LinkedHashMap<>();

    public static RatNationsCampaignSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                RatNationsCampaignSavedData::load, RatNationsCampaignSavedData::new, DATA_NAME);
    }

    public Campaign campaign(ServerLevel level, ResourceLocation nation) {
        return campaigns.get(Key.of(level, nation));
    }

    public List<Campaign> campaigns(ServerLevel level) {
        return campaigns.entrySet().stream().filter(entry -> entry.getKey().dimension().equals(level.dimension().location().toString()))
                .map(Map.Entry::getValue).sorted(Comparator.comparing(value -> value.nation().toString())).toList();
    }

    public void put(Campaign campaign) { campaigns.put(campaign.key(), campaign); setDirty(); }
    public Campaign remove(ServerLevel level, ResourceLocation nation) { Campaign value = campaigns.remove(Key.of(level, nation)); if (value != null) setDirty(); return value; }
    public long cooldownUntil(ServerLevel level, ResourceLocation nation) { return cooldowns.getOrDefault(Key.of(level, nation), 0L); }
    public void setCooldownUntil(ServerLevel level, ResourceLocation nation, long tick) { cooldowns.put(Key.of(level, nation), Math.max(0L, tick)); setDirty(); }
    public void markChanged() { setDirty(); }

    @Override public CompoundTag save(CompoundTag tag) {
        ListTag campaignList = new ListTag();
        campaigns.values().stream().sorted(Comparator.comparing(value -> value.key().toString())).forEach(value -> campaignList.add(value.save()));
        tag.put("Campaigns", campaignList);
        ListTag cooldownList = new ListTag();
        cooldowns.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag value = new CompoundTag();
            value.putString("Dimension", entry.getKey().dimension());
            value.putString("Nation", entry.getKey().nation().toString());
            value.putLong("Until", entry.getValue());
            cooldownList.add(value);
        });
        tag.put("Cooldowns", cooldownList);
        return tag;
    }

    private static RatNationsCampaignSavedData load(CompoundTag tag) {
        RatNationsCampaignSavedData data = new RatNationsCampaignSavedData();
        for (Tag raw : tag.getList("Campaigns", Tag.TAG_COMPOUND)) {
            Campaign campaign = Campaign.load((CompoundTag) raw);
            if (campaign != null) data.campaigns.put(campaign.key(), campaign);
        }
        for (Tag raw : tag.getList("Cooldowns", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                Key key = new Key(value.getString("Dimension"), new ResourceLocation(value.getString("Nation")));
                data.cooldowns.put(key, Math.max(0L, value.getLong("Until")));
            } catch (RuntimeException ignored) { }
        }
        return data;
    }

    public record Key(String dimension, ResourceLocation nation) implements Comparable<Key> {
        static Key of(ServerLevel level, ResourceLocation nation) { return new Key(level.dimension().location().toString(), nation); }
        @Override public int compareTo(Key other) { return toString().compareTo(other.toString()); }
        @Override public String toString() { return dimension + ":" + nation; }
    }

    public static final class Campaign {
        private final UUID id;
        private final Key key;
        private final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
        private final BlockPos rally, fallback;
        private final List<Member> members;
        private final Set<Long> ignoredChunks = new LinkedHashSet<>();
        private MouseCampaignPhase phase;
        private long launchAt, phaseSince;
        private boolean paused;

        public Campaign(UUID id, Key key, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                        BlockPos rally, BlockPos fallback, List<Member> members, MouseCampaignPhase phase,
                        long launchAt, long phaseSince) {
            this.id = id; this.key = key; this.minChunkX = minChunkX; this.minChunkZ = minChunkZ;
            this.maxChunkX = maxChunkX; this.maxChunkZ = maxChunkZ; this.rally = rally.immutable(); this.fallback = fallback.immutable();
            this.members = new ArrayList<>(members); this.phase = phase; this.launchAt = launchAt; this.phaseSince = phaseSince;
        }

        public UUID id() { return id; }
        public Key key() { return key; }
        public ResourceLocation nation() { return key.nation(); }
        public int minChunkX() { return minChunkX; }
        public int minChunkZ() { return minChunkZ; }
        public int maxChunkX() { return maxChunkX; }
        public int maxChunkZ() { return maxChunkZ; }
        public BlockPos rally() { return rally; }
        public BlockPos fallback() { return fallback; }
        public List<Member> members() { return List.copyOf(members); }
        public MouseCampaignPhase phase() { return paused ? MouseCampaignPhase.PAUSED : phase; }
        public long launchAt() { return launchAt; }
        public long phaseSince() { return phaseSince; }
        public void setPhase(MouseCampaignPhase value, long now) { phase = value; phaseSince = now; paused = false; }
        public void setPaused(boolean value) { paused = value; }
        public boolean paused() { return paused; }
        public boolean contains(net.minecraft.world.level.ChunkPos chunk) { return chunk.x >= minChunkX && chunk.x <= maxChunkX && chunk.z >= minChunkZ && chunk.z <= maxChunkZ; }
        public void assignObjectives(List<BlockPos> objectives) {
            if (objectives == null || objectives.isEmpty()) return;
            for (int index = 0; index < members.size(); index++) {
                members.set(index, new Member(members.get(index).id(), objectives.get(index % objectives.size())));
            }
        }
        public boolean isIgnored(net.minecraft.world.level.ChunkPos chunk) { return ignoredChunks.contains(chunk.toLong()); }
        public void ignore(net.minecraft.world.level.ChunkPos chunk) { if (chunk != null) ignoredChunks.add(chunk.toLong()); }
        public Set<Long> ignoredChunks() { return Set.copyOf(ignoredChunks); }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id); tag.putString("Dimension", key.dimension()); tag.putString("Nation", key.nation().toString());
            tag.putInt("MinX", minChunkX); tag.putInt("MinZ", minChunkZ); tag.putInt("MaxX", maxChunkX); tag.putInt("MaxZ", maxChunkZ);
            savePos(tag, "Rally", rally); savePos(tag, "Fallback", fallback);
            tag.putString("Phase", phase.name()); tag.putLong("LaunchAt", launchAt); tag.putLong("PhaseSince", phaseSince); tag.putBoolean("Paused", paused);
            tag.putLongArray("IgnoredChunks", new ArrayList<>(ignoredChunks));
            ListTag list = new ListTag(); for (Member member : members) list.add(member.save()); tag.put("Members", list);
            return tag;
        }

        static Campaign load(CompoundTag tag) {
            try {
                List<Member> members = new ArrayList<>();
                for (Tag raw : tag.getList("Members", Tag.TAG_COMPOUND)) { Member member = Member.load((CompoundTag) raw); if (member != null) members.add(member); }
                Campaign campaign = new Campaign(tag.getUUID("Id"), new Key(tag.getString("Dimension"), new ResourceLocation(tag.getString("Nation"))),
                        tag.getInt("MinX"), tag.getInt("MinZ"), tag.getInt("MaxX"), tag.getInt("MaxZ"), loadPos(tag, "Rally"), loadPos(tag, "Fallback"),
                        members, MouseCampaignPhase.valueOf(tag.getString("Phase")), tag.getLong("LaunchAt"), tag.getLong("PhaseSince"));
                campaign.paused = tag.getBoolean("Paused");
                for (long chunk : tag.getLongArray("IgnoredChunks")) campaign.ignoredChunks.add(chunk);
                return campaign;
            } catch (RuntimeException ignored) { return null; }
        }
    }

    public record Member(UUID id, BlockPos objective) {
        private CompoundTag save() { CompoundTag tag = new CompoundTag(); tag.putUUID("Id", id); savePos(tag, "Objective", objective); return tag; }
        private static Member load(CompoundTag tag) { try { return new Member(tag.getUUID("Id"), loadPos(tag, "Objective")); } catch (RuntimeException ignored) { return null; } }
    }

    private static void savePos(CompoundTag tag, String key, BlockPos pos) { tag.putLong(key, pos.asLong()); }
    private static BlockPos loadPos(CompoundTag tag, String key) { return BlockPos.of(tag.getLong(key)); }
}
