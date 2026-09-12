package com.arxyt.territorycontrolcompat.mixin;

import com.arxyt.territorycontrolcompat.compat.SporeCompat;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Corrosive drowned lumps also apply vanilla poison from their random-tick area scan. */
@Pseudo
@Mixin(targets = "com.Harbinger.Spore.Sblocks.CorrosiveDrownedLump")
public abstract class SporeCorrosiveDrownedLumpMixin {
    @Redirect(
            method = "m_213898_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;m_7292_(Lnet/minecraft/world/effect/MobEffectInstance;)Z"),
            remap = false,
            require = 0)
    private boolean territorycontrolcompat$skipFriendlyEffects(
            LivingEntity target, MobEffectInstance effect) {
        if (SporeCompat.shouldProtectFriendlyEntity(target.level(), target)) {
            return false;
        }
        return target.addEffect(effect);
    }
}
