package com.arxyt.territorycontrolcompat.client;

import com.arxyt.territorycontrolcompat.compat.CampaignProbeRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/** Client-only no-op renderer registration for the invisible campaign route probe. */
public final class CampaignProbeClient {
    private CampaignProbeClient() { }

    public static void register(IEventBus bus) {
        bus.addListener(CampaignProbeClient::renderers);
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CampaignProbeRegistry.ROUTE_PROBE.get(), NoopRenderer::new);
    }
}
