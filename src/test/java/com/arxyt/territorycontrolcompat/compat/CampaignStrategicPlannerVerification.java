package com.arxyt.territorycontrolcompat.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Dependency-free contract checks for rear-area mobilization and staging selection. */
public final class CampaignStrategicPlannerVerification {
    private CampaignStrategicPlannerVerification() { }

    public static void main(String[] args) {
        friendlyChunkInsideTargetWarzoneIsNotARally();
        fiveNeutralChunksAreAccepted();
        friendlyChunkTwoChunksFromTargetIsAccepted();
        allNearbyOwnedRalliesAreRetained();
        unsafeRallyChunkIsRejected();
        rearTerritoryStateDoesNotBlockMobilization();
        unitsAlreadyAtTargetCount();
        hostileStagingAndUnsafeRearAreRejected();
        deterministicSelection();
        largeSnapshotRemainsBounded();
        System.out.println("Campaign strategic planner verification passed");
    }

    private static void friendlyChunkInsideTargetWarzoneIsNotARally() {
        Scenario scenario = scenario(25, false, false);
        List<CampaignStrategicPlanner.TerritoryCell> territories = new ArrayList<>(scenario.request().territories());
        territories.add(new CampaignStrategicPlanner.TerritoryCell(6, 0, true, false, false));
        Map<Long, CampaignStrategicPlanner.TerrainChunk> terrain = new HashMap<>(scenario.request().terrain());
        terrain.put(CampaignStrategicPlanner.chunkKey(6, 0), terrain(6, 0));
        CampaignStrategicPlanner.Request request = withTerritoriesAndTerrain(scenario.request(), territories, terrain, Set.of());
        CampaignStrategicPlanner.Result result = CampaignStrategicPlanner.plan(request);
        require(result.success(), "an external friendly chunk within two chunks of the target must produce an option");
        require(result.options().stream().noneMatch(option -> option.candidate().stagingChunk().equals(
                        new CampaignStrategicPlanner.ChunkKey(6, 0))),
                "a friendly-owned chunk inside the target warzone must never be used as a rally chunk");
        require(result.options().get(0).candidate().tier() >= 1 && result.options().get(0).candidate().tier() <= 2,
                "rally chunks must be one or two chunks outside the target edge");
        require(result.options().get(0).memberIds().size() == 8, "rat squad must respect maximum size 8");
    }

    private static void fiveNeutralChunksAreAccepted() {
        Scenario scenario = scenario(20, false, false);
        CampaignStrategicPlanner.Result result = CampaignStrategicPlanner.plan(scenario.request());
        require(result.success(), "a friendly rally chunk must be accepted even when its surrounding warzone is incomplete");
        require(result.options().get(0).candidate().tier() == 2,
                "a friendly chunk two chunks outside the target must use tier 2");
    }

    private static void friendlyChunkTwoChunksFromTargetIsAccepted() {
        Scenario scenario = scenario(0, false, false);
        List<CampaignStrategicPlanner.TerritoryCell> territories = new ArrayList<>(scenario.request().territories());
        territories.add(new CampaignStrategicPlanner.TerritoryCell(3, 0, true, false, false));
        Map<Long, CampaignStrategicPlanner.TerrainChunk> terrain = new HashMap<>(scenario.request().terrain());
        terrain.put(CampaignStrategicPlanner.chunkKey(3, 0), terrain(3, 0));
        CampaignStrategicPlanner.Result result = CampaignStrategicPlanner.plan(
                withTerritoriesAndTerrain(scenario.request(), territories, terrain, Set.of()));
        require(result.success(), "a friendly rally chunk two chunks from the hostile target must be accepted");
        require(result.options().get(0).candidate().tier() == 2,
                "a rally chunk two chunks outside the target must use tier 2");
        require(result.options().get(0).candidate().stagingChunk().equals(new CampaignStrategicPlanner.ChunkKey(3, 0)),
                "the two-chunk rally candidate must be selected");
    }

    private static void allNearbyOwnedRalliesAreRetained() {
        CampaignStrategicPlanner.Result result = CampaignStrategicPlanner.plan(scenario(25, false, false).request());
        require(result.success() && result.options().size() > 4,
                "all friendly or allied chunks in the two-chunk target perimeter must remain candidates");
        require(result.options().stream().allMatch(option -> option.candidate().tier() == 1
                        || option.candidate().tier() == 2),
                "every rally candidate must be one or two chunks outside the target edge");
    }

