package com.arxyt.territorycontrolcompat.mixin;

import com.arxyt.territorycontrolcompat.compat.SporeCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops sporesrp's Builder before any part of its overgrown-spawner template can overwrite a non-Spore chunk.
 *
 * <p>The redirect preserves Builder point reset and cooldown behavior when a structure is denied, preventing
 * a completed Builder from attempting placement every tick. It is optional because sporesrp is optional.</p>
 */
@Pseudo
@Mixin(targets = "com.maha_fish.sporesrp.handler.GastgaberBuilderHandler")
public abstract class GastgaberBuilderStructureMixin {
    @Redirect(
            method = "triggerStructure",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;m_230328_(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;I)Z"),
            remap = false,
            require = 0)
    private static boolean territorycontrolcompat$guardBuilderStructure(
            StructureTemplate template,
            ServerLevelAccessor level,
            BlockPos origin,
            BlockPos pivot,
            StructurePlaceSettings settings,
            RandomSource random,
            int flags) {
        return SporeCompat.allowOvergrownSpawnerStructure(level, origin)
                && template.placeInWorld(level, origin, pivot, settings, random, flags);
    }
}
