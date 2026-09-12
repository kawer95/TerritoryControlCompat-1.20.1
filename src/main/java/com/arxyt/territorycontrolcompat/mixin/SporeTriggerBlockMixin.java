package com.arxyt.territorycontrolcompat.mixin;

import com.arxyt.territorycontrolcompat.compat.SporeCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes contact-triggered Spore foliage behave like Spore's own friendly-unit checks for
 * entities supplied by another mod. The source block code is left untouched for hostile units.
 */
@Pseudo
@Mixin(targets = {
        "com.Harbinger.Spore.Sblocks.FungalClamp",
        "com.Harbinger.Spore.Sblocks.FangLump",
        "com.Harbinger.Spore.Sblocks.FrozenRemains",
        "com.Harbinger.Spore.Sblocks.ExplodingLump",
        "com.Harbinger.Spore.Sblocks.Hand",
        "com.Harbinger.Spore.Sblocks.HangingPlantBub",
        "com.Harbinger.Spore.Sblocks.HangingPlant",
        "com.Harbinger.Spore.Sblocks.BileLump",
        "com.Harbinger.Spore.Sblocks.Acid",
        "com.Harbinger.Spore.Sblocks.Remains",
        "com.Harbinger.Spore.Sblocks.WallRemainsBlock",
        "com.Harbinger.Spore.Sblocks.UnderWaterFungusTop",
        "com.Harbinger.Spore.Sblocks.Tar"
})
public abstract class SporeTriggerBlockMixin {
    @Inject(
            method = "m_7892_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void territorycontrolcompat$skipFriendlyEntity(
            BlockState state, Level level, BlockPos pos, Entity entity, CallbackInfo callbackInfo) {
        if (SporeCompat.shouldProtectFriendlyEntity(level, entity)) {
            callbackInfo.cancel();
        }
    }
}
