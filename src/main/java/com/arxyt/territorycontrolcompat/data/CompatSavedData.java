package com.arxyt.territorycontrolcompat.data;

import com.arxyt.territorycontrolcompat.TerritoryControlCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

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
        return tag;
    }

    public static CompatSavedData load(CompoundTag tag) {
        CompatSavedData data = new CompatSavedData();
        data.config = new Config(
                tag.getBoolean("BalancedOvaryDensity"),
                tag.getBoolean("RestrictCaerula"),
                tag.getBoolean("TideRecession"),
                tag.getBoolean("RestrictEyes"),
                tag.getBoolean("EyesCollapse"),
                tag.getBoolean("RestrictPhayriosis"),
                tag.getBoolean("PhayriosisCure"),
                tag.getBoolean("RestrictSporeMounds"),
                tag.getBoolean("RestrictSporeVigils"),
                tag.getBoolean("RestrictSporeSpawnerStructures"),
                tag.getBoolean("RestoreSporeOnLoss"),
                tag.getBoolean("RestrictSporeInfectionSpread"),
                tag.getBoolean("DisableSporeUndergroundBias"));
        return data;
    }

    public record Config(
            boolean balancedOvaryDensity,
            boolean restrictCaerula,
            boolean tideRecession,
            boolean restrictEyes,
            boolean eyesCollapse,
            boolean restrictPhayriosis,
            boolean phayriosisCure,
            boolean restrictSporeMounds,
            boolean restrictSporeVigils,
            boolean restrictSporeSpawnerStructures,
            boolean restoreSporeOnLoss,
            boolean restrictSporeInfectionSpread,
            boolean disableSporeUndergroundBias) {
        public static final Config DEFAULT = new Config(false, false, false, false, false, false, false,
                false, false, false, false, false, false);

        /** Keeps older in-code callers source compatible while new controls default to disabled. */
        public Config(boolean balancedOvaryDensity, boolean restrictCaerula, boolean tideRecession,
                      boolean restrictEyes, boolean eyesCollapse, boolean restrictPhayriosis,
                      boolean phayriosisCure) {
            this(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, false, false, false, false, false, false);
        }

        public Config withBalancedOvaryDensity(boolean value) {
            return copy(value, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config withRestrictCaerula(boolean value) {
            return copy(balancedOvaryDensity, value, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config withTideRecession(boolean value) {
            return copy(balancedOvaryDensity, restrictCaerula, value, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config withRestrictEyes(boolean value) {
            return copy(balancedOvaryDensity, restrictCaerula, tideRecession, value, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config withEyesCollapse(boolean value) {
            return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, value,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config withRestrictPhayriosis(boolean value) {
            return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    value, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config withPhayriosisCure(boolean value) {
            return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, value, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config withRestrictSporeMounds(boolean value) {
            return new Config(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, value, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config withRestrictSporeVigils(boolean value) {
            return new Config(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, value,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config withRestrictSporeSpawnerStructures(boolean value) {
            return new Config(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    value, restoreSporeOnLoss, restrictSporeInfectionSpread, disableSporeUndergroundBias);
        }

        public Config withRestoreSporeOnLoss(boolean value) {
            return new Config(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, value, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config withRestrictSporeInfectionSpread(boolean value) {
            return new Config(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, value,
                    disableSporeUndergroundBias);
        }

        public Config withDisableSporeUndergroundBias(boolean value) {
            return copy(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread, value);
        }

        private static Config copy(boolean balancedOvaryDensity, boolean restrictCaerula,
                                   boolean tideRecession, boolean restrictEyes, boolean eyesCollapse,
                                   boolean restrictPhayriosis, boolean phayriosisCure,
                                   boolean restrictSporeMounds, boolean restrictSporeVigils,
                                   boolean restrictSporeSpawnerStructures, boolean restoreSporeOnLoss,
                                   boolean restrictSporeInfectionSpread, boolean disableSporeUndergroundBias) {
            return new Config(balancedOvaryDensity, restrictCaerula, tideRecession, restrictEyes, eyesCollapse,
                    restrictPhayriosis, phayriosisCure, restrictSporeMounds, restrictSporeVigils,
                    restrictSporeSpawnerStructures, restoreSporeOnLoss, restrictSporeInfectionSpread,
                    disableSporeUndergroundBias);
        }

        public Config normalized() { return this; }

        public boolean hasPlacementRestrictions() {
            return restrictCaerula || restrictEyes || restrictPhayriosis
                    || restrictSporeSpawnerStructures || restrictSporeInfectionSpread;
        }
    }
}
