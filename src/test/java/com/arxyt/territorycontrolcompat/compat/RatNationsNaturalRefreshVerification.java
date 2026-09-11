package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratnations.api.RatNationsFactionApi;
import com.arxyt.ratnations.nation.NationCatalogManager;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import com.arxyt.territorycontrolcompat.network.CompatConfigPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/** Lightweight verification entry point for refresh rules that do not need a running game. */
public final class RatNationsNaturalRefreshVerification {
    private RatNationsNaturalRefreshVerification() {
    }

    public static void main(String[] args) {
        verifyDefaultAndLegacyConfig();
        verifyConfigNbt();
        verifyNetworkRoundTrip();
        verifyCapacityBoundaries();
        verifyOfficialRoleWeights();
        verifyNationDefaultsAndCustomNationSafety();
        System.out.println("Rat Nations natural refresh verification passed");
    }

    private static void verifyDefaultAndLegacyConfig() {
        require(!CompatSavedData.Config.DEFAULT.ratNationsNaturalRefresh(),
                "natural refresh must default to disabled");
        CompoundTag legacy = new CompoundTag();
        legacy.putBoolean("PurgePrionOnLoss", true);
        CompatSavedData.Config loaded = CompatSavedData.load(legacy).config();
        require(!loaded.ratNationsNaturalRefresh(), "legacy config must default natural refresh to disabled");
        require(loaded.purgePrionOnLoss(), "legacy config fields must remain readable");
    }

    private static void verifyConfigNbt() {
        CompatSavedData data = new CompatSavedData();
        data.setConfig(CompatSavedData.Config.DEFAULT.withRatNationsNaturalRefresh(true));
        CompatSavedData.Config loaded = CompatSavedData.load(data.save(new CompoundTag())).config();
        require(loaded.ratNationsNaturalRefresh(), "natural refresh must survive NBT save/load");
    }

    private static void verifyNetworkRoundTrip() {
        CompatSavedData.Config expected = CompatSavedData.Config.DEFAULT
                .withRatNationsNaturalRefresh(true)
                .withRestrictPrionTerrain(true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        new CompatConfigPacket(expected, true).encode(buffer);
        CompatConfigPacket decoded = CompatConfigPacket.decode(buffer);
        require(decoded.config().equals(expected), "compat config packet must round-trip all fields");
        require(decoded.open(), "compat config packet open flag must round-trip");
    }

    private static void verifyCapacityBoundaries() {
        require(RatNationsNaturalRefreshSpawner.capacity(4, 5, 30) == 0, "military capacity below five must be zero");
        require(RatNationsNaturalRefreshSpawner.capacity(5, 5, 30) == 1, "military capacity at five must be one");
        require(RatNationsNaturalRefreshSpawner.capacity(149, 5, 30) == 29, "military capacity must floor divide");
        require(RatNationsNaturalRefreshSpawner.capacity(150, 5, 30) == 30, "military capacity must cap at thirty");
        require(RatNationsNaturalRefreshSpawner.capacity(999, 5, 30) == 30, "military capacity cap must hold");
        require(RatNationsNaturalRefreshSpawner.capacity(9, 10, 10) == 0, "civilian capacity below ten must be zero");
        require(RatNationsNaturalRefreshSpawner.capacity(10, 10, 10) == 1, "civilian capacity at ten must be one");
        require(RatNationsNaturalRefreshSpawner.capacity(109, 10, 10) == 10, "civilian capacity must cap at ten");
        RandomSource random = RandomSource.create(7L);
        require(RatNationsNaturalRefreshSpawner.requestedCount(random, 0) == 0,
                "refresh request must be zero when no capacity remains");
        for (int i = 0; i < 32; i++) {
            require(RatNationsNaturalRefreshSpawner.requestedCount(random, 1) == 1,
                    "refresh request must truncate to one remaining slot");
        }

        long seed = 0L;
        int expectedMilitary;
        int expectedCivilian;
        do {
            RandomSource expectedRandom = RandomSource.create(seed++);
            expectedMilitary = expectedRandom.nextInt(2) + 1;
            expectedCivilian = expectedRandom.nextInt(2) + 1;
        } while (expectedMilitary == expectedCivilian);
        RatNationsNaturalRefreshSpawner.RefreshCounts counts =
                RatNationsNaturalRefreshSpawner.requestedCounts(RandomSource.create(seed - 1), 99, 99);
        require(counts.military() == expectedMilitary && counts.civilian() == expectedCivilian,
                "military and civilian batches must each make an independent one-to-two roll");
    }

    private static void verifyOfficialRoleWeights() {
        require(RatNationsNaturalRefreshSpawner.ROLE_WEIGHTS.size() == 5,
                "five official military roles must be configured");
        require(RatNationsNaturalRefreshSpawner.officialRoleWeightTotal() == 38,
                "official military weights must total thirty-eight");
        require(RatNationsNaturalRefreshSpawner.ROLE_WEIGHTS.stream()
                        .anyMatch(role -> role.suffix().equals("rifleman") && role.weight() == 20),
                "rifleman weight must be twenty");
        require(RatNationsNaturalRefreshSpawner.ROLE_WEIGHTS.stream()
                        .anyMatch(role -> role.suffix().equals("pistolman") && role.weight() == 10),
                "pistolman weight must be ten");
        require(RatNationsNaturalRefreshSpawner.ROLE_WEIGHTS.stream()
                        .anyMatch(role -> role.suffix().equals("assault") && role.weight() == 2),
                "assault weight must be two");
        require(RatNationsNaturalRefreshSpawner.ROLE_WEIGHTS.stream()
                        .anyMatch(role -> role.suffix().equals("machine_gunner") && role.weight() == 1),
                "machine gunner weight must be one");
        require(RatNationsNaturalRefreshSpawner.ROLE_WEIGHTS.stream()
                        .anyMatch(role -> role.suffix().equals("sniper") && role.weight() == 5),
                "sniper weight must be five");
    }

    private static void verifyNationDefaultsAndCustomNationSafety() {
        ResourceLocation expectedCivilian = ResourceLocation.fromNamespaceAndPath("rat_nations", "female_gray_dress");
        require(NationCatalogManager.find(RatNationsFactionApi.RAT_EMPIRE)
                        .map(nation -> expectedCivilian.equals(nation.defaultCivilianId())).orElse(false),
                "the built-in empire must use the official default civilian pack");
        require(RatNationsNaturalRefreshSpawner.officialPacks(
                        ResourceLocation.fromNamespaceAndPath("rat_nations", "custom_nation")).isEmpty(),
                "custom nations must not receive an invented military prototype");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
