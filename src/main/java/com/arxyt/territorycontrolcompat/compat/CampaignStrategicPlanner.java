package com.arxyt.territorycontrolcompat.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure-data selection of a hostile warzone target, one nearby friendly-owned rally chunk and a rear-area squad. */
final class CampaignStrategicPlanner {
    private static final int RALLY_DISTANCE_CHUNKS = 2;
    private static final int[] ANCHOR_OFFSETS = {2, 6, 10, 14};

    private CampaignStrategicPlanner() { }

    static Result plan(Request request) {
        Map<Long, TerritoryCell> territories = new HashMap<>();
        request.territories().forEach(cell -> territories.put(chunkKey(cell.chunkX(), cell.chunkZ()), cell));
        Set<ZoneKey> hostileZones = frontierTargets(request.territories(), request.warzoneSize());
        DiagnosticsBuilder diagnostics = new DiagnosticsBuilder(request, hostileZones.size());
        List<Candidate> candidates = new ArrayList<>();
        int rejected = 0;
        for (ZoneKey target : hostileZones) {
            Point targetAnchor = anchor(request.terrain(), territories, target, request.warzoneSize(), null, false);
            if (targetAnchor == null) {
                diagnostics.targetAnchorMissing++;
                diagnostics.addTargetEvidence(targetEvidence(request, territories, target, "TARGET_ANCHOR_MISSING"));
                rejected++;
                continue;
            }
            List<ChunkKey> rallies = rallyChunks(request, territories, target, targetAnchor, diagnostics);
            if (rallies.isEmpty()) {
                diagnostics.addTargetEvidence(targetEvidence(request, territories, target, "NO_SAFE_FRIENDLY_RALLY"));
                rejected++;
                continue;
            }
            for (ChunkKey rally : rallies) {
                rejected += addCandidate(request, target, rally, targetAnchor,
                        chunkDistanceToTarget(target, rally, request.warzoneSize()), candidates, diagnostics);
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::tier)
                .thenComparingDouble(candidate -> nearestUnitDistance(request.units(), candidate.stagingAnchor()))
                .thenComparing(candidate -> candidate.target().x()).thenComparing(candidate -> candidate.target().z()));
        List<Option> options = new ArrayList<>();
        for (Candidate candidate : candidates) {
            List<Unit> eligible = eligibleUnits(request, territories, hostileZones, candidate, false, diagnostics);
            if (eligible.size() < request.minimumUnits()) {
                diagnostics.squadRejectedMinimum++;
                rejected++;
                continue;
            }
            diagnostics.squadCandidatesPassed++;
            List<Unit> calamities = request.requiredCalamities() == 0 ? List.of()
                    : eligibleUnits(request, territories, hostileZones, candidate, true, diagnostics);
            if (calamities.size() < request.requiredCalamities()) {
                diagnostics.calamityRejectedMinimum++;
                rejected++;
                continue;
            }
            diagnostics.calamityCandidatesPassed++;
            options.add(Option.of(candidate, eligible, calamities));
            diagnostics.options++;
        }
        Diagnostics resultDiagnostics = diagnostics.build();
        if (!options.isEmpty()) return Result.success(rejected, options, resultDiagnostics);
        String reason;
        if (hostileZones.isEmpty()) reason = "NO_FRONTIER";
        else if (diagnostics.candidateAccepted == 0 && diagnostics.targetAnchorMissing == hostileZones.size()) {
            reason = "NO_TARGET_TERRAIN_ANCHOR";
        } else if (diagnostics.candidateAccepted == 0) {
            reason = "NO_SELECTABLE_TARGET";
        }
        else if (diagnostics.squadCandidatesPassed == 0) reason = "NO_REAR_SQUAD";
        else if (request.requiredCalamities() > 0 && diagnostics.calamityCandidatesPassed == 0) reason = "NO_CALAMITY_SQUAD";
        else reason = "NO_REAR_SQUAD";
        return Result.failure(reason, rejected, resultDiagnostics);
    }

