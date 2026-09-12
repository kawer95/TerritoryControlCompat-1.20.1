package com.arxyt.territorycontrolcompat.mixin;

import com.arxyt.territorycontrolcompat.compat.SporeCampaignDirector;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a campaign-assigned Calamity from planting its native mound in uncaptured terrain. */
@Pseudo
@Mixin(targets = "com.Harbinger.Spore.Sentities.BaseEntities.Calamity")
public abstract class SporeCalamityCampaignMoundMixin {
    @Inject(method = "SummonMound", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void territorycontrolcompat$gateCampaignMound(Entity entity, CallbackInfo callbackInfo) {
        if (!SporeCampaignDirector.allowNativeMoundLanding(entity)) callbackInfo.cancel();
    }
}
