package com.arxyt.territorycontrolcompat.compat;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread transient reservations prevent concurrent planners from selecting the same unit. */
final class CampaignUnitReservations {
    private static final Map<UUID, CampaignPlanningService.JobKey> RESERVED = new HashMap<>();
    private CampaignUnitReservations() { }

    static boolean tryReserve(CampaignPlanningService.JobKey key, Collection<UUID> ids) {
        if (ids.stream().anyMatch(id -> RESERVED.containsKey(id) && !RESERVED.get(id).equals(key))) return false;
        ids.forEach(id -> RESERVED.put(id, key));
        return true;
    }

    static boolean reserved(UUID id) { return RESERVED.containsKey(id); }
    static void release(CampaignPlanningService.JobKey key) { RESERVED.entrySet().removeIf(entry -> entry.getValue().equals(key)); }
    static void clear() { RESERVED.clear(); }
}
