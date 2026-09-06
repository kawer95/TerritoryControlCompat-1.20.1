package com.arxyt.territorycontrolcompat.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents Phayriosis spawn hooks from synchronously loading an unavailable chunk. */
@Pseudo
@Mixin(targets = "net.mcreator.phayriosis.procedures.Global6biomeProcedure")
public abstract class PhayriosisEntityJoinBiomeMixin {
    @Inject(method = "onEntitySpawned", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void territoryControlCompat$skipUnavailableChunk(
            EntityJoinLevelEvent event, CallbackInfo ci) {
        if (event.getLevel() instanceof ServerLevel level
                && !level.hasChunkAt(event.getEntity().blockPosition())) {
            ci.cancel();
        }
    }
}
