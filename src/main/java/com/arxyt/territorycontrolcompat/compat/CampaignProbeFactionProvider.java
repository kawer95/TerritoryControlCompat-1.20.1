package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.EntityFactionProvider;
import com.arxyt.territorycontrolcompat.TerritoryControlCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** Keeps the internal route probe terminally unmapped so it never participates in capture. */
public final class CampaignProbeFactionProvider implements EntityFactionProvider {
    @Override public String id() { return "territorycontrolcompat:campaign_probe"; }
    @Override public String modId() { return TerritoryControlCompat.MODID; }
    @Override public boolean supports(Entity entity) { return entity instanceof CampaignRouteProbeEntity; }
    @Override public Resolution resolve(Entity entity) { return supports(entity) ? Resolution.unmapped() : Resolution.notApplicable(); }
    @Override public List<Option> options(ServerLevel level) { return List.of(); }
}