    private static void unsafeRallyChunkIsRejected() {
        Scenario scenario = scenario(0, false, false);
        List<CampaignStrategicPlanner.TerritoryCell> territories = new ArrayList<>(scenario.request().territories());
        territories.add(new CampaignStrategicPlanner.TerritoryCell(3, 0, true, false, false));
        Map<Long, CampaignStrategicPlanner.TerrainChunk> terrain = new HashMap<>(scenario.request().terrain());
        terrain.put(CampaignStrategicPlanner.chunkKey(3, 0), terrain(3, 0));
        CampaignStrategicPlanner.Result result = CampaignStrategicPlanner.plan(withTerritoriesAndTerrain(
                scenario.request(), territories, terrain, Set.of(CampaignStrategicPlanner.chunkKey(3, 0))));
        require(!result.success() && "NO_SELECTABLE_TARGET".equals(result.reason()),
                "a 32-block safety-blocked rally chunk must not produce a campaign option");
        require(result.diagnostics().stagingUnsafePresence() > 0,
                "rally diagnostics must expose the safety-radius rejection");
    }

    private static void rearTerritoryStateDoesNotBlockMobilization() {
        Scenario scenario = scenario(25, false, false);
        List<CampaignStrategicPlanner.TerritoryCell> territories = new ArrayList<>();
        int neutralized = 0;
        for (CampaignStrategicPlanner.TerritoryCell cell : scenario.request().territories()) {
            if (cell.chunkX() < -5 && neutralized++ < 6) {
                territories.add(new CampaignStrategicPlanner.TerritoryCell(cell.chunkX(), cell.chunkZ(), false, false, true));
            } else {
                territories.add(cell);
            }
        }
        CampaignStrategicPlanner.Result result = CampaignStrategicPlanner.plan(withTerritoriesAndTerrain(
                scenario.request(), territories, scenario.request().terrain(), Set.of()));
        require(result.success(),
                "neutral, hostile or contested rear territory must not block mobilization when no enemy unit is present");
        require(result.diagnostics().unitUnsafePresence() == 0,
                "only an enemy unit physically present in the source warzone may reject a rear unit");
    }

    private static void unitsAlreadyAtTargetCount() {
        Scenario scenario = scenario(25, false, false);
        UUID targetUnitId = new UUID(42L, 42L);
        List<CampaignStrategicPlanner.Unit> units = new ArrayList<>(scenario.request().units());
        units.add(new CampaignStrategicPlanner.Unit(targetUnitId, 80, 64, 20, 0.6F, 1.8F, true, true));
        CampaignStrategicPlanner.Request request = new CampaignStrategicPlanner.Request(
                scenario.request().kind(), scenario.request().warzoneSize(), scenario.request().minimumUnits(),
                scenario.request().maximumUnits(), scenario.request().requiredCalamities(), scenario.request().territories(),
                scenario.request().unsafePresenceZones(), units, scenario.request().calamities(), scenario.request().terrain());
        CampaignStrategicPlanner.Result result = CampaignStrategicPlanner.plan(request);
        require(result.success(), "a unit already in the hostile target warzone must count toward the squad");
        require(result.options().get(0).memberIds().contains(targetUnitId),
                "an in-place target unit must be retained as a campaign member");
        require(result.diagnostics().unitInPlace() > 0,
                "planner diagnostics must expose in-place target members");
    }

    private static void hostileStagingAndUnsafeRearAreRejected() {
        CampaignStrategicPlanner.Result hostile = CampaignStrategicPlanner.plan(scenario(1, false, false).request());
        require(!hostile.success(),
                "a friendly warzone without a rally chunk near its hostile neighbour must reject that target");
        require(hostile.diagnostics().stagingNoFriendlyTerritory() > 0,
                "staging diagnostics must identify the absence of a friendly rally chunk");
        CampaignStrategicPlanner.Result unsafeRear = CampaignStrategicPlanner.plan(scenario(25, false, true).request());
        require(!unsafeRear.success(),
                "a rear warzone with hostile presence must not supply units");
        require(unsafeRear.diagnostics().unitUnsafePresence() > 0,
                "rear diagnostics must identify hostile presence");
    }

