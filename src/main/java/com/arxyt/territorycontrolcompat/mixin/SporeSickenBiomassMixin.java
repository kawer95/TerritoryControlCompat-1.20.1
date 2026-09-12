package com.arxyt.territorycontrolcompat.mixin;

import com.arxyt.territorycontrolcompat.compat.SporeCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sicken biomass uses the vanilla poison effect rather than a Spore effect registry entry. */
@Pseudo
@Mixin(targets = "com.Harbinger.Spore.Sblocks.SickenBiomassBlock")
public abstract class SporeSickenBiomassMixin {
    @Inject(
            method = "m_6256_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void territorycontrolcompat$skipFriendlyAttack(
            BlockState state, Level level, BlockPos pos, Player player, CallbackInfo callbackInfo) {
        if (SporeCompat.shouldProtectFriendlyEntity(level, player)) {
            callbackInfo.cancel();
        }
    }
}
