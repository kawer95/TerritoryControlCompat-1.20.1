package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.EntityFactionProvider;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Small dependency-free verification entry point for the infection-block classifier. */
public final class SporeCompatVerification {
    private SporeCompatVerification() {
    }

    public static void main(String[] args) {
        require(SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("spore", "infested_stone")),
                "infested stone must be restricted and cleaned");
        require(SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("spore", "growths_big")),
                "fungal foliage must be restricted and cleaned");
        require(SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("minecraft", "mycelium")),
                "Spore's grass-to-mycelium conversion must be restricted and cleaned");
        require(!SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("spore", "cdu")),
                "non-infection Spore machinery must not be cleaned on a territory loss");
        require(!SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("minecraft", "stone")),
                "ordinary vanilla terrain must not be treated as fungal");
        verifyBuiltinCnpcFactionCatalog();
    }

    private static void verifyBuiltinCnpcFactionCatalog() {
        List<EntityFactionProvider.Option> defaults = CustomNpcFactionProvider.completeBuiltinFactionCatalog(List.of());
        require(defaults.stream().anyMatch(option -> option.key().equals("0") && option.name().equals("Friendly")),
                "CNPC Friendly default must remain visible");
        require(defaults.stream().anyMatch(option -> option.key().equals("1") && option.name().equals("Neutral")),
                "CNPC Neutral default must remain visible");
        require(defaults.stream().anyMatch(option -> option.key().equals("2") && option.name().equals("Aggressive")),
                "CNPC Aggressive default must remain visible");
        List<EntityFactionProvider.Option> renamed = CustomNpcFactionProvider.completeBuiltinFactionCatalog(
                List.of(new EntityFactionProvider.Option("0", "友好", 0x00DD00)));
        require(renamed.stream().anyMatch(option -> option.key().equals("0") && option.name().equals("友好")),
                "CNPC-provided localized name must not be overwritten");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
