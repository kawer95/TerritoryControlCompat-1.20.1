package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.core.TCPermissions;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

/** Administrator observability and safe cancellation for automatic Spore campaigns. */
public final class SporeCampaignCommands {
    private SporeCampaignCommands() { }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tc")
                .then(Commands.literal("sporecampaign").requires(TCPermissions::canManage)
                        .then(Commands.literal("list").executes(context -> {
                            var lines = SporeCampaignDirector.status(context.getSource().getLevel());
                            if (lines.isEmpty()) context.getSource().sendSuccess(() -> Component.literal("当前维度没有真菌战役。"), false);
                            else lines.forEach(line -> context.getSource().sendSuccess(() -> Component.literal(line), false));
                            return lines.size();
                        }))
                        .then(Commands.literal("abort")
                                .then(Commands.literal("regular").executes(context -> abort(context, SporeCampaignSavedData.Type.REGULAR)))
                                .then(Commands.literal("grand").executes(context -> abort(context, SporeCampaignSavedData.Type.GRAND))))));
    }

    private static int abort(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                             SporeCampaignSavedData.Type type) {
        boolean removed = SporeCampaignDirector.abort(context.getSource().getLevel(), type, true, "管理员取消");
        if (removed) context.getSource().sendSuccess(() -> Component.literal("已取消真菌" + (type == SporeCampaignSavedData.Type.GRAND ? "大型远征。" : "常规蜂群战役。")), true);
        else context.getSource().sendFailure(Component.literal("没有可取消的对应真菌战役。"));
        return removed ? 1 : 0;
    }
}
