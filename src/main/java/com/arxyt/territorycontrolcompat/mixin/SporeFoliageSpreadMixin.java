package com.arxyt.territorycontrolcompat.mixin;

import com.arxyt.territorycontrolcompat.compat.SporeCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * FoliageSpread normally guards regular infection replacements through Level#setBlock. Its wooden
 * conversion path instead creates a falling fungal block directly, so it needs the same territory check.
 */
@Pseudo
@Mixin(targets = "com.Harbinger.Spore.Sentities.FoliageSpread")
public interface SporeFoliageSpreadMixin {
    @Redirect(
            method = "convertWood(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;m_201971_(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/entity/item/FallingBlockEntity;"),
            remap = false,
            require = 0)
    public static FallingBlockEntity territorycontrolcompat$guardFungalFallingConversion(
            Level level, BlockPos pos, BlockState state) {
        return SporeCompat.allowFungalInfectionFallingConversion(level, pos)
                ? FallingBlockEntity.fall(level, pos, state)
                : null;
    }
}
