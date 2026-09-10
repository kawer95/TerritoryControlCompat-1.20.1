package com.arxyt.territorycontrolcompat.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Explicit opt-in: territory ownership never silently rewrites civilian nationality. */
public final class RatNationsCivilianConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue CONTROL_AFFILIATION;
    static {
        ForgeConfigSpec.Builder builder=new ForgeConfigSpec.Builder();
        builder.push("rat_nations");
        CONTROL_AFFILIATION=builder.comment("When true, eligible Rat Nations civilians in a conquered loaded chunk adopt its uniquely mapped Rat Nations nationality.").define("civilianControlAffiliationEnabled",false);
        builder.pop();SPEC=builder.build();
    }
    private RatNationsCivilianConfig(){}
    public static boolean civilianControlAffiliationEnabled(){return CONTROL_AFFILIATION.get();}
}
