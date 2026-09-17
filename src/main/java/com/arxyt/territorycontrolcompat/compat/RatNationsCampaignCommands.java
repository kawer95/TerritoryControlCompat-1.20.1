package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratnations.api.RatNationsFactionApi;
import com.arxyt.territorycontrol.core.TCPermissions;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.RegisterCommandsEvent;

/** Administrator-only campaign observability and safe cancellation. */
public final class RatNationsCampaignCommands {
    private RatNationsCampaignCommands() { }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tc")
                .then(Commands.literal("ratcampaign").requires(TCPermissions::canManage)
                        .then(Commands.literal("list").executes(context -> {
                            var lines = RatNationsCampaignDirector.status(context.getSource().getLevel());
                            if (lines.isEmpty()) context.getSource().sendSuccess(() -> Component.literal("No Rat Nations campaigns in this dimension."), false);
                            else lines.forEach(line -> context.getSource().sendSuccess(() -> Component.literal(line), false));
                            var metrics = RatNationsCampaignDirector.metrics();
                            context.getSource().sendSuccess(() -> Component.literal("metrics plans=" + metrics.planningAttempts()
                                    + " zones=" + metrics.candidateWarzones() + " paths=" + metrics.pathProbes()
                                    + " start=" + metrics.campaignsStarted() + " win=" + metrics.campaignsCompleted()
                                    + " fail=" + metrics.campaignsFailed() + " pause=" + metrics.pauses()), false);
                            var planner = CampaignPlanningService.metrics();
                            var snapshots = CampaignWorldSnapshotCache.metrics(context.getSource().getLevel());
                            context.getSource().sendSuccess(() -> Component.literal("async running=" + planner.running()
                                    + " queued=" + planner.queued() + " complete=" + planner.completed()
                                    + " cancelled=" + planner.cancelled() + " expired=" + planner.expired()
                                    + " rejected=" + planner.rejected() + " probes=" + CampaignRouteProbeService.activeCount()
                                    + " probeQueue=" + CampaignRouteProbeService.pendingCount()
                                    + " deployments=" + CampaignDeploymentService.pending(context.getSource().getLevel())), false);
                            context.getSource().sendSuccess(() -> Component.literal("snapshots tiles=" + snapshots.pendingTiles()
                                    + " terrainBacklog=" + snapshots.terrainBacklog() + " terrain=" + snapshots.terrainChunks()
                                    + " territories=" + snapshots.territoryChunks() + " maxMainNanos=" + snapshots.maxTickNanos()
                                    + " failureQueue=" + CampaignFailureLog.queued() + " failureDropped=" + CampaignFailureLog.dropped()), false);
                            return lines.size();
                        }))
                        .then(Commands.literal("abort").then(Commands.argument("nation", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(RatNationsFactionApi.factions().stream()
                                        .map(value -> value.id().toString()).toList(), builder))
                                .executes(context -> abort(context.getSource().getLevel(), ResourceLocationArgument.getId(context, "nation"), context))))));
    }

    private static int abort(net.minecraft.server.level.ServerLevel level, ResourceLocation nation,
                             com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context) {
        if (!RatNationsFactionApi.isKnownRatFaction(nation)) {
            context.getSource().sendFailure(Component.literal("Unknown Rat Nations nation: " + nation));
            return 0;
        }
        if (!RatNationsCampaignDirector.abort(level, nation)) {
            context.getSource().sendFailure(Component.literal("No campaign for " + nation + " in this dimension."));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Aborted campaign for " + nation + "."), true);
        return 1;
    }
}
