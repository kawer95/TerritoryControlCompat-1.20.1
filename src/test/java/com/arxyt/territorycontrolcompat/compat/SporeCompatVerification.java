package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.EntityFactionProvider;
import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import com.arxyt.territorycontrolcompat.network.CompatConfigPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

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
        require(SporeCompat.isFungalLifecycleTransition(
                        ResourceLocation.fromNamespaceAndPath("spore", "bloomfung2"),
                        ResourceLocation.fromNamespaceAndPath("spore", "blomfung")),
                "touch-triggered bloomed mycelium must be allowed to transform once");
        require(!SporeCompat.isFungalLifecycleTransition(
                        ResourceLocation.fromNamespaceAndPath("minecraft", "stone"),
                        ResourceLocation.fromNamespaceAndPath("spore", "blomfung")),
                "ordinary terrain must not gain a fungal lifecycle bypass");
        require(SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("spore", "bile")),
                "casing-generated bile must be restricted and cleaned");
        require(SporeCompat.isFungalInfectionBlockId(ResourceLocation.fromNamespaceAndPath("spore", "crusted_bile")),
                "solidified bile must remain restricted and cleaned");
        verifySporeAutomaticRestorationExemptions();
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
        verifySporeCampaignPersistence();
        verifyCampaignTerrainPolicy();
        verifySporeTerrainCleanupProfile();
        verifyBuiltinCnpcFactionCatalog();
        verifyRatNationsFactionCatalog();
    }

    private static void verifyCompatConfigPacketRoundTrip() {
        CompatSavedData.Config expected = CompatSavedData.Config.DEFAULT
                .withRestrictPrionTerrain(true)
                .withPurgePrionOnLoss(true)
                .withProtectSporeFriendlyEntities(true)
                .withSporeRegularCampaigns(true)
                .withSporeGrandCampaigns(true)
                .withSporeCampaignScentReinforcements(false)
                .withSporeCampaignMoundEstablishment(false)
                .withSporeCampaignVictoryCooldownMinutes(17)
                .withSporeCampaignFailureCooldownMinutes(4)
                .withSporeGrandCampaignVictoryCooldownMinutes(31)
                .withSporeGrandCampaignFailureCooldownMinutes(16);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        new CompatConfigPacket(expected, true).encode(buffer);
        CompatConfigPacket decoded = CompatConfigPacket.decode(buffer);
        require(decoded.open() && decoded.config().equals(expected),
                "compat config network order must preserve the Prion, friendly-unit, and campaign controls");
        buffer.release();
    }

    private static void verifySporeCampaignPersistence() {
        require(CampaignTerrainResolver.twoThirds(8) == 6, "eight members require six reachable paths");
        require(CampaignTerrainResolver.twoThirds(12) == 8, "twelve members require eight reachable paths");
        require(SporeCampaignPolicy.isAvailableInfected(false, false),
                "a non-combat infected unit with a retained native search waypoint must remain campaign-eligible");
        require(!SporeCampaignPolicy.isAvailableInfected(true, false),
                "an infected unit already assigned to a campaign must remain unavailable");
        require(!SporeCampaignPolicy.isAvailableInfected(false, true),
                "an infected unit with an active combat target must remain unavailable");
        SporeCampaignSavedData.Key key = new SporeCampaignSavedData.Key("minecraft:overworld", SporeCampaignSavedData.Type.GRAND);
        UUID infectedId = UUID.randomUUID();
        UUID calamityId = UUID.randomUUID();
        SporeCampaignSavedData.Campaign campaign = new SporeCampaignSavedData.Campaign(UUID.randomUUID(), key,
                0, 0, 4, 4, new BlockPos(8, 64, 8), new BlockPos(-8, 64, -8),
                List.of(new SporeCampaignSavedData.InfectedMember(infectedId, false, null, new BlockPos(24, 64, 24))),
                List.of(new SporeCampaignSavedData.CalamityMember(calamityId, BlockPos.ZERO, new BlockPos(24, 64, 24), false, false)),
                SporeCampaignSavedData.Phase.MUSTER, 12_000L, 4_000L);
        campaign.setMoundAnchor(new BlockPos(25, 64, 25));
        campaign.setScentId(UUID.randomUUID());
        SporeCampaignSavedData.Campaign loaded = SporeCampaignSavedData.Campaign.load(campaign.save());
        require(loaded != null && loaded.type() == SporeCampaignSavedData.Type.GRAND && loaded.phase() == SporeCampaignSavedData.Phase.MUSTER,
                "Spore campaign type and phase must survive NBT round-trip");
        require(loaded.members().size() == 1 && loaded.members().get(0).id().equals(infectedId)
                        && !loaded.members().get(0).originalLinked() && loaded.members().get(0).originalSearch() == null,
                "Spore member route snapshot must preserve the original linked and search state");
        require(loaded.calamities().size() == 1 && loaded.calamities().get(0).id().equals(calamityId)
                        && loaded.moundAnchor().equals(new BlockPos(25, 64, 25)),
                "Calamity and post-capture mound state must survive NBT round-trip");
        CompatSavedData.Config legacy = CompatSavedData.load(new net.minecraft.nbt.CompoundTag()).config();
        require(!legacy.sporeRegularCampaigns() && !legacy.sporeGrandCampaigns()
                        && legacy.sporeCampaignScentReinforcements() && legacy.sporeCampaignMoundEstablishment(),
                "old worlds must leave new campaigns disabled while retaining their safe reinforcement defaults");
    }

    private static void verifyCampaignTerrainPolicy() {
        require(CampaignTerrainResolver.hasDryColumn(false, false, false, true, true),
                "solid dry ground with two air blocks must be a valid land anchor candidate");
        require(!CampaignTerrainResolver.hasDryColumn(true, false, false, true, true),
                "water surfaces must never become campaign anchors");
        require(!CampaignTerrainResolver.hasDryColumn(false, true, false, true, true),
                "lava surfaces must never become campaign anchors");
        require(!CampaignTerrainResolver.hasDryColumn(false, false, false, false, true),
                "submerged objectives must never become campaign anchors");
    }

    private static void verifySporeTerrainCleanupProfile() {
        TerritoryControlApi.TerrainCleanupProfile profile = SporeCompat.terrainCleanupProfile();
        require(profile.sourceModId().equals("spore"),
                "Spore terrain cleanup must register under the Spore faction mod id");
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
        require(PrionCompat.cleanupReplacementId(
                        ResourceLocation.fromNamespaceAndPath("prionmod", "living_block"))
                        .equals(ResourceLocation.withDefaultNamespace("cobblestone")),
                "lost Prion living block terrain must become cobblestone");
        require(PrionCompat.cleanupReplacementId(
                        ResourceLocation.fromNamespaceAndPath("prionmod", "root_block"))
                        .equals(ResourceLocation.withDefaultNamespace("cobblestone")),
                "lost Prion root block terrain must become cobblestone");
        require(PrionCompat.cleanupReplacementId(
                        ResourceLocation.fromNamespaceAndPath("prionmod", "eggs_block"))
                        .equals(ResourceLocation.withDefaultNamespace("air")),
                "temporary Prion egg blocks must remain air cleanup");
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

    /**
     * These Spore outputs either consume themselves after activation or are temporary biomass
     * used by infection structures.  CompatBlockPolicy maps every fungal classification to the
     * Territory Control protection exemption, so a removal here must never be auto-restored.
     */
    private static void verifySporeAutomaticRestorationExemptions() {
        List.of(
                        // Touch-triggered foliage and traps.
                        "biomass_lump", "biomass_bulb", "bile_lump", "fang_lump", "exploding_lump",
                        "fungal_clamp", "drowned_lump", "poisoning_lump", "acid", "tar", "remains",
                        // Biomass and dome shell variants, including the random-decaying frozen form.
                        "rooted_biomass", "biomass_block", "sicken_biomass_block",
                        "calcified_biomass_block", "gastric_biomass_block", "fungal_shell",
                        "membrane_block", "rooted_mycelium", "mycelium_block", "mycelium_slab",
                        "freeze_burned_biomass")
                .forEach(path -> require(SporeCompat.isFungalInfectionBlockId(
                                ResourceLocation.fromNamespaceAndPath("spore", path)),
                        path + " must bypass automatic restoration"));
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

    private static void verifyRatNationsFactionCatalog() {
        List<EntityFactionProvider.Option> factions = RatNationsFactionProvider.factionCatalog();
        require(factions.size() == 2, "Rat Nations must expose exactly two configured factions");
        require(factions.stream().anyMatch(option -> option.key().equals("rat_nations:rat_federation")
                        && option.color() == 0x55AA55),
                "Rat Federation must retain its stable key and green color");
        require(factions.stream().anyMatch(option -> option.key().equals("rat_nations:rat_empire")
                        && option.color() == 0xAA3333),
                "Rat Empire must retain its stable key and red color");
        RatNationsFactionProvider provider = new RatNationsFactionProvider();
        require(!provider.supports(null), "null must not be claimed by the Rat Nations provider");
        require(provider.resolve(null).kind() == EntityFactionProvider.ResolutionKind.NOT_APPLICABLE,
                "unsupported entities must remain not applicable");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
