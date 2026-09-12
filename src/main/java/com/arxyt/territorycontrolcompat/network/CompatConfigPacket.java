package com.arxyt.territorycontrolcompat.network;

import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Synchronizes all visual compatibility controls from the authoritative server state. */
public record CompatConfigPacket(CompatSavedData.Config config, boolean open) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBoolean(config.balancedOvaryDensity());
        buffer.writeBoolean(config.restrictCaerula());
        buffer.writeBoolean(config.tideRecession());
        buffer.writeBoolean(config.restrictEyes());
        buffer.writeBoolean(config.eyesCollapse());
        buffer.writeBoolean(config.restrictPhayriosis());
        buffer.writeBoolean(config.phayriosisCure());
        buffer.writeBoolean(config.restrictSporeMounds());
        buffer.writeBoolean(config.restrictSporeVigils());
        buffer.writeBoolean(config.restrictSporeSpawnerStructures());
        buffer.writeBoolean(config.restoreSporeOnLoss());
        buffer.writeBoolean(config.restrictSporeInfectionSpread());
        buffer.writeBoolean(config.disableSporeUndergroundBias());
        buffer.writeBoolean(config.restrictAbominationsSpread());
        buffer.writeBoolean(config.purgeAbominationsOnLoss());
        buffer.writeBoolean(config.restrictPrionTerrain());
        buffer.writeBoolean(config.purgePrionOnLoss());
        buffer.writeBoolean(config.ratNationsNaturalRefresh());
        buffer.writeBoolean(config.protectSporeFriendlyEntities());
        buffer.writeBoolean(config.ratNationsCampaigns());
        buffer.writeVarInt(config.ratNationsVictoryCooldownMinutes());
        buffer.writeVarInt(config.ratNationsFailureCooldownMinutes());
        buffer.writeBoolean(config.sporeRegularCampaigns());
        buffer.writeBoolean(config.sporeGrandCampaigns());
        buffer.writeBoolean(config.sporeCampaignScentReinforcements());
        buffer.writeBoolean(config.sporeCampaignMoundEstablishment());
        buffer.writeVarInt(config.sporeCampaignVictoryCooldownMinutes());
        buffer.writeVarInt(config.sporeCampaignFailureCooldownMinutes());
        buffer.writeVarInt(config.sporeGrandCampaignVictoryCooldownMinutes());
        buffer.writeVarInt(config.sporeGrandCampaignFailureCooldownMinutes());
        buffer.writeBoolean(open);
    }

    public static CompatConfigPacket decode(FriendlyByteBuf buffer) {
        CompatSavedData.Config config = new CompatSavedData.Config(
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
        return new CompatConfigPacket(config, buffer.readBoolean());
    }

    public static void handle(CompatConfigPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.arxyt.territorycontrolcompat.client.CompatClientEvents.receiveConfig(packet.config(), packet.open())));
        context.setPacketHandled(true);
    }
}
