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
        require(SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("spore", "rooted_mycelium")),
                "sculk conversion output must be restricted and cleaned");
        require(SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("minecraft", "mycelium")),
                "Spore's grass-to-mycelium conversion must be restricted and cleaned");
        require(!SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("spore", "cdu")),
                "non-infection Spore machinery must not be cleaned on a territory loss");
        require(!SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("minecraft", "stone")),
                "ordinary vanilla terrain must not be treated as fungal");
        verifySporeCleanupClassification();
        verifyBuiltinCnpcFactionCatalog();
    }

    private static void verifySporeCleanupClassification() {
        require(SporeCompat.isAirCleanupBlockId(ResourceLocation.fromNamespaceAndPath("spore", "growths_big")),
                "newly grown ground foliage must be removed as air");
        require(SporeCompat.isAirCleanupBlockId(ResourceLocation.fromNamespaceAndPath("spore", "wall_remains")),
                "wall remains must be removed as air");
        require(SporeCompat.isAirCleanupBlockId(ResourceLocation.fromNamespaceAndPath("spore", "rotten_log")),
                "displaced rotten wood must be removed as air");
        require(SporeCompat.isAirCleanupBlockId(ResourceLocation.fromNamespaceAndPath("spore", "rotten_scraps")),
                "rotten door and fence scraps must be removed as air");
        require(SporeCompat.isAirCleanupBlockId(ResourceLocation.fromNamespaceAndPath("spore", "frozen_remains")),
                "frozen remains must be removed as air");
        require(SporeCompat.isAirCleanupBlockId(ResourceLocation.fromNamespaceAndPath("spore", "mycelium_block")),
                "casing fungal stalks must be removed as air");
        require(!SporeCompat.isAirCleanupBlockId(ResourceLocation.fromNamespaceAndPath("spore", "infested_stone")),
                "direct stone conversion must remain a reversible conversion");
        require(!SporeCompat.isAirCleanupBlockId(ResourceLocation.fromNamespaceAndPath("spore", "rotten_grass")),
                "known grass conversion must retain its explicit restoration");
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
