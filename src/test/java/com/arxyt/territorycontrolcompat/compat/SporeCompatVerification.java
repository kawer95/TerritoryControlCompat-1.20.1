package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.EntityFactionProvider;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import com.arxyt.territorycontrolcompat.network.CompatConfigPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
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
        require(SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("spore", "bile")),
                "casing-generated bile must be restricted and cleaned");
        require(SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("spore", "crusted_bile")),
                "solidified bile must remain restricted and cleaned");
        require(!SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("spore", "cdu")),
                "non-infection Spore machinery must not be cleaned on a territory loss");
        require(!SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("minecraft", "stone")),
                "ordinary vanilla terrain must not be treated as fungal");
        verifySporeCleanupClassification();
        verifySporeOrganoidClassification();
        verifyPhayriosisInsectClassification();
        verifyAbominationsInfectionClassification();
        verifyPrionClassification();
        verifyCompatConfigPacketRoundTrip();
        verifyBuiltinCnpcFactionCatalog();
    }

    private static void verifyCompatConfigPacketRoundTrip() {
        CompatSavedData.Config expected = CompatSavedData.Config.DEFAULT
                .withRestrictPrionTerrain(true)
                .withPurgePrionOnLoss(true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        new CompatConfigPacket(expected, true).encode(buffer);
        CompatConfigPacket decoded = CompatConfigPacket.decode(buffer);
        require(decoded.open() && decoded.config().equals(expected),
                "compat config network order must preserve the Prion controls");
        buffer.release();
    }

    private static void verifyPrionClassification() {
        List.of("root_block", "living_block", "nox_block", "entrails_block", "eggs_block")
                .forEach(path -> require(PrionCompat.isTerritoryBlockId(
                                ResourceLocation.fromNamespaceAndPath("prionmod", path)),
                        path + " must be restricted and purged as Prion terrain"));
        require(PrionCompat.isEggsBlockId(
                        ResourceLocation.fromNamespaceAndPath("prionmod", "eggs_block")),
                "eggs block must be recognized for its lifecycle removal exemption");
        require(!PrionCompat.isTerritoryBlockId(
                        ResourceLocation.fromNamespaceAndPath("prionmod", "decorative_block")),
                "unknown Prion blocks must not be purged");
        require(!PrionCompat.isTerritoryBlockId(
                        ResourceLocation.fromNamespaceAndPath("minecraft", "rooted_dirt")),
                "vanilla terrain must not be treated as Prion terrain");
    }

    private static void verifyAbominationsInfectionClassification() {
        List.of("rooted_stone", "rooted_log", "red_root_block", "heart_root_core",
                        "tumorroot", "parasitic_worm_colony")
                .forEach(path -> require(AbominationsInfectionCompat.isInfectionBlockId(
                                ResourceLocation.fromNamespaceAndPath("abominations_infection", path)),
                        path + " must be restricted as Abominations infection terrain"));
        require(AbominationsInfectionCompat.cleanupReplacementId(
                        ResourceLocation.fromNamespaceAndPath("abominations_infection", "rooted_stone"))
                        .equals(ResourceLocation.withDefaultNamespace("stone")),
                "rooted stone must restore solid vanilla terrain");
        require(AbominationsInfectionCompat.cleanupReplacementId(
                        ResourceLocation.fromNamespaceAndPath("abominations_infection", "heart_root"))
                        .equals(ResourceLocation.withDefaultNamespace("air")),
                "newly grown roots must be removed as air");
        require(!AbominationsInfectionCompat.isInfectionBlockId(
                        ResourceLocation.fromNamespaceAndPath("abominations_infection", "teeth_block_bricks")),
                "player construction blocks must not be purged as spreading terrain");
    }

    private static void verifyPhayriosisInsectClassification() {
        List.of("phayrectix", "phayrilesh_mite", "assimilated_mite", "alterack_mite", "siege_mite")
                .forEach(path -> require(PhayriosisCompat.isSmallInsectId(
                                ResourceLocation.fromNamespaceAndPath("phayriosis", path)),
                        path + " must be recognized as a Phayriosis insect"));
        require(!PhayriosisCompat.isSmallInsectId(
                        ResourceLocation.fromNamespaceAndPath("phayriosis", "primitive_dreadmind")),
                "ordinary Phayriosis units must not be filtered by the ambient insect guard");
    }

    private static void verifySporeOrganoidClassification() {
        List.of("mound", "delusioner", "umarmed", "braurei", "tentacle", "arena_tendril",
                        "gastgaber", "reconstructor", "verva", "usurper", "proto", "hivetumor")
                .forEach(path -> require(SporeCompat.isTerritoryBoundOrganoidId(
                                ResourceLocation.fromNamespaceAndPath("spore", path)),
                        path + " must be covered by the broad organoid restriction"));
        require(!SporeCompat.isTerritoryBoundOrganoidId(ResourceLocation.fromNamespaceAndPath("spore", "vigil")),
                "vigils must remain exempt from the broad organoid restriction");
        require(!SporeCompat.isTerritoryBoundOrganoidId(ResourceLocation.fromNamespaceAndPath("spore", "scent")),
                "scent must remain exempt from the broad organoid restriction");
        require(!SporeCompat.isTerritoryBoundOrganoidId(ResourceLocation.fromNamespaceAndPath("spore", "inf_human")),
                "ordinary infected units must not be mistaken for organoids");
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
        require(SporeCompat.isAirCleanupBlockId(ResourceLocation.fromNamespaceAndPath("spore", "bile")),
                "generated bile must be removed as air");
        require(SporeCompat.isAirCleanupBlockId(ResourceLocation.fromNamespaceAndPath("spore", "crusted_bile")),
                "solidified bile must be removed as air");
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
