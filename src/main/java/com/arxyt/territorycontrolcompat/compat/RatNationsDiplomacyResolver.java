package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratnations.api.FactionRelation;
import com.arxyt.territorycontrol.api.TerritoryControlApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.util.Optional;

/**
 * Territory Control is the sole authority for player-versus-Rat-Nations diplomacy.  An
 * unassigned player or unbound nation is neutral, never a reason to fall back to the Rat Nations
 * reputation ledger.  Rat-versus-rat pairs retain the older complete-pair fallback behaviour.
 */
public final class RatNationsDiplomacyResolver {
    private static final String DOMINION_SWORD = "dominionsword";
    private static final String DOMINION_PLAYER_FACTION = "dominionsword_faction";
    private RatNationsDiplomacyResolver() { }

    public static Optional<FactionRelation> resolve(Entity first, Entity second) {
        if (first == null || second == null || !(first.level() instanceof ServerLevel level)
                || second.level() != level) return Optional.empty();
        Optional<String> firstFaction = factionFor(level, first);
        Optional<String> secondFaction = factionFor(level, second);
        if (firstFaction.isEmpty() || secondFaction.isEmpty()) {
            return first instanceof ServerPlayer || second instanceof ServerPlayer
                    ? Optional.of(FactionRelation.NEUTRAL) : Optional.empty();
        }
        return Optional.of(TerritoryControlApi.areFactionsSameOrAllied(level, firstFaction.get(), secondFaction.get())
                ? FactionRelation.FRIENDLY : FactionRelation.HOSTILE);
    }

    /** Dominion Sword is the only player-faction authority in this modpack. */
    private static Optional<String> factionFor(ServerLevel level, Entity entity) {
        if (entity instanceof ServerPlayer player && ModList.get().isLoaded(DOMINION_SWORD)) {
            String faction = player.getPersistentData().getString(DOMINION_PLAYER_FACTION).trim();
            return faction.isBlank() ? Optional.empty() : Optional.of(faction);
        }
        return TerritoryControlApi.factionIdForEntity(level, entity);
    }
}