    private static int addCandidate(Request request, ZoneKey target, ChunkKey stagingChunk, Point targetAnchor,
                                    int tier, List<Candidate> output, DiagnosticsBuilder diagnostics) {
        diagnostics.candidateChecks++;
        Point stagingAnchor = anchorChunk(request.terrain(), stagingChunk, targetAnchor);
        if (stagingAnchor == null) diagnostics.stagingAnchorMissing++;
        if (stagingAnchor == null) return 1;
        output.add(new Candidate(target, zone(stagingChunk.x(), stagingChunk.z(), request.warzoneSize()),
                stagingChunk, tier, stagingAnchor, targetAnchor));
        diagnostics.candidateAccepted++;
        return 0;
    }

    private static List<ChunkKey> rallyChunks(Request request, Map<Long, TerritoryCell> territories, ZoneKey target,
                                               Point targetAnchor, DiagnosticsBuilder diagnostics) {
        int size = request.warzoneSize();
        int minX = target.x() * size - RALLY_DISTANCE_CHUNKS;
        int maxX = target.x() * size + size - 1 + RALLY_DISTANCE_CHUNKS;
        int minZ = target.z() * size - RALLY_DISTANCE_CHUNKS;
        int maxZ = target.z() * size + size - 1 + RALLY_DISTANCE_CHUNKS;
        List<ChunkKey> result = new ArrayList<>();
        boolean friendlyFound = false;
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                TerritoryCell cell = territories.get(chunkKey(chunkX, chunkZ));
                if (cell == null || !cell.stableFriendly()) continue;
                if (chunkDistanceToTarget(target, new ChunkKey(chunkX, chunkZ), size) == 0) continue;
                friendlyFound = true;
                long key = chunkKey(chunkX, chunkZ);
                if (request.unsafeRallyChunks().contains(key)) {
                    diagnostics.stagingUnsafePresence++;
                    continue;
                }
                result.add(new ChunkKey(chunkX, chunkZ));
            }
        }
        if (!friendlyFound) diagnostics.stagingNoFriendlyTerritory++;
        result.sort(Comparator.comparingInt((ChunkKey chunk) -> chunkDistanceToTarget(target, chunk, size))
                .thenComparingDouble(chunk -> distance((chunk.x() << 4) + 8, (chunk.z() << 4) + 8, targetAnchor))
                .thenComparing(ChunkKey::x).thenComparing(ChunkKey::z));
        return List.copyOf(result);
    }

    /** Bounded coordinate evidence for one failed target; emitted only in the final failure record. */
    private static String targetEvidence(Request request, Map<Long, TerritoryCell> territories, ZoneKey target,
                                         String outcome) {
        int size = request.warzoneSize();
        int minX = target.x() * size - RALLY_DISTANCE_CHUNKS;
        int maxX = target.x() * size + size - 1 + RALLY_DISTANCE_CHUNKS;
        int minZ = target.z() * size - RALLY_DISTANCE_CHUNKS;
        int maxZ = target.z() * size + size - 1 + RALLY_DISTANCE_CHUNKS;
        int friendly = 0, safetyBlocked = 0;
        List<String> safe = new ArrayList<>();
        for (int chunkX = minX; chunkX <= maxX; chunkX++) for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
            TerritoryCell cell = territories.get(chunkKey(chunkX, chunkZ));
            if (cell == null || !cell.stableFriendly()) continue;
            if (chunkDistanceToTarget(target, new ChunkKey(chunkX, chunkZ), size) == 0) continue;
            friendly++;
            if (request.unsafeRallyChunks().contains(chunkKey(chunkX, chunkZ))) {
                safetyBlocked++;
            } else if (safe.size() < 6) {
                safe.add(chunkX + "," + chunkZ);
            }
        }
        return "targetChunks=" + (target.x() * size) + ".." + (target.x() * size + size - 1) + ","
                + (target.z() * size) + ".." + (target.z() * size + size - 1)
                + ",outcome=" + outcome + ",rallyFriendly=" + friendly
                + ",rallySafetyBlocked=" + safetyBlocked + ",rallySafeChunks=" + safe;
    }

    static Set<Long> potentialRallyChunks(List<TerritoryCell> cells, int size) {
        Map<Long, TerritoryCell> territories = new HashMap<>();
        for (TerritoryCell cell : cells) {
            territories.put(chunkKey(cell.chunkX(), cell.chunkZ()), cell);
        }
        Set<Long> result = new HashSet<>();
        for (ZoneKey target : frontierTargets(cells, size)) {
            int minX = target.x() * size - RALLY_DISTANCE_CHUNKS;
            int maxX = target.x() * size + size - 1 + RALLY_DISTANCE_CHUNKS;
            int minZ = target.z() * size - RALLY_DISTANCE_CHUNKS;
            int maxZ = target.z() * size + size - 1 + RALLY_DISTANCE_CHUNKS;
            for (int chunkX = minX; chunkX <= maxX; chunkX++) for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                TerritoryCell cell = territories.get(chunkKey(chunkX, chunkZ));
                if (cell != null && cell.stableFriendly()
                        && chunkDistanceToTarget(target, new ChunkKey(chunkX, chunkZ), size) > 0) {
                    result.add(chunkKey(chunkX, chunkZ));
                }
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Frontline discovery is intentionally friendly-first: inspect every friendly warzone and
     * only target a cardinally adjoining hostile/contested warzone. Remote hostile warzones are
     * never planning targets merely because their territory data is visible.
     */
    static Set<ZoneKey> frontierTargets(List<TerritoryCell> cells, int size) {
        Map<Long, TerritoryCell> territories = new HashMap<>();
        Set<ZoneKey> friendlyZones = new LinkedHashSet<>();
        for (TerritoryCell cell : cells) {
            territories.put(chunkKey(cell.chunkX(), cell.chunkZ()), cell);
            if (cell.stableFriendly()) friendlyZones.add(zone(cell.chunkX(), cell.chunkZ(), size));
        }
        Set<ZoneKey> targets = new LinkedHashSet<>();
        for (ZoneKey friendly : friendlyZones) {
            for (int direction = 0; direction < 4; direction++) {
                ZoneKey neighbour = new ZoneKey(friendly.x() + new int[]{1, -1, 0, 0}[direction],
                        friendly.z() + new int[]{0, 0, 1, -1}[direction]);
                if (zoneContainsHostile(territories, neighbour, size)) targets.add(neighbour);
            }
        }
        return Set.copyOf(targets);
    }

    static Set<Long> frontierTerrainChunks(List<TerritoryCell> cells, int size) {
        Set<ZoneKey> targets = frontierTargets(cells, size);
        Set<Long> result = new HashSet<>();
        for (TerritoryCell cell : cells) {
            if (cell.hostile() && targets.contains(zone(cell.chunkX(), cell.chunkZ(), size))) {
                result.add(chunkKey(cell.chunkX(), cell.chunkZ()));
            }
        }
        result.addAll(potentialRallyChunks(cells, size));
        return Set.copyOf(result);
    }

    private static boolean zoneContainsHostile(Map<Long, TerritoryCell> territories, ZoneKey zone, int size) {
        for (int x = zone.x() * size; x < zone.x() * size + size; x++) for (int z = zone.z() * size; z < zone.z() * size + size; z++) {
            TerritoryCell cell = territories.get(chunkKey(x, z));
            if (cell != null && cell.hostile()) return true;
        }
        return false;
    }

    private static int chunkDistanceToTarget(ZoneKey target, ChunkKey chunk, int size) {
        int minX = target.x() * size, maxX = minX + size - 1;
        int minZ = target.z() * size, maxZ = minZ + size - 1;
        int dx = chunk.x() < minX ? minX - chunk.x() : Math.max(0, chunk.x() - maxX);
        int dz = chunk.z() < minZ ? minZ - chunk.z() : Math.max(0, chunk.z() - maxZ);
        return Math.max(dx, dz);
    }

    private static List<Unit> eligibleUnits(Request request, Map<Long, TerritoryCell> territories,
                                            Set<ZoneKey> hostileZones, Candidate candidate, boolean calamity,
                                            DiagnosticsBuilder diagnostics) {
        List<Unit> eligible = new ArrayList<>();
        List<Unit> source = calamity ? request.calamities() : request.units();
        for (Unit unit : source) {
            RearReason reason = rearReason(request, territories, hostileZones, unit, candidate);
            diagnostics.recordRear(reason, calamity);
            if (reason == RearReason.OK || reason == RearReason.IN_PLACE) eligible.add(unit);
        }
        eligible.sort(Comparator.comparingDouble(unit -> distance(unit.x(), unit.z(), candidate.stagingAnchor())));
        int limit = calamity ? request.requiredCalamities() : request.maximumUnits();
        return eligible.stream().limit(Math.max(0, limit)).toList();
    }

    private static RearReason rearReason(Request request, Map<Long, TerritoryCell> territories,
                                         Set<ZoneKey> hostileZones, Unit unit, Candidate candidate) {
        ZoneKey source = zone(unit.x() >> 4, unit.z() >> 4, request.warzoneSize());
        if (source.equals(candidate.target())
                || new ChunkKey(unit.x() >> 4, unit.z() >> 4).equals(candidate.stagingChunk())) return RearReason.IN_PLACE;
        if (unit.blockingTarget()) return RearReason.BLOCKING_TARGET;
        if (unit.recentCombat()) return RearReason.RECENT_COMBAT;
        // A rear source is unsafe only when enemy faction units are physically present now.
        // Ownership, contest progress, neutral chunks and front-line adjacency never block mobilization.
        return request.unsafePresenceZones().contains(source) ? RearReason.UNSAFE_PRESENCE : RearReason.OK;
    }

    private static Point anchor(Map<Long, TerrainChunk> terrain, Map<Long, TerritoryCell> territories,
                                ZoneKey zone, int size, Point preferred, boolean friendly) {
        List<Point> candidates = new ArrayList<>();
        for (int chunkX = zone.x() * size; chunkX < zone.x() * size + size; chunkX++) {
            for (int chunkZ = zone.z() * size; chunkZ < zone.z() * size + size; chunkZ++) {
                TerritoryCell cell = territories.get(chunkKey(chunkX, chunkZ));
                if (cell == null || (friendly ? !cell.stableFriendly() : !cell.hostile())) continue;
                TerrainChunk chunk = terrain.get(chunkKey(chunkX, chunkZ));
                if (chunk == null) continue;
                for (int x : ANCHOR_OFFSETS) for (int z : ANCHOR_OFFSETS) {
                    int y = chunk.height(x, z);
                    if (y != Integer.MIN_VALUE && chunk.clearance(x, z) >= 2) {
                        candidates.add(new Point((chunkX << 4) + x, y, (chunkZ << 4) + z));
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        if (preferred == null) return candidates.get(0);
        return candidates.stream().min(Comparator.comparingDouble(point -> distance(point.x(), point.z(), preferred))).orElse(null);
    }

    private static Point anchorChunk(Map<Long, TerrainChunk> terrain, ChunkKey rallyChunk, Point preferred) {
        TerrainChunk chunk = terrain.get(chunkKey(rallyChunk.x(), rallyChunk.z()));
        if (chunk == null) return null;
        List<Point> candidates = new ArrayList<>();
        for (int x : ANCHOR_OFFSETS) for (int z : ANCHOR_OFFSETS) {
            int y = chunk.height(x, z);
            if (y != Integer.MIN_VALUE && chunk.clearance(x, z) >= 2) {
                candidates.add(new Point((rallyChunk.x() << 4) + x, y, (rallyChunk.z() << 4) + z));
            }
        }
        if (candidates.isEmpty()) return null;
        if (preferred == null) return candidates.get(0);
        return candidates.stream().min(Comparator.comparingDouble(point -> distance(point.x(), point.z(), preferred))).orElse(null);
    }

    private static double nearestUnitDistance(List<Unit> units, Point point) {
        return units.stream().mapToDouble(unit -> distance(unit.x(), unit.z(), point)).min().orElse(Double.MAX_VALUE);
    }

    private static double distance(int x, int z, Point point) {
        double dx = x - point.x();
        double dz = z - point.z();
        return dx * dx + dz * dz;
    }

    static ZoneKey zone(int chunkX, int chunkZ, int size) { return new ZoneKey(Math.floorDiv(chunkX, size), Math.floorDiv(chunkZ, size)); }
    static long chunkKey(int x, int z) { return ((long) x & 0xffffffffL) | (((long) z & 0xffffffffL) << 32); }
    static boolean inPlace(Candidate candidate, int size, int chunkX, int chunkZ) {
        return zone(chunkX, chunkZ, size).equals(candidate.target())
                || new ChunkKey(chunkX, chunkZ).equals(candidate.stagingChunk());
    }

    enum Kind { RAT, SPORE_REGULAR, SPORE_GRAND }
    record ZoneKey(int x, int z) { }
    record ChunkKey(int x, int z) { }
    record TerritoryCell(int chunkX, int chunkZ, boolean stableFriendly, boolean hostile, boolean neutral) { }
    record Unit(UUID id, int x, int y, int z, float width, float height,
                boolean blockingTarget, boolean recentCombat) {
        Unit(UUID id, int x, int y, int z, float width, float height) {
            this(id, x, y, z, width, height, false, false);
        }
    }
    record Point(int x, int y, int z) { }
    record TerrainChunk(int chunkX, int chunkZ, long revision, short[] heights, byte[] clearance) {
        TerrainChunk {
            heights = heights.clone();
            clearance = clearance.clone();
            if (heights.length != 256 || clearance.length != 256) throw new IllegalArgumentException("terrain arrays must contain 256 columns");
        }
        int height(int x, int z) { short value = heights[(z & 15) * 16 + (x & 15)]; return value == Short.MIN_VALUE ? Integer.MIN_VALUE : value; }
        int clearance(int x, int z) { return Byte.toUnsignedInt(clearance[(z & 15) * 16 + (x & 15)]); }
    }
    record Request(Kind kind, int warzoneSize, int minimumUnits, int maximumUnits, int requiredCalamities,
                   List<TerritoryCell> territories, Set<ZoneKey> unsafePresenceZones, List<Unit> units,
                   List<Unit> calamities, Map<Long, TerrainChunk> terrain,
                   Set<Long> unsafeRallyChunks,
                   String cooldownAudit,
                   SourceStats unitStats, SourceStats calamityStats) {
        Request(Kind kind, int warzoneSize, int minimumUnits, int maximumUnits, int requiredCalamities,
                List<TerritoryCell> territories, Set<ZoneKey> unsafePresenceZones, List<Unit> units,
                List<Unit> calamities, Map<Long, TerrainChunk> terrain) {
            this(kind, warzoneSize, minimumUnits, maximumUnits, requiredCalamities, territories,
                    unsafePresenceZones, units, calamities, terrain, Set.of(), "", SourceStats.empty(), SourceStats.empty());
        }

        Request {
            territories = List.copyOf(territories);
            unsafePresenceZones = Set.copyOf(unsafePresenceZones);
            units = List.copyOf(units);
            calamities = List.copyOf(calamities);
            terrain = terrain == null ? Map.of() : Map.copyOf(terrain);
            unsafeRallyChunks = Set.copyOf(unsafeRallyChunks == null ? Set.of() : unsafeRallyChunks);
            cooldownAudit = cooldownAudit == null ? "" : cooldownAudit;
            unitStats = unitStats == null ? SourceStats.empty() : unitStats;
            calamityStats = calamityStats == null ? SourceStats.empty() : calamityStats;
        }
    }
    record Candidate(ZoneKey target, ZoneKey staging, ChunkKey stagingChunk, int tier,
                     Point stagingAnchor, Point targetAnchor) { }
    record Option(Candidate candidate, List<UUID> memberIds, List<UUID> calamityIds,
                  float probeWidth, float probeHeight) {
        static Option of(Candidate candidate, List<Unit> units, List<Unit> calamities) {
            float width = 0.6F, height = 1.8F;
            for (Unit unit : units) { width = Math.max(width, unit.width()); height = Math.max(height, unit.height()); }
            for (Unit unit : calamities) { width = Math.max(width, unit.width()); height = Math.max(height, unit.height()); }
            return new Option(candidate, units.stream().map(Unit::id).toList(), calamities.stream().map(Unit::id).toList(), width, height);
        }
    }
    record Result(boolean success, String reason, int rejectedCandidates, List<Option> options, Diagnostics diagnostics) {
        static Result failure(String reason, int rejected) { return failure(reason, rejected, Diagnostics.empty()); }
        static Result failure(String reason, int rejected, Diagnostics diagnostics) {
            return new Result(false, reason, rejected, List.of(), diagnostics);
        }
        static Result success(int rejected, List<Option> options) { return success(rejected, options, Diagnostics.empty()); }
        static Result success(int rejected, List<Option> options, Diagnostics diagnostics) {
            return new Result(true, "SELECTED", rejected, List.copyOf(options), diagnostics);
        }
    }
    record SourceStats(int scanned, int wrongFaction, int deadOrRemoved, int assigned, int targeted,
                       int unalignedTargetIgnored, int combatCooldown, int reserved, int deployment,
                       int outsideFriendly, int eligible, int combatCooldownPlayer, int combatCooldownHostileFaction) {
        static SourceStats empty() { return new SourceStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }

        String compact() {
            return "scan=" + scanned + ",snapshotCandidates=" + eligible + ",wrongFaction=" + wrongFaction
                    + ",dead=" + deadOrRemoved + ",assigned=" + assigned + ",target=" + targeted
                    + ",unalignedTargetIgnored=" + unalignedTargetIgnored + ",combatCooldown=" + combatCooldown
                    + ",combatPlayer=" + combatCooldownPlayer + ",combatHostileFaction=" + combatCooldownHostileFaction
                    + ",reserved=" + reserved + ",deployment=" + deployment
                    + ",outsideFriendlyObserved=" + outsideFriendly;
        }
    }

    record Diagnostics(int territoryCells, int hostileZones, int units, int calamities,
                       int candidateChecks, int candidateAccepted,
                       int stagingNoFriendlyTerritory, int stagingUnsafePresence,
                       int duplicateStaging, int stagingAnchorMissing, int targetAnchorMissing,
                       int unitChecks, int unitEligible, int unitInPlace, int unitBlockingTarget, int unitRecentCombat,
                       int unitUnsafePresence,
                       int squadRejectedMinimum, int squadCandidatesPassed,
                       int calamityChecks, int calamityEligible, int calamityInPlace, int calamityBlockingTarget, int calamityRecentCombat,
                       int calamityUnsafePresence,
                       int calamityRejectedMinimum, int calamityCandidatesPassed, int options,
                       String targetEvidence,
                       String cooldownAudit,
                       SourceStats unitStats, SourceStats calamityStats) {
        static Diagnostics empty() {
            return new Diagnostics(
                    0, 0, 0, 0,
                    0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0,
                    0, 0,
                    0, 0, 0, 0, 0, 0,
                    0, 0, 0,
                    "",
                    "",
                    SourceStats.empty(), SourceStats.empty());
        }

        /** Compact aggregate fields for the independent failure log; never contains coordinates or UUIDs. */
        String compact() {
            return "territories=" + territoryCells + ",hostileZones=" + hostileZones
                    + ",snapshotUnits=" + units + ",snapshotCalamities=" + calamities
                    + ",candidateChecks=" + candidateChecks + ",candidateAccepted=" + candidateAccepted
                    + ",stageNoFriendly=" + stagingNoFriendlyTerritory
                    + ",stageSafetyBlocked=" + stagingUnsafePresence
                    + ",stageDuplicate=" + duplicateStaging + ",stageAnchorMissing=" + stagingAnchorMissing
                    + ",targetAnchorMissing=" + targetAnchorMissing
                    + ",candidateUnitChecks=" + unitChecks + ",candidateUnitEligible=" + unitEligible
                    + ",candidateUnitInPlace=" + unitInPlace
                    + ",candidateUnitTarget=" + unitBlockingTarget + ",candidateUnitCombat=" + unitRecentCombat
                    + ",candidateUnitEnemyPresence=" + unitUnsafePresence
                    + ",squadBelowMinimum=" + squadRejectedMinimum + ",squadPassed=" + squadCandidatesPassed
                    + ",calamityChecks=" + calamityChecks + ",calamityEligible=" + calamityEligible
                    + ",calamityInPlace=" + calamityInPlace
                    + ",calamityTarget=" + calamityBlockingTarget + ",calamityCombat=" + calamityRecentCombat
                    + ",calamityEnemyPresence=" + calamityUnsafePresence
                    + ",calamityBelowMinimum=" + calamityRejectedMinimum + ",calamityPassed=" + calamityCandidatesPassed
                    + ",options=" + options
                    + (targetEvidence.isBlank() ? "" : ",targetEvidence{" + targetEvidence + "}")
                    + (cooldownAudit.isBlank() ? "" : ",cooldownAudit{" + cooldownAudit + "}")
                    + ",unitIndex{" + unitStats.compact() + "}"
                    + ",calamityIndex{" + calamityStats.compact() + "}";
        }
    }

    private enum RearReason { OK, IN_PLACE, BLOCKING_TARGET, RECENT_COMBAT, UNSAFE_PRESENCE }

    private static final class DiagnosticsBuilder {
        private final int territoryCells;
        private final int hostileZones;
        private final int units;
        private final int calamities;
        private final StringBuilder targetEvidence = new StringBuilder();
        private final String cooldownAudit;
        private final SourceStats unitStats;
        private final SourceStats calamityStats;
        private int candidateChecks, candidateAccepted, stagingNoFriendlyTerritory;
        private int stagingUnsafePresence;
        private int duplicateStaging, stagingAnchorMissing, targetAnchorMissing;
        private int unitChecks, unitEligible, unitInPlace, unitBlockingTarget, unitRecentCombat,
                unitUnsafePresence;
        private int squadRejectedMinimum, squadCandidatesPassed;
        private int calamityChecks, calamityEligible, calamityInPlace, calamityBlockingTarget, calamityRecentCombat,
                calamityUnsafePresence;
        private int calamityRejectedMinimum, calamityCandidatesPassed, options;

        private DiagnosticsBuilder(Request request, int hostileZones) {
            this.territoryCells = request.territories().size();
            this.hostileZones = hostileZones;
            this.units = request.units().size();
            this.calamities = request.calamities().size();
            this.cooldownAudit = request.cooldownAudit();
            this.unitStats = request.unitStats();
            this.calamityStats = request.calamityStats();
        }

        private void recordRear(RearReason reason, boolean calamity) {
            if (calamity) {
                calamityChecks++;
                switch (reason) {
                    case OK -> calamityEligible++;
                    case IN_PLACE -> { calamityEligible++; calamityInPlace++; }
                    case BLOCKING_TARGET -> calamityBlockingTarget++;
                    case RECENT_COMBAT -> calamityRecentCombat++;
                    case UNSAFE_PRESENCE -> calamityUnsafePresence++;
                }
            } else {
                unitChecks++;
                switch (reason) {
                    case OK -> unitEligible++;
                    case IN_PLACE -> { unitEligible++; unitInPlace++; }
                    case BLOCKING_TARGET -> unitBlockingTarget++;
                    case RECENT_COMBAT -> unitRecentCombat++;
                    case UNSAFE_PRESENCE -> unitUnsafePresence++;
                }
            }
        }

        private void addTargetEvidence(String evidence) {
            if (evidence == null || evidence.isBlank() || targetEvidence.length() >= 4_096) return;
            if (!targetEvidence.isEmpty()) targetEvidence.append(" || ");
            targetEvidence.append(evidence);
        }

        private Diagnostics build() {
            return new Diagnostics(territoryCells, hostileZones, units, calamities,
                    candidateChecks, candidateAccepted, stagingNoFriendlyTerritory,
                    stagingUnsafePresence, duplicateStaging, stagingAnchorMissing,
                    targetAnchorMissing, unitChecks, unitEligible, unitInPlace, unitBlockingTarget, unitRecentCombat,
                    unitUnsafePresence, squadRejectedMinimum,
                    squadCandidatesPassed, calamityChecks, calamityEligible, calamityInPlace, calamityBlockingTarget,
                    calamityRecentCombat, calamityUnsafePresence,
                    calamityRejectedMinimum, calamityCandidatesPassed, options,
                    targetEvidence.toString(),
                    cooldownAudit,
                    unitStats, calamityStats);
        }
    }
}
