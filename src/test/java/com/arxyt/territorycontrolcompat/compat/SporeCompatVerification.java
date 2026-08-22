package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.resources.ResourceLocation;

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
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
