package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratwarlords.api.FactionDescriptor;
import com.arxyt.ratwarlords.api.RatWarlordsFactionApi;
import com.arxyt.territorycontrol.api.EntityFactionProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Optional Rat Warlords bridge. It keeps the alliance and warlord armies independently
 * assignable instead of collapsing every {@code rat_warlords} entity into one mod faction.
 * Unknown entities from the namespace are deliberately terminally unmapped.
 */
public final class RatWarlordsFactionProvider implements EntityFactionProvider {
    public static final String PROVIDER_ID = "rat_warlords:faction";
    public static final String MOD_ID = "rat_warlords";

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public boolean supports(Entity entity) {
        if (entity == null) return false;
        if (RatWarlordsFactionApi.factionOf(entity).isPresent()) return true;
        ResourceLocation entityId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return entityId != null && MOD_ID.equals(entityId.getNamespace());
    }

    @Override
    public Resolution resolve(Entity entity) {
        if (!supports(entity)) return Resolution.notApplicable();
        return RatWarlordsFactionApi.factionOf(entity)
                .map(id -> Resolution.mapped(factionKey(id)))
                .orElseGet(Resolution::unmapped);
    }

    @Override
    public List<Option> options(ServerLevel level) {
        return factionCatalog();
    }

    static List<Option> factionCatalog() {
        return RatWarlordsFactionApi.factions().stream()
                .map(RatWarlordsFactionProvider::toOption)
                .toList();
    }

    static String factionKey(ResourceLocation id) {
        return id.toString();
    }

    private static Option toOption(FactionDescriptor faction) {
        return new Option(factionKey(faction.id()), faction.displayName().getString(), faction.color());
    }
}
