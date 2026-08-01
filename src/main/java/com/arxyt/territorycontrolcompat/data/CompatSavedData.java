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
        return tag;
    }

    public static CompatSavedData load(CompoundTag tag) {
        CompatSavedData data = new CompatSavedData();
        data.config = new Config(tag.getBoolean("BalancedOvaryDensity"), tag.getBoolean("RestrictCaerula"), tag.getBoolean("TideRecession"), tag.getBoolean("RestrictEyes"), tag.getBoolean("EyesCollapse"), tag.getBoolean("RestrictPhayriosis"), tag.getBoolean("PhayriosisCure"));
        return data;
    }

    public record Config(boolean balancedOvaryDensity, boolean restrictCaerula, boolean tideRecession, boolean restrictEyes, boolean eyesCollapse, boolean restrictPhayriosis, boolean phayriosisCure) {
        public static final Config DEFAULT = new Config(false, false, false, false, false, false, false);
        public Config normalized() { return this; }
    }
}