    private static void deterministicSelection() {
        CampaignStrategicPlanner.Request request = scenario(25, false, false).request();
        CampaignStrategicPlanner.Result first = CampaignStrategicPlanner.plan(request);
        CampaignStrategicPlanner.Result second = CampaignStrategicPlanner.plan(request);
        require(first.equals(second), "identical snapshots must produce identical strategic results");
    }

    private static void largeSnapshotRemainsBounded() {
        List<CampaignStrategicPlanner.TerritoryCell> territories = new ArrayList<>();
        for (int index = 0; index < 2_138; index++) {
            territories.add(new CampaignStrategicPlanner.TerritoryCell(index % 64, index / 64, true, false, false));
        }
        List<CampaignStrategicPlanner.Unit> units = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            units.add(new CampaignStrategicPlanner.Unit(new UUID(1L, index), index % 256, 64,
                    index / 256, 0.6F, 1.8F));
        }
        long started = System.nanoTime();
        CampaignStrategicPlanner.Result result = CampaignStrategicPlanner.plan(new CampaignStrategicPlanner.Request(
                CampaignStrategicPlanner.Kind.RAT, 5, 4, 8, 0, territories, Set.of(), units, List.of(), Map.of()));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        require(!result.success() && elapsedMillis < 250L,
                "2138 territories and 1000 units must remain bounded on a background worker; elapsed=" + elapsedMillis + "ms");
    }

    private static Scenario scenario(int stagingFriendlyChunks, boolean hostileStaging, boolean unsafeRear) {
        int size = 5;
        List<CampaignStrategicPlanner.TerritoryCell> territories = new ArrayList<>();
        Map<Long, CampaignStrategicPlanner.TerrainChunk> terrain = new HashMap<>();
        int added = 0;
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) {
            if (added++ < stagingFriendlyChunks) territories.add(new CampaignStrategicPlanner.TerritoryCell(x, z, true, false, false));
            terrain.put(CampaignStrategicPlanner.chunkKey(x, z), terrain(x, z));
        }
        if (hostileStaging) territories.set(0, new CampaignStrategicPlanner.TerritoryCell(0, 0, false, false, true));
        territories.add(new CampaignStrategicPlanner.TerritoryCell(5, 0, false, true, false));
        terrain.put(CampaignStrategicPlanner.chunkKey(5, 0), terrain(5, 0));
        for (int x = -10; x < -5; x++) for (int z = 0; z < 5; z++) {
            territories.add(new CampaignStrategicPlanner.TerritoryCell(x, z, true, false, false));
            terrain.put(CampaignStrategicPlanner.chunkKey(x, z), terrain(x, z));
        }
        List<CampaignStrategicPlanner.Unit> units = new ArrayList<>();
        for (int index = 0; index < 10; index++) units.add(new CampaignStrategicPlanner.Unit(
                new UUID(0L, index + 1L), -150 + index, 64, 20, 0.6F, 1.8F));
        Set<CampaignStrategicPlanner.ZoneKey> unsafe = unsafeRear
                ? Set.of(new CampaignStrategicPlanner.ZoneKey(-2, 0)) : Set.of();
        return new Scenario(new CampaignStrategicPlanner.Request(CampaignStrategicPlanner.Kind.RAT, size, 4, 8, 0,
                territories, unsafe, units, List.of(), terrain));
    }

    private static CampaignStrategicPlanner.TerrainChunk terrain(int chunkX, int chunkZ) {
        short[] heights = new short[256];
        byte[] clearance = new byte[256];
        java.util.Arrays.fill(heights, (short) 64);
        java.util.Arrays.fill(clearance, (byte) 4);
        return new CampaignStrategicPlanner.TerrainChunk(chunkX, chunkZ, 1L, heights, clearance);
    }

    private static CampaignStrategicPlanner.Request withTerritoriesAndTerrain(
            CampaignStrategicPlanner.Request base,
            List<CampaignStrategicPlanner.TerritoryCell> territories,
            Map<Long, CampaignStrategicPlanner.TerrainChunk> terrain,
            Set<Long> unsafeRallyChunks) {
        return new CampaignStrategicPlanner.Request(base.kind(), base.warzoneSize(), base.minimumUnits(),
                base.maximumUnits(), base.requiredCalamities(), territories, base.unsafePresenceZones(), base.units(),
                base.calamities(), terrain, unsafeRallyChunks, base.cooldownAudit(), base.unitStats(), base.calamityStats());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Scenario(CampaignStrategicPlanner.Request request) { }
}
