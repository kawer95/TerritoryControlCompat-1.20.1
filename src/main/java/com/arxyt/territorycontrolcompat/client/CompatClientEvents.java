package com.arxyt.territorycontrolcompat.client;

import com.arxyt.territorycontrol.client.api.TerritoryControlClientApi;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import com.arxyt.territorycontrolcompat.network.CompatNetwork;
import com.arxyt.territorycontrolcompat.network.OpenCompatPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CompatClientEvents {
    private static CompatSavedData.Config config = CompatSavedData.Config.DEFAULT;
    private static Screen pendingParent;

    private CompatClientEvents() {
    }

    public static void registerPage() {
        TerritoryControlClientApi.registerConfigPage("territorycontrolcompat:compat", Component.translatable("screen.territorycontrol.page.mod_compat"), CompatClientEvents::openScreen);
    }

    private static void openScreen(Screen parent) {
        pendingParent = parent;
        CompatNetwork.CHANNEL.sendToServer(new OpenCompatPacket());
    }

    public static void receiveConfig(CompatSavedData.Config next, boolean open) {
        config = next;
        if (open && pendingParent != null) {
            Minecraft.getInstance().setScreen(new CompatScreen(pendingParent, config));
            pendingParent = null;
        }
    }
}
