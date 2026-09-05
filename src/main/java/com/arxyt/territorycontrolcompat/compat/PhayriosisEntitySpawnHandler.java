package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Prevents Phayriosis' procedure-driven summons from crossing faction borders. */
public final class PhayriosisEntitySpawnHandler {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        if (event.isSpawnCancelled()) {
            return;
        }

        ResourceLocation entityId = ForgeRegistries.ENTITY_TYPES.getKey(event.getEntity().getType());
        if (!isRestrictedAutomaticSpawn(entityId, event.getSpawnType())) {
            return;
        }

        ServerLevel level = event.getLevel().getLevel();
        if (!PhayriosisCompat.sourceCanSpread(level,
                event.getEntity().getX(), event.getEntity().getY(), event.getEntity().getZ())) {
            event.setSpawnCancelled(true);
        }
    }

    static boolean isRestrictedAutomaticSpawn(ResourceLocation entityId, MobSpawnType spawnType) {
        return entityId != null
                && PhayriosisCompat.MOD_ID.equals(entityId.getNamespace())
                && spawnType == MobSpawnType.MOB_SUMMONED;
    }
}
