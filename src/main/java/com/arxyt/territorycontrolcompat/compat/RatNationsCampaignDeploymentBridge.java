package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;

/** Rat-only deployment calls, reached only for a RAT deployment on a Rat-enabled server. */
final class RatNationsCampaignDeploymentBridge {
    private RatNationsCampaignDeploymentBridge() {
    }

    static CampaignDeploymentService.FinalizationResult complete(ServerLevel level,
                                                                  CampaignDeploymentSavedData.Deployment deployment) {
        if (!ModList.get().isLoaded("rat_nations")) {
            return CampaignDeploymentService.FinalizationResult.UNKNOWN_DEPLOYMENT_KIND;
        }
        try {
            return RatNationsCampaignDirector.completeDeployment(level, deployment);
        } catch (LinkageError ignored) {
            return CampaignDeploymentService.FinalizationResult.UNKNOWN_DEPLOYMENT_KIND;
        }
    }

    static String controller(ServerLevel level, String scope) {
        if (!ModList.get().isLoaded("rat_nations")) return "";
        try {
            return TerritoryControlApi.factionForExternal(level, RatNationsFactionProvider.PROVIDER_ID, scope)
                    .map(TerritoryControlApi.FactionView::id).orElse("");
        } catch (LinkageError | RuntimeException ignored) {
            return "";
        }
    }

    static void retry(ServerLevel level, String scope) {
        if (!ModList.get().isLoaded("rat_nations")) return;
        try {
            RatNationsCampaignDirector.retryAfterDeployment(level, scope);
        } catch (LinkageError ignored) {
            // A legacy RAT deployment may remain after a server removes Rat Nations.
        }
    }
}
