package com.arxyt.territorycontrolcompat.data;

import com.arxyt.territorycontrolcompat.TerritoryControlCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** World-authoritative compatibility settings. New controls always have explicit old-save defaults. */
public final class CompatSavedData extends SavedData {
    private static final String DATA_NAME = TerritoryControlCompat.MODID + "_config";
    private Config config = Config.DEFAULT;

    public static CompatSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(CompatSavedData::load, CompatSavedData::new, DATA_NAME);
    }

    public Config config() { return config; }
    public void setConfig(Config config) { this.config = config.normalized(); setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("BalancedOvaryDensity", config.balancedOvaryDensity());
        tag.putBoolean("RestrictCaerula", config.restrictCaerula());
        tag.putBoolean("TideRecession", config.tideRecession());
        tag.putBoolean("RestrictEyes", config.restrictEyes());
        tag.putBoolean("EyesCollapse", config.eyesCollapse());
        tag.putBoolean("RestrictPhayriosis", config.restrictPhayriosis());
        tag.putBoolean("PhayriosisCure", config.phayriosisCure());
        tag.putBoolean("RestrictSporeMounds", config.restrictSporeMounds());
        tag.putBoolean("RestrictSporeVigils", config.restrictSporeVigils());
        tag.putBoolean("RestrictSporeSpawnerStructures", config.restrictSporeSpawnerStructures());
        tag.putBoolean("RestoreSporeOnLoss", config.restoreSporeOnLoss());
        tag.putBoolean("RestrictSporeInfectionSpread", config.restrictSporeInfectionSpread());
        tag.putBoolean("DisableSporeUndergroundBias", config.disableSporeUndergroundBias());
        tag.putBoolean("RestrictAbominationsSpread", config.restrictAbominationsSpread());
        tag.putBoolean("PurgeAbominationsOnLoss", config.purgeAbominationsOnLoss());
        tag.putBoolean("RestrictPrionTerrain", config.restrictPrionTerrain());
        tag.putBoolean("PurgePrionOnLoss", config.purgePrionOnLoss());
        tag.putBoolean("RatNationsNaturalRefresh", config.ratNationsNaturalRefresh());
        tag.putBoolean("ProtectSporeFriendlyEntities", config.protectSporeFriendlyEntities());
        tag.putBoolean("RatNationsCampaigns", config.ratNationsCampaigns());
        tag.putInt("RatNationsVictoryCooldownMinutes", config.ratNationsVictoryCooldownMinutes());
        tag.putInt("RatNationsFailureCooldownMinutes", config.ratNationsFailureCooldownMinutes());
        tag.putBoolean("SporeRegularCampaigns", config.sporeRegularCampaigns());
        tag.putBoolean("SporeGrandCampaigns", config.sporeGrandCampaigns());
        tag.putBoolean("SporeCampaignScentReinforcements", config.sporeCampaignScentReinforcements());
        tag.putBoolean("SporeCampaignMoundEstablishment", config.sporeCampaignMoundEstablishment());
        tag.putInt("SporeCampaignVictoryCooldownMinutes", config.sporeCampaignVictoryCooldownMinutes());
        tag.putInt("SporeCampaignFailureCooldownMinutes", config.sporeCampaignFailureCooldownMinutes());
        tag.putInt("SporeGrandCampaignVictoryCooldownMinutes", config.sporeGrandCampaignVictoryCooldownMinutes());
        tag.putInt("SporeGrandCampaignFailureCooldownMinutes", config.sporeGrandCampaignFailureCooldownMinutes());
        return tag;
    }

    public static CompatSavedData load(CompoundTag tag) {
        CompatSavedData data = new CompatSavedData();
        data.config = new Config(
                tag.getBoolean("BalancedOvaryDensity"), tag.getBoolean("RestrictCaerula"), tag.getBoolean("TideRecession"),
                tag.getBoolean("RestrictEyes"), tag.getBoolean("EyesCollapse"), tag.getBoolean("RestrictPhayriosis"),
                tag.getBoolean("PhayriosisCure"), tag.getBoolean("RestrictSporeMounds"), tag.getBoolean("RestrictSporeVigils"),
                tag.getBoolean("RestrictSporeSpawnerStructures"), tag.getBoolean("RestoreSporeOnLoss"),
                tag.getBoolean("RestrictSporeInfectionSpread"), tag.getBoolean("DisableSporeUndergroundBias"),
                tag.getBoolean("RestrictAbominationsSpread"), tag.getBoolean("PurgeAbominationsOnLoss"),
                tag.getBoolean("RestrictPrionTerrain"), tag.getBoolean("PurgePrionOnLoss"),
                tag.getBoolean("RatNationsNaturalRefresh"), tag.getBoolean("ProtectSporeFriendlyEntities"),
                tag.getBoolean("RatNationsCampaigns"), intValue(tag, "RatNationsVictoryCooldownMinutes", 10),
                intValue(tag, "RatNationsFailureCooldownMinutes", 5), tag.getBoolean("SporeRegularCampaigns"),
                tag.getBoolean("SporeGrandCampaigns"), booleanValue(tag, "SporeCampaignScentReinforcements", true),
                booleanValue(tag, "SporeCampaignMoundEstablishment", true),
                intValue(tag, "SporeCampaignVictoryCooldownMinutes", 10), intValue(tag, "SporeCampaignFailureCooldownMinutes", 5),
                intValue(tag, "SporeGrandCampaignVictoryCooldownMinutes", 30), intValue(tag, "SporeGrandCampaignFailureCooldownMinutes", 15));
        return data;
    }

    private static boolean booleanValue(CompoundTag tag, String key, boolean fallback) { return tag.contains(key) ? tag.getBoolean(key) : fallback; }
    private static int intValue(CompoundTag tag, String key, int fallback) { return tag.contains(key) ? tag.getInt(key) : fallback; }

    public record Config(
            boolean balancedOvaryDensity, boolean restrictCaerula, boolean tideRecession,
            boolean restrictEyes, boolean eyesCollapse, boolean restrictPhayriosis, boolean phayriosisCure,
            boolean restrictSporeMounds, boolean restrictSporeVigils, boolean restrictSporeSpawnerStructures,
            boolean restoreSporeOnLoss, boolean restrictSporeInfectionSpread, boolean disableSporeUndergroundBias,
            boolean restrictAbominationsSpread, boolean purgeAbominationsOnLoss, boolean restrictPrionTerrain,
            boolean purgePrionOnLoss, boolean ratNationsNaturalRefresh, boolean protectSporeFriendlyEntities,
            boolean ratNationsCampaigns, int ratNationsVictoryCooldownMinutes, int ratNationsFailureCooldownMinutes,
            boolean sporeRegularCampaigns, boolean sporeGrandCampaigns,
            boolean sporeCampaignScentReinforcements, boolean sporeCampaignMoundEstablishment,
            int sporeCampaignVictoryCooldownMinutes, int sporeCampaignFailureCooldownMinutes,
            int sporeGrandCampaignVictoryCooldownMinutes, int sporeGrandCampaignFailureCooldownMinutes) {
        public static final Config DEFAULT = new Config(
                false, false, false, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false, false,
                10, 5, false, false, true, true, 10, 5, 30, 15);

        private Config copy(boolean balancedOvaryDensity, boolean restrictCaerula, boolean tideRecession,
                            boolean restrictEyes, boolean eyesCollapse, boolean restrictPhayriosis, boolean phayriosisCure,
                            boolean restrictSporeMounds, boolean restrictSporeVigils, boolean restrictSporeSpawnerStructures,
                            boolean restoreSporeOnLoss, boolean restrictSporeInfectionSpread, boolean disableSporeUndergroundBias,
                            boolean restrictAbominationsSpread, boolean purgeAbominationsOnLoss, boolean restrictPrionTerrain,
                            boolean purgePrionOnLoss) {
            return all(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis,
                    phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss,
                    restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss,
                    restrictPrionTerrain, purgePrionOnLoss, ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns,
                    ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns,
                    sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes,
                    sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes);
        }

        public Config withBalancedOvaryDensity(boolean value) { return copy(value, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withRestrictCaerula(boolean value) { return copy(balancedOvaryDensity, value, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withTideRecession(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, value, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withRestrictEyes(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, value, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withEyesCollapse(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, value, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withRestrictPhayriosis(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, value, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withPhayriosisCure(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, value, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withRestrictSporeMounds(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, value, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withRestrictSporeVigils(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, value, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withRestrictSporeSpawnerStructures(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, value, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withRestoreSporeOnLoss(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, value, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withRestrictSporeInfectionSpread(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, value, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withDisableSporeUndergroundBias(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, value, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withRestrictAbominationsSpread(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, value, purgeAbominationsOnLoss, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withPurgeAbominationsOnLoss(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, value, restrictPrionTerrain, purgePrionOnLoss); }
        public Config withRestrictPrionTerrain(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, value, purgePrionOnLoss); }
        public Config withPurgePrionOnLoss(boolean value) { return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss, restrictPrionTerrain, value); }

        public Config withRatNationsNaturalRefresh(boolean value) { return campaignCopy(value, protectSporeFriendlyEntities, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withProtectSporeFriendlyEntities(boolean value) { return campaignCopy(ratNationsNaturalRefresh, value, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withRatNationsCampaigns(boolean value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, value, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withRatNationsVictoryCooldownMinutes(int value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns, value, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withRatNationsFailureCooldownMinutes(int value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, value, sporeRegularCampaigns, sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withSporeRegularCampaigns(boolean value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, value, sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withSporeGrandCampaigns(boolean value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, value, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withSporeCampaignScentReinforcements(boolean value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns, value, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withSporeCampaignMoundEstablishment(boolean value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns, sporeCampaignScentReinforcements, value, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withSporeCampaignVictoryCooldownMinutes(int value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, value, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withSporeCampaignFailureCooldownMinutes(int value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, value, sporeGrandCampaignVictoryCooldownMinutes, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withSporeGrandCampaignVictoryCooldownMinutes(int value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, value, sporeGrandCampaignFailureCooldownMinutes); }
        public Config withSporeGrandCampaignFailureCooldownMinutes(int value) { return campaignCopy(ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns, ratNationsVictoryCooldownMinutes, ratNationsFailureCooldownMinutes, sporeRegularCampaigns, sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment, sporeCampaignVictoryCooldownMinutes, sporeCampaignFailureCooldownMinutes, sporeGrandCampaignVictoryCooldownMinutes, value); }

        private Config campaignCopy(boolean natural, boolean friendly, boolean ratCampaigns, int ratVictory, int ratFailure,
                                    boolean regular, boolean grand, boolean scent, boolean mound, int regularVictory, int regularFailure,
                                    int grandVictory, int grandFailure) {
            return all(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis,
                    phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss,
                    restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss,
                    restrictPrionTerrain, purgePrionOnLoss, natural, friendly, ratCampaigns, ratVictory, ratFailure, regular, grand,
                    scent, mound, regularVictory, regularFailure, grandVictory, grandFailure).normalized();
        }

        private static Config all(boolean balanced, boolean caerula, boolean tide, boolean eyes, boolean eyeCollapse,
                                  boolean phayriosis, boolean phayriosisCure, boolean mounds, boolean vigils, boolean spawners,
                                  boolean restore, boolean infection, boolean underground, boolean abominations, boolean purgeAbominations,
                                  boolean prion, boolean purgePrion, boolean natural, boolean friendly, boolean ratCampaigns,
                                  int ratVictory, int ratFailure, boolean regular, boolean grand, boolean scent, boolean mound,
                                  int regularVictory, int regularFailure, int grandVictory, int grandFailure) {
            return new Config(balanced, caerula, tide, eyes, eyeCollapse, phayriosis, phayriosisCure, mounds, vigils, spawners,
                    restore, infection, underground, abominations, purgeAbominations, prion, purgePrion, natural, friendly,
                    ratCampaigns, ratVictory, ratFailure, regular, grand, scent, mound, regularVictory, regularFailure,
                    grandVictory, grandFailure);
        }

        public Config normalized() {
            return all(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse, restrictPhayriosis,
                    phayriosisCure, restrictSporeMounds, restrictSporeVigils, restrictSporeSpawnerStructures, restoreSporeOnLoss,
                    restrictSporeInfectionSpread, disableSporeUndergroundBias, restrictAbominationsSpread, purgeAbominationsOnLoss,
                    restrictPrionTerrain, purgePrionOnLoss, ratNationsNaturalRefresh, protectSporeFriendlyEntities, ratNationsCampaigns,
                    clamp(ratNationsVictoryCooldownMinutes), clamp(ratNationsFailureCooldownMinutes), sporeRegularCampaigns,
                    sporeGrandCampaigns, sporeCampaignScentReinforcements, sporeCampaignMoundEstablishment,
                    clamp(sporeCampaignVictoryCooldownMinutes), clamp(sporeCampaignFailureCooldownMinutes),
                    clamp(sporeGrandCampaignVictoryCooldownMinutes), clamp(sporeGrandCampaignFailureCooldownMinutes));
        }

        private static int clamp(int value) { return Math.max(1, Math.min(1440, value)); }
        public boolean hasPlacementRestrictions() { return restrictCaerula || restrictEyes || restrictPhayriosis || restrictSporeSpawnerStructures || restrictSporeInfectionSpread || restrictAbominationsSpread || restrictPrionTerrain; }
    }
}
