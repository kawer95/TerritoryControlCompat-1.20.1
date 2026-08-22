package com.arxyt.territorycontrolcompat.network;

import com.arxyt.territorycontrolcompat.TerritoryControlCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/** Owns the versioned wire format for the compatibility configuration screen. */
public final class CompatNetwork {
    private static final String PROTOCOL_VERSION = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(TerritoryControlCompat.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);
    private static int id;

    private CompatNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(id++, OpenCompatPacket.class, OpenCompatPacket::encode, OpenCompatPacket::decode,
                OpenCompatPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, CompatConfigPacket.class, CompatConfigPacket::encode, CompatConfigPacket::decode,
                CompatConfigPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SaveCompatPacket.class, SaveCompatPacket::encode, SaveCompatPacket::decode,
                SaveCompatPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
}
