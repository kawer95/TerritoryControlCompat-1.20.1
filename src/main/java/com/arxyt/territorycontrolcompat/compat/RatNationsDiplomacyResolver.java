package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratwarlords.api.FactionRelation;
import com.arxyt.territorycontrol.api.TerritoryControlApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * Territory Control is authoritative only for a pair it can resolve completely. A missing
 * faction intentionally returns empty so Rat Nations retains its local player reputation rules.
 */
public final class RatNationsDiplomacyResolver {
    private RatNationsDiplomacyResolver() { }

    public static Optional<FactionRelation> resolve(Entity first, Entity second) {
        if (first == null || second == null || !(first.level() instanceof ServerLevel level)
                || second.level() != level) return Optional.empty();
        Optional<String> firstFaction = TerritoryControlApi.factionIdForEntity(level, first);
        Optional<String> secondFaction = TerritoryControlApi.factionIdForEntity(level, second);
        if (firstFaction.isEmpty() || secondFaction.isEmpty()) return Optional.empty();
        return Optional.of(TerritoryControlApi.areFactionsSameOrAllied(level, firstFaction.get(), secondFaction.get())
                ? FactionRelation.FRIENDLY : FactionRelation.HOSTILE);
    }
}
