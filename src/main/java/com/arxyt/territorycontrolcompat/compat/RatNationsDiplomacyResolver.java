package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratnations.api.FactionRelation;
import com.arxyt.territorycontrol.api.TerritoryControlApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * Territory Control is the sole authority for player-versus-Rat-Nations diplomacy.  An
 * unassigned player or unbound nation is neutral, never a reason to fall back to the Rat Nations
 * reputation ledger.  Rat-versus-rat pairs retain the older complete-pair fallback behaviour.
 */
public final class RatNationsDiplomacyResolver {
    private RatNationsDiplomacyResolver() { }

    public static Optional<FactionRelation> resolve(Entity first, Entity second) {
        if (first == null || second == null || !(first.level() instanceof ServerLevel level)
                || second.level() != level) return Optional.empty();
        Optional<String> firstFaction = TerritoryControlApi.factionIdForEntity(level, first);
        Optional<String> secondFaction = TerritoryControlApi.factionIdForEntity(level, second);
        if (firstFaction.isEmpty() || secondFaction.isEmpty()) {
            return first instanceof Player || second instanceof Player
                    ? Optional.of(FactionRelation.NEUTRAL) : Optional.empty();
        }
        return Optional.of(TerritoryControlApi.areFactionsSameOrAllied(level, firstFaction.get(), secondFaction.get())
                ? FactionRelation.FRIENDLY : FactionRelation.HOSTILE);
    }
}
