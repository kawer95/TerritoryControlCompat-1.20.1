package com.arxyt.territorycontrolcompat.mixin;

import com.arxyt.territorycontrolcompat.compat.SporeCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Marks only the entity join performed by Scent's Summon skill as territory-exempt. */
@Pseudo
@Mixin(targets = "com.Harbinger.Spore.Sentities.Utility.ScentEntity")
public abstract class SporeScentSummonMixin {
    @Redirect(
            method = "Summon",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;m_7967_(Lnet/minecraft/world/entity/Entity;)Z",
                    remap = false),
            require = 0,
            remap = false)
    private boolean territoryControlCompat$allowScentSkillSummonSrg(Level level, Entity entity) {
        return SporeCompat.addScentSummonedEntity(level, entity);
    }

    @Redirect(
            method = "Summon",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z",
                    remap = false),
            require = 0,
            remap = false)
    private boolean territoryControlCompat$allowScentSkillSummonMapped(Level level, Entity entity) {
        return SporeCompat.addScentSummonedEntity(level, entity);
    }
}
