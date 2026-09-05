package com.arxyt.territorycontrolcompat.mixin;

import com.arxyt.territorycontrolcompat.compat.SporeCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Makes Proto treat low altitude like normal ground without changing its water or air branches. */
@Pseudo
@Mixin(targets = "com.Harbinger.Spore.Sentities.Organoids.Proto")
public abstract class SporeProtoTerrainMixin {
    @ModifyConstant(
            method = "SummonConstructor(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;)V",
            constant = @Constant(intValue = 63),
            remap = false,
            require = 0)
    private int territorycontrolcompat$adjustUndergroundThreshold(
            int originalThreshold, Level level, Entity source, BlockPos origin) {
        return SporeCompat.adjustUndergroundCalamityThreshold(level, originalThreshold);
    }
}
