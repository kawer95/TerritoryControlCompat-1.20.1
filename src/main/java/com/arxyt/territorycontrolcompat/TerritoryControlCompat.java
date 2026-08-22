package com.arxyt.territorycontrolcompat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrolcompat.compat.CaerulaArborCompat;
import com.arxyt.territorycontrolcompat.compat.EyesCompat;
import com.arxyt.territorycontrolcompat.compat.PhayriosisCompat;
import com.arxyt.territorycontrolcompat.compat.SporeCompat;
import com.arxyt.territorycontrolcompat.compat.SporeEntitySpawnHandler;
import com.arxyt.territorycontrolcompat.network.CompatNetwork;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.common.MinecraftForge;

@Mod(TerritoryControlCompat.MODID)
public final class TerritoryControlCompat {
    public static final String MODID = "territorycontrolcompat";

    public TerritoryControlCompat() {
        TerritoryControlApi.registerBlockPlacementGuard(CaerulaArborCompat::allowControlledBlockPlacement);
        TerritoryControlApi.registerBlockPlacementGuard(EyesCompat::allowReplacementBlockPlacement);
        TerritoryControlApi.registerBlockPlacementGuard(PhayriosisCompat::allowBlockPlacement);
        TerritoryControlApi.registerBlockPlacementGuard(SporeCompat::allowFungalInfectionBlockPlacement);
        TerritoryControlApi.registerBlockPlacementGuard(SporeCompat::allowOvergrownSpawnerPlacement);
        TerritoryControlApi.registerBlockProtectionExemption((level, pos, oldState, newState) -> CaerulaArborCompat.isTidePollutionBlock(oldState));
        TerritoryControlApi.registerBlockProtectionExemption((level, pos, oldState, newState) -> EyesCompat.isReplacementBlock(oldState));
        TerritoryControlApi.registerBlockProtectionExemption((level, pos, oldState, newState) -> PhayriosisCompat.isPhayriosisBlock(oldState));
        TerritoryControlApi.registerBlockProtectionExemption((level, pos, oldState, newState) -> SporeCompat.isFungalInfectionBlock(oldState));
        TerritoryControlApi.registerOwnershipChangeListener(CaerulaArborCompat::onOwnershipChanged);
        TerritoryControlApi.registerOwnershipChangeListener(EyesCompat::onOwnershipChanged);
        TerritoryControlApi.registerOwnershipChangeListener(PhayriosisCompat::onOwnershipChanged);
        TerritoryControlApi.registerOwnershipChangeListener(SporeCompat::onOwnershipChanged);
        MinecraftForge.EVENT_BUS.register(new SporeEntitySpawnHandler());
        CompatNetwork.register();
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () -> com.arxyt.territorycontrolcompat.client.CompatClientEvents.registerPage());
    }
}
