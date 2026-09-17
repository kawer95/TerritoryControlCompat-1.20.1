package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks recent dealt or received damage so mobilization never removes units from a live fight. */
public final class CampaignCombatTracker {
    private static final long SAFE_AFTER_TICKS = 200L;
    private static final Map<ServerLevel, Map<UUID, CombatMark>> LAST_RELEVANT_COMBAT = new IdentityHashMap<>();

    public CampaignCombatTracker() { }

    /** Detailed, bounded audit text for a failed campaign-planning round. */
    static String cooldownAudit(ServerLevel level, Collection<UUID> entityIds) {
        if (level == null || entityIds == null || entityIds.isEmpty()) return "none";
        long now = level.getGameTime();
        Map<UUID, CombatMark> marks = LAST_RELEVANT_COMBAT.getOrDefault(level, Map.of());
        List<String> details = new ArrayList<>();
        for (UUID entityId : entityIds) {
            CombatMark mark = marks.get(entityId);
            if (mark == null || now - mark.tick >= SAFE_AFTER_TICKS) continue;
            details.add("unit=" + entityId + ",unitType=" + mark.unitType + ",ageTicks=" + (now - mark.tick)
                    + ",remainingTicks=" + (SAFE_AFTER_TICKS - (now - mark.tick)) + ",reason=" + mark.reason
                    + ",direction=" + mark.direction + ",counterpartType=" + mark.counterpartType
                    + ",counterpartFaction=" + mark.counterpartFaction + ",counterpart=" + mark.counterpartId);
        }
        return details.isEmpty() ? "none" : String.join("|", details);
    }

    static boolean recentlyInCombat(ServerLevel level, UUID entityId) {
        return recentReason(level, entityId) != CombatReason.NONE;
    }

    /** Exact aggregateable cause of a still-active cooldown; never guesses from unrelated damage. */
    static CombatReason recentReason(ServerLevel level, UUID entityId) {
        if (level == null || entityId == null) return CombatReason.NONE;
        CombatMark mark = LAST_RELEVANT_COMBAT.getOrDefault(level, Map.of()).get(entityId);
        return mark != null && level.getGameTime() - mark.tick < SAFE_AFTER_TICKS ? mark.reason : CombatReason.NONE;
    }

    /** A target blocks deployment only when it is a player or a non-allied, faction-mapped entity. */
    static boolean hasBlockingTarget(ServerLevel level, Mob mob) {
        return mob != null && relevantCounterpart(level, mob, mob.getTarget());
    }

    static boolean blocksMobilization(ServerLevel level, Mob mob) {
        return hasBlockingTarget(level, mob) || (mob != null && recentlyInCombat(level, mob.getUUID()));
    }

    @SubscribeEvent
    public void onHurt(LivingHurtEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        Entity victim = event.getEntity();
        Entity source = combatant(event.getSource().getEntity());
        mark(level, victim, source, "RECEIVED_DAMAGE");
        mark(level, source, victim, "DEALT_DAMAGE");
    }

    @SubscribeEvent
    public void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Map<UUID, CombatMark> state = LAST_RELEVANT_COMBAT.get(level);
        if (state != null) state.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LAST_RELEVANT_COMBAT.remove(level);
    }

    private static void mark(ServerLevel level, Entity entity, Entity counterpart, String direction) {
        CombatReason reason = combatReason(level, entity, counterpart);
        if (entity == null || reason == CombatReason.NONE) return;
        String counterpartFaction = counterpart instanceof Player ? "PLAYER"
                : TerritoryControlApi.factionIdForEntity(level, counterpart).orElse("NONE");
        CombatMark next = new CombatMark(level.getGameTime(), reason, direction, entityType(entity),
                entityType(counterpart), counterpartFaction, counterpart.getUUID().toString());
        CombatMark previous = LAST_RELEVANT_COMBAT.computeIfAbsent(level, ignored -> new HashMap<>())
                .put(entity.getUUID(), next);
        if (campaignUnit(entity) && (previous == null || previous.reason != next.reason)) {
            CampaignFailureLog.record("CampaignCombat", "result=COOLDOWN_MARK unit=" + entity.getUUID()
                    + " unitType=" + next.unitType + " reason=" + next.reason + " direction=" + next.direction
                    + " counterpartType=" + next.counterpartType + " counterpartFaction=" + next.counterpartFaction
                    + " counterpart=" + next.counterpartId);
        }
    }

    /**
     * Vanilla/factionless entities never create a cooldown. Faction-mapped allies are also not
     * combat for deployment purposes; a player remains an explicit exception.
     */
    private static boolean relevantCounterpart(ServerLevel level, Entity entity, Entity counterpart) {
        return combatReason(level, entity, counterpart) != CombatReason.NONE;
    }

    private static CombatReason combatReason(ServerLevel level, Entity entity, Entity counterpart) {
        if (level == null || entity == null || counterpart == null) return CombatReason.NONE;
        if (counterpart instanceof Player) return CombatReason.PLAYER;
        if (TerritoryControlApi.factionIdForEntity(level, counterpart).isEmpty()) return CombatReason.NONE;
        return TerritoryControlApi.areEntitiesFriendly(level, entity, counterpart)
                ? CombatReason.NONE : CombatReason.HOSTILE_FACTION;
    }

    enum CombatReason { NONE, PLAYER, HOSTILE_FACTION }
    private record CombatMark(long tick, CombatReason reason, String direction, String unitType,
                              String counterpartType, String counterpartFaction, String counterpartId) { }

    private static boolean campaignUnit(Entity entity) {
        if (entity == null) return false;
        var key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return key != null && ("rat_nations".equals(key.getNamespace()) || "spore".equals(key.getNamespace()));
    }

    private static String entityType(Entity entity) {
        if (entity == null) return "NONE";
        var key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return key == null ? entity.getType().getDescriptionId() : key.toString();
    }

    private static Entity combatant(Entity source) {
        if (source instanceof Projectile projectile && projectile.getOwner() != null) return projectile.getOwner();
        return source;
    }

}
