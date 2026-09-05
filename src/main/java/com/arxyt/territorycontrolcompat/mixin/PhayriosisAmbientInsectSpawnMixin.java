package com.arxyt.territorycontrolcompat.mixin;

import com.arxyt.territorycontrolcompat.compat.PhayriosisCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Restricts ambient parasite production without suppressing combat or infection summons. */
@Pseudo
@Mixin(targets = {
        "net.mcreator.phayriosis.procedures.NucleusOnEntityTickUpdateProcedure",
        "net.mcreator.phayriosis.procedures.PrimordialLingererOnEntityTickUpdateProcedure"
})
public abstract class PhayriosisAmbientInsectSpawnMixin {
    @Redirect(
            method = "execute",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityType;m_262496_(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/MobSpawnType;)Lnet/minecraft/world/entity/Entity;",
                    remap = false),
            require = 0,
            remap = false)
    private static Entity territoryControlCompat$guardAmbientInsect(
            EntityType<?> type, ServerLevel level, BlockPos pos, MobSpawnType spawnType) {
        if (PhayriosisCompat.isSmallInsect(type)
                && !PhayriosisCompat.sourceCanSpread(level, pos.getX(), pos.getY(), pos.getZ())) {
            return null;
        }
        return type.spawn(level, pos, spawnType);
    }
}
