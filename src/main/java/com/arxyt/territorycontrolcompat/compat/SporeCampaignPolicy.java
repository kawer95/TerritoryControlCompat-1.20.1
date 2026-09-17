package com.arxyt.territorycontrolcompat.compat;

/** Stateless availability rules shared by the Spore campaign director and dependency-free checks. */
final class SporeCampaignPolicy {
    private SporeCampaignPolicy() {
    }

    /**
     * SearchPos is a long-lived native waypoint: wounds, biomass, Wombs and evolution all write
     * it, then it may be inherited by later forms. A unit is only busy when it is already
     * assigned to a campaign or actively fighting, not merely because this stale waypoint exists.
     */
    static boolean isAvailableInfected(boolean assigned, boolean hasTarget) {
        return !assigned && !hasTarget;
    }
}
