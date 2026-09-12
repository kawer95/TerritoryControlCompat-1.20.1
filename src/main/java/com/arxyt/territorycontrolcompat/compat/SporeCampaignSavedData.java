package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrolcompat.TerritoryControlCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent state for one regular and one grand Spore campaign per dimension. */
public final class SporeCampaignSavedData extends SavedData {
    private static final String DATA_NAME = TerritoryControlCompat.MODID + "_spore_campaigns";
    private final Map<Key, Campaign> campaigns = new LinkedHashMap<>();
    private final Map<Key, Long> cooldowns = new LinkedHashMap<>();

    public static SporeCampaignSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                SporeCampaignSavedData::load, SporeCampaignSavedData::new, DATA_NAME);
    }

    public Campaign campaign(ServerLevel level, Type type) { return campaigns.get(Key.of(level, type)); }
    public List<Campaign> campaigns(ServerLevel level) {
        return campaigns.entrySet().stream().filter(entry -> entry.getKey().dimension().equals(level.dimension().location().toString()))
                .map(Map.Entry::getValue).sorted(Comparator.comparing(value -> value.type().name())).toList();
    }
    public void put(Campaign campaign) { campaigns.put(campaign.key(), campaign); setDirty(); }
    public Campaign remove(ServerLevel level, Type type) { Campaign removed = campaigns.remove(Key.of(level, type)); if (removed != null) setDirty(); return removed; }
    public long cooldownUntil(ServerLevel level, Type type) { return cooldowns.getOrDefault(Key.of(level, type), 0L); }
    public void setCooldownUntil(ServerLevel level, Type type, long value) { cooldowns.put(Key.of(level, type), Math.max(0L, value)); setDirty(); }
    public void markChanged() { setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag campaignList = new ListTag();
        campaigns.values().stream().sorted(Comparator.comparing(value -> value.key().toString())).forEach(value -> campaignList.add(value.save()));
        tag.put("Campaigns", campaignList);
        ListTag cooldownList = new ListTag();
        cooldowns.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag value = new CompoundTag();
            value.putString("Dimension", entry.getKey().dimension());
            value.putString("Type", entry.getKey().type().name());
            value.putLong("Until", entry.getValue());
            cooldownList.add(value);
        });
        tag.put("Cooldowns", cooldownList);
        return tag;
    }

    private static SporeCampaignSavedData load(CompoundTag tag) {
        SporeCampaignSavedData data = new SporeCampaignSavedData();
        for (Tag raw : tag.getList("Campaigns", Tag.TAG_COMPOUND)) {
            Campaign campaign = Campaign.load((CompoundTag) raw);
            if (campaign != null) data.campaigns.put(campaign.key(), campaign);
        }
        for (Tag raw : tag.getList("Cooldowns", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag value = (CompoundTag) raw;
                data.cooldowns.put(new Key(value.getString("Dimension"), Type.valueOf(value.getString("Type"))),
                        Math.max(0L, value.getLong("Until")));
            } catch (RuntimeException ignored) { }
        }
        return data;
    }

    public enum Type { REGULAR, GRAND }
    public enum Phase { MUSTER, ADVANCE, OCCUPY, STABILIZE, ESTABLISH_MOUND, RETREAT }

    public record Key(String dimension, Type type) implements Comparable<Key> {
        static Key of(ServerLevel level, Type type) { return new Key(level.dimension().location().toString(), type); }
        @Override public int compareTo(Key other) { return toString().compareTo(other.toString()); }
        @Override public String toString() { return dimension + ":" + type; }
    }

    public static final class Campaign {
        private final UUID id;
        private final Key key;
        private final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
        private final BlockPos rally, fallback;
        private final List<InfectedMember> members;
        private final List<CalamityMember> calamities;
        private final Set<Long> ignoredChunks = new LinkedHashSet<>();
        private Phase phase;
        private long launchAt, phaseSince;
        private boolean paused;
        private UUID scentId;
        private BlockPos moundAnchor;

        public Campaign(UUID id, Key key, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                        BlockPos rally, BlockPos fallback, List<InfectedMember> members, List<CalamityMember> calamities,
                        Phase phase, long launchAt, long phaseSince) {
            this.id = id; this.key = key; this.minChunkX = minChunkX; this.minChunkZ = minChunkZ;
            this.maxChunkX = maxChunkX; this.maxChunkZ = maxChunkZ; this.rally = rally.immutable(); this.fallback = fallback.immutable();
            this.members = new ArrayList<>(members); this.calamities = new ArrayList<>(calamities);
            this.phase = phase; this.launchAt = launchAt; this.phaseSince = phaseSince;
        }

        public UUID id() { return id; }
        public Key key() { return key; }
        public Type type() { return key.type(); }
        public int minChunkX() { return minChunkX; }
        public int minChunkZ() { return minChunkZ; }
        public int maxChunkX() { return maxChunkX; }
        public int maxChunkZ() { return maxChunkZ; }
        public BlockPos rally() { return rally; }
        public BlockPos fallback() { return fallback; }
        public List<InfectedMember> members() { return List.copyOf(members); }
        public List<CalamityMember> calamities() { return List.copyOf(calamities); }
        public Phase phase() { return phase; }
        public long launchAt() { return launchAt; }
        public long phaseSince() { return phaseSince; }
        public boolean paused() { return paused; }
        public UUID scentId() { return scentId; }
        public BlockPos moundAnchor() { return moundAnchor; }
        public void setPhase(Phase value, long now) { phase = value; phaseSince = now; paused = false; }
        public void setPaused(boolean value) { paused = value; }
        public void setScentId(UUID value) { scentId = value; }
        public void setMoundAnchor(BlockPos value) { moundAnchor = value == null ? null : value.immutable(); }
        public boolean contains(ChunkPos chunk) { return chunk.x >= minChunkX && chunk.x <= maxChunkX && chunk.z >= minChunkZ && chunk.z <= maxChunkZ; }
        public boolean sharesZone(Campaign other) { return other != null && minChunkX <= other.maxChunkX && maxChunkX >= other.minChunkX && minChunkZ <= other.maxChunkZ && maxChunkZ >= other.minChunkZ; }
        public boolean isIgnored(ChunkPos chunk) { return ignoredChunks.contains(chunk.toLong()); }
        public void ignore(ChunkPos chunk) { if (chunk != null) ignoredChunks.add(chunk.toLong()); }
        public void assignObjectives(List<BlockPos> objectives) {
            if (objectives == null || objectives.isEmpty()) return;
            for (int index = 0; index < members.size(); index++) {
                InfectedMember member = members.get(index);
                members.set(index, member.withObjective(objectives.get(index % objectives.size())));
            }
            for (int index = 0; index < calamities.size(); index++) {
                CalamityMember calamity = calamities.get(index);
                calamities.set(index, calamity.withObjective(objectives.get(index % objectives.size())));
            }
        }
        public void markMoundRequested(UUID calamityId) {
            for (int index = 0; index < calamities.size(); index++) {
                CalamityMember value = calamities.get(index);
                if (value.id().equals(calamityId)) calamities.set(index, value.withMoundRequested());
            }
        }
        public void markAdvanceIssued(UUID calamityId) {
            for (int index = 0; index < calamities.size(); index++) {
                CalamityMember value = calamities.get(index);
                if (value.id().equals(calamityId)) calamities.set(index, value.withAdvanceIssued());
            }
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id); tag.putString("Dimension", key.dimension()); tag.putString("Type", key.type().name());
            tag.putInt("MinX", minChunkX); tag.putInt("MinZ", minChunkZ); tag.putInt("MaxX", maxChunkX); tag.putInt("MaxZ", maxChunkZ);
            savePos(tag, "Rally", rally); savePos(tag, "Fallback", fallback); tag.putString("Phase", phase.name());
            tag.putLong("LaunchAt", launchAt); tag.putLong("PhaseSince", phaseSince); tag.putBoolean("Paused", paused);
            tag.putLongArray("IgnoredChunks", new ArrayList<>(ignoredChunks));
            if (scentId != null) tag.putUUID("Scent", scentId);
            if (moundAnchor != null) savePos(tag, "MoundAnchor", moundAnchor);
            ListTag memberList = new ListTag(); for (InfectedMember member : members) memberList.add(member.save()); tag.put("Members", memberList);
            ListTag calamityList = new ListTag(); for (CalamityMember calamity : calamities) calamityList.add(calamity.save()); tag.put("Calamities", calamityList);
            return tag;
        }

        static Campaign load(CompoundTag tag) {
            try {
                List<InfectedMember> members = new ArrayList<>();
                for (Tag raw : tag.getList("Members", Tag.TAG_COMPOUND)) { InfectedMember member = InfectedMember.load((CompoundTag) raw); if (member != null) members.add(member); }
                List<CalamityMember> calamities = new ArrayList<>();
                for (Tag raw : tag.getList("Calamities", Tag.TAG_COMPOUND)) { CalamityMember calamity = CalamityMember.load((CompoundTag) raw); if (calamity != null) calamities.add(calamity); }
                Campaign campaign = new Campaign(tag.getUUID("Id"), new Key(tag.getString("Dimension"), Type.valueOf(tag.getString("Type"))),
                        tag.getInt("MinX"), tag.getInt("MinZ"), tag.getInt("MaxX"), tag.getInt("MaxZ"), loadPos(tag, "Rally"), loadPos(tag, "Fallback"),
                        members, calamities, Phase.valueOf(tag.getString("Phase")), tag.getLong("LaunchAt"), tag.getLong("PhaseSince"));
                campaign.paused = tag.getBoolean("Paused");
                for (long chunk : tag.getLongArray("IgnoredChunks")) campaign.ignoredChunks.add(chunk);
                if (tag.hasUUID("Scent")) campaign.scentId = tag.getUUID("Scent");
                if (tag.contains("MoundAnchor")) campaign.moundAnchor = loadPos(tag, "MoundAnchor");
                return campaign;
            } catch (RuntimeException ignored) { return null; }
        }
    }

    public record InfectedMember(UUID id, boolean originalLinked, BlockPos originalSearch, BlockPos objective) {
        InfectedMember withObjective(BlockPos value) { return new InfectedMember(id, originalLinked, originalSearch, value.immutable()); }
        CompoundTag save() { CompoundTag tag = new CompoundTag(); tag.putUUID("Id", id); tag.putBoolean("Linked", originalLinked); saveOptionalPos(tag, "OriginalSearch", originalSearch); savePos(tag, "Objective", objective); return tag; }
        static InfectedMember load(CompoundTag tag) { try { return new InfectedMember(tag.getUUID("Id"), tag.getBoolean("Linked"), loadOptionalPos(tag, "OriginalSearch"), loadPos(tag, "Objective")); } catch (RuntimeException ignored) { return null; } }
    }

    public record CalamityMember(UUID id, BlockPos originalSearch, BlockPos objective, boolean advanceIssued, boolean moundRequested) {
        CalamityMember withObjective(BlockPos value) { return new CalamityMember(id, originalSearch, value.immutable(), advanceIssued, moundRequested); }
        CalamityMember withAdvanceIssued() { return new CalamityMember(id, originalSearch, objective, true, moundRequested); }
        CalamityMember withMoundRequested() { return new CalamityMember(id, originalSearch, objective, advanceIssued, true); }
        CompoundTag save() { CompoundTag tag = new CompoundTag(); tag.putUUID("Id", id); savePos(tag, "OriginalSearch", originalSearch); savePos(tag, "Objective", objective); tag.putBoolean("AdvanceIssued", advanceIssued); tag.putBoolean("MoundRequested", moundRequested); return tag; }
        static CalamityMember load(CompoundTag tag) { try { return new CalamityMember(tag.getUUID("Id"), loadPos(tag, "OriginalSearch"), loadPos(tag, "Objective"), tag.getBoolean("AdvanceIssued"), tag.getBoolean("MoundRequested")); } catch (RuntimeException ignored) { return null; } }
    }

    private static void savePos(CompoundTag tag, String key, BlockPos pos) { tag.putLong(key, pos.asLong()); }
    private static BlockPos loadPos(CompoundTag tag, String key) { return BlockPos.of(tag.getLong(key)); }
    private static void saveOptionalPos(CompoundTag tag, String key, BlockPos pos) { if (pos != null) savePos(tag, key, pos); }
    private static BlockPos loadOptionalPos(CompoundTag tag, String key) { return tag.contains(key) ? loadPos(tag, key) : null; }
}
