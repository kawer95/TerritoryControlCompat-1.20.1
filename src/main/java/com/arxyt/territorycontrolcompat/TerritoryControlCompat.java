package com.arxyt.territorycontrolcompat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.ratwarlords.api.RatNationsFactionApi;
import com.arxyt.territorycontrolcompat.compat.CaerulaArborCompat;
import com.arxyt.territorycontrolcompat.compat.AbominationsInfectionCompat;
import com.arxyt.territorycontrolcompat.compat.EyesCompat;
import com.arxyt.territorycontrolcompat.compat.PhayriosisCompat;
import com.arxyt.territorycontrolcompat.compat.SporeCompat;
import com.arxyt.territorycontrolcompat.compat.PrionCompat;
import com.arxyt.territorycontrolcompat.compat.SporeEntitySpawnHandler;
import com.arxyt.territorycontrolcompat.compat.CompatBlockPolicy;
import com.arxyt.territorycontrolcompat.compat.CustomNpcFactionProvider;
import com.arxyt.territorycontrolcompat.compat.RatWarlordsFactionProvider;
import com.arxyt.territorycontrolcompat.compat.RatNationsDiplomacyResolver;
import com.arxyt.territorycontrolcompat.network.CompatNetwork;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.ModList;

@Mod(TerritoryControlCompat.MODID)
public final class TerritoryControlCompat {
    public static final String MODID = "territorycontrolcompat";

    public TerritoryControlCompat() {
        TerritoryControlApi.registerBlockPlacementGuard(CompatBlockPolicy::allowPlacement);
        TerritoryControlApi.registerBlockProtectionExemption(CompatBlockPolicy::protectionExempt);
        TerritoryControlApi.registerOwnershipChangeListener(CaerulaArborCompat::onOwnershipChanged);
        TerritoryControlApi.registerOwnershipChangeListener(EyesCompat::onOwnershipChanged);
        TerritoryControlApi.registerOwnershipChangeListener(PhayriosisCompat::onOwnershipChanged);
        TerritoryControlApi.registerOwnershipChangeListener(SporeCompat::onOwnershipChanged);
        TerritoryControlApi.registerOwnershipChangeListener(AbominationsInfectionCompat::onOwnershipChanged);
        TerritoryControlApi.registerOwnershipChangeListener(PrionCompat::onOwnershipChanged);
        if (ModList.get().isLoaded(CustomNpcFactionProvider.MOD_ID)) {
            // Register even when reflection is unavailable: an applicable CNPC entity must remain
            // terminally unmapped instead of falling through to the whole customnpcs namespace.
            TerritoryControlApi.registerEntityFactionProvider(new CustomNpcFactionProvider());
        }
        if (ModList.get().isLoaded(RatWarlordsFactionProvider.MOD_ID)) {
            TerritoryControlApi.registerEntityFactionProvider(new RatWarlordsFactionProvider());
            RatNationsFactionApi.registerExternalDiplomacyResolver(
                    new net.minecraft.resources.ResourceLocation(MODID, "rat_nations_diplomacy"), 1000,
                    RatNationsDiplomacyResolver::resolve);
        }
        MinecraftForge.EVENT_BUS.register(new SporeEntitySpawnHandler());
        CompatNetwork.register();
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> com.arxyt.territorycontrolcompat.client.CompatClientEvents::registerPage);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(CompatBlockPolicy::compile);
    }
}
