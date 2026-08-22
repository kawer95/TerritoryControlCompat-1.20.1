package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Cancels new Spore organoids outside Spore-controlled chunks on the logical server.
 *
 * <p>The base mod creates both entities directly instead of placing a block first, so
 * the Territory Control block-placement guard cannot enforce these two rules.</p>
 */
public final class SporeEntitySpawnHandler {
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ResourceLocation entityId = ForgeRegistries.ENTITY_TYPES.getKey(event.getEntity().getType());
        if (entityId == null || !SporeCompat.MOD_ID.equals(entityId.getNamespace())) {
            return;
        }

        BlockPos spawnPos = event.getEntity().blockPosition();
        if ("mound".equals(entityId.getPath()) && !SporeCompat.allowMoundSpawn(level, spawnPos)) {
            event.setCanceled(true);
        } else if ("vigil".equals(entityId.getPath()) && !SporeCompat.allowVigilSpawn(level, spawnPos)) {
            event.setCanceled(true);
        }
    }
}
