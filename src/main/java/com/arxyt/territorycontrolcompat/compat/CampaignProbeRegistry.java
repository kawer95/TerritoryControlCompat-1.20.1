package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrolcompat.TerritoryControlCompat;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Registers the invisible server-driven entity used for short-range campaign route probes. */
public final class CampaignProbeRegistry {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(
            ForgeRegistries.ENTITY_TYPES, TerritoryControlCompat.MODID);
    public static final RegistryObject<EntityType<CampaignRouteProbeEntity>> ROUTE_PROBE = ENTITIES.register(
            "campaign_route_probe", () -> EntityType.Builder.of(CampaignRouteProbeEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F).clientTrackingRange(2).updateInterval(20)
                    .build(TerritoryControlCompat.MODID + ":campaign_route_probe"));

    private CampaignProbeRegistry() { }

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
        bus.addListener(CampaignProbeRegistry::attributes);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(ROUTE_PROBE.get(), CampaignRouteProbeEntity.createAttributes().build());
    }
}
