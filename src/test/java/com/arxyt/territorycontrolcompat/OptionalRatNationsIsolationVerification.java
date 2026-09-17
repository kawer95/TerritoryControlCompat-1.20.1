package com.arxyt.territorycontrolcompat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Ensures the always-loaded startup path has no Rat Nations class references. */
public final class OptionalRatNationsIsolationVerification {
    private static final String RAT_INTERNAL_NAME = "com/arxyt/ratnations";

    private OptionalRatNationsIsolationVerification() {
    }

    public static void main(String[] args) throws IOException {
        for (String type : List.of(
                "com.arxyt.territorycontrolcompat.TerritoryControlCompat",
                "com.arxyt.territorycontrolcompat.compat.CampaignCombatTracker",
                "com.arxyt.territorycontrolcompat.compat.CampaignDeploymentService")) {
            requireNoRatNationsReference(type);
        }
        System.out.println("Optional Rat Nations isolation verification passed");
    }

    private static void requireNoRatNationsReference(String type) throws IOException {
        String resource = "/" + type.replace('.', '/') + ".class";
        try (InputStream input = OptionalRatNationsIsolationVerification.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing class resource: " + resource);
            String bytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            if (bytes.contains(RAT_INTERNAL_NAME)) {
                throw new IllegalStateException("Always-loaded class still hard-references Rat Nations: " + type);
            }
        }
    }
}
