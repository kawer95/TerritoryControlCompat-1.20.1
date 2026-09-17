package com.arxyt.territorycontrolcompat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrolcompat.compat.CaerulaArborCompat;
import com.arxyt.territorycontrolcompat.compat.AbominationsInfectionCompat;
import com.arxyt.territorycontrolcompat.compat.EyesCompat;
import com.arxyt.territorycontrolcompat.compat.PhayriosisCompat;
import com.arxyt.territorycontrolcompat.compat.SporeCompat;
import com.arxyt.territorycontrolcompat.compat.PrionCompat;
import com.arxyt.territorycontrolcompat.compat.SporeEntitySpawnHandler;
import com.arxyt.territorycontrolcompat.compat.SporeFriendlyEntityHandler;
import com.arxyt.territorycontrolcompat.compat.SporeCampaignDirector;
import com.arxyt.territorycontrolcompat.compat.SporeCampaignCommands;
import com.arxyt.territorycontrolcompat.compat.CompatBlockPolicy;
import com.arxyt.territorycontrolcompat.compat.CustomNpcFactionProvider;
import com.arxyt.territorycontrolcompat.compat.CampaignWorldSnapshotCache;
import com.arxyt.territorycontrolcompat.compat.SporeCampaignUnitIndex;
import com.arxyt.territorycontrolcompat.compat.CampaignProbeRegistry;
import com.arxyt.territorycontrolcompat.compat.CampaignRouteProbeService;
import com.arxyt.territorycontrolcompat.compat.CampaignCombatTracker;
import com.arxyt.territorycontrolcompat.compat.CampaignDeploymentService;
import com.arxyt.territorycontrolcompat.compat.CampaignRallyPlacementService;
import com.arxyt.territorycontrolcompat.compat.CampaignProbeFactionProvider;
import com.arxyt.territorycontrolcompat.network.CompatNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import net.minecraftforge.eventbus.api.IEventBus;

@Mod(TerritoryControlCompat.MODID)
public final class TerritoryControlCompat {
    public static final String MODID = "territorycontrolcompat";
    private static final Logger LOGGER = LogUtils.getLogger();

    public TerritoryControlCompat() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        CampaignProbeRegistry.register(modBus);
        TerritoryControlApi.registerEntityFactionProvider(new CampaignProbeFactionProvider());
        CampaignWorldSnapshotCache.register();
        MinecraftForge.EVENT_BUS.register(new CampaignRouteProbeService());
        MinecraftForge.EVENT_BUS.register(new CampaignRallyPlacementService());
        MinecraftForge.EVENT_BUS.register(new CampaignCombatTracker());
        MinecraftForge.EVENT_BUS.register(new CampaignDeploymentService());
        TerritoryControlApi.registerBlockPlacementGuard(CompatBlockPolicy::allowPlacement);
        TerritoryControlApi.registerBlockStateUpdateGuard(CompatBlockPolicy::allowStateUpdate);
        TerritoryControlApi.registerBlockProtectionExemption(CompatBlockPolicy::protectionExempt);
        TerritoryControlApi.registerTerrainCleanupProfile(CaerulaArborCompat.terrainCleanupProfile());
        TerritoryControlApi.registerTerrainCleanupProfile(EyesCompat.terrainCleanupProfile());
        TerritoryControlApi.registerTerrainCleanupProfile(PhayriosisCompat.terrainCleanupProfile());
        TerritoryControlApi.registerTerrainCleanupProfile(SporeCompat.terrainCleanupProfile());
        TerritoryControlApi.registerTerrainCleanupProfile(AbominationsInfectionCompat.terrainCleanupProfile());
        TerritoryControlApi.registerTerrainCleanupProfile(PrionCompat.terrainCleanupProfile());
        if (ModList.get().isLoaded(CustomNpcFactionProvider.MOD_ID)) {
            // Register even when reflection is unavailable: an applicable CNPC entity must remain
            // terminally unmapped instead of falling through to the whole customnpcs namespace.
            TerritoryControlApi.registerEntityFactionProvider(new CustomNpcFactionProvider());
        }
        registerRatNationsCompatWhenPresent();
        MinecraftForge.EVENT_BUS.register(new SporeEntitySpawnHandler());
        MinecraftForge.EVENT_BUS.register(new SporeFriendlyEntityHandler());
        if (ModList.get().isLoaded(SporeCampaignDirector.MOD_ID)) {
            MinecraftForge.EVENT_BUS.register(new SporeCampaignDirector());
            MinecraftForge.EVENT_BUS.register(new SporeCampaignUnitIndex());
            MinecraftForge.EVENT_BUS.addListener(SporeCampaignCommands::register);
            LOGGER.info("[SporeCampaign] director registered on Forge event bus");
        }
        CompatNetwork.register();
        modBus.addListener(this::commonSetup);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            com.arxyt.territorycontrolcompat.client.CompatClientEvents.registerPage();
            com.arxyt.territorycontrolcompat.client.CampaignProbeClient.register(modBus);
        });
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(CompatBlockPolicy::compile);
    }

    /** Keeps every Rat Nations type out of this always-loaded class. */
    private static void registerRatNationsCompatWhenPresent() {
        if (!ModList.get().isLoaded("rat_nations")) return;
        try {
            Class<?> bootstrap = Class.forName("com.arxyt.territorycontrolcompat.compat.RatNationsCompatBootstrap",
                    true, TerritoryControlCompat.class.getClassLoader());
            bootstrap.getMethod("register").invoke(null);
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.error("[RatCampaign] Rat Nations is present but its optional bridge could not be initialized", exception);
        }
    }
}
