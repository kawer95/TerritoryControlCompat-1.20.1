package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratnations.api.RatNationsFactionApi;
import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrolcompat.TerritoryControlCompat;
import com.arxyt.territorycontrolcompat.config.RatNationsCivilianConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/** Loaded reflectively only after Forge confirms that Rat Nations is present. */
public final class RatNationsCompatBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RatNationsCompatBootstrap() {
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, RatNationsCivilianConfig.SPEC);
        TerritoryControlApi.registerEntityFactionProvider(new RatNationsFactionProvider());
        RatNationsFactionApi.registerExternalDiplomacyResolver(
                ResourceLocation.fromNamespaceAndPath(TerritoryControlCompat.MODID, "rat_nations_diplomacy"),
                1000, RatNationsDiplomacyResolver::resolve);
        TerritoryControlApi.registerOwnershipChangeListener(RatNationsCivilianControlCompat::onOwnershipChanged);
        MinecraftForge.EVENT_BUS.register(new RatNationsNaturalRefreshSpawner());
        MinecraftForge.EVENT_BUS.register(new RatNationsCampaignDirector());
        MinecraftForge.EVENT_BUS.register(new RatCampaignUnitIndex());
        MinecraftForge.EVENT_BUS.addListener(RatNationsCampaignCommands::register);
        LOGGER.info("[RatCampaign] optional Rat Nations bridge registered");
    }
}
