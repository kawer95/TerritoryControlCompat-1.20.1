package com.arxyt.territorycontrolcompat.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;

/** Applies the optional same-faction/alliance immunity to Spore status effects. */
public final class SporeFriendlyEntityHandler {
    @SubscribeEvent
    public void onSporeEffectApplicable(MobEffectEvent.Applicable event) {
        if (SporeCompat.shouldBlockFriendlySporeEffect(event.getEntity(), event.getEffectInstance())) {
            event.setResult(Event.Result.DENY);
        }
    }

    /** Clears a debuff that was already present when the server option was enabled. */
    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel)
                || entity.tickCount % 20 != 0
                || !SporeCompat.shouldProtectFriendlyEntity(entity.level(), entity)) {
            return;
        }

        for (MobEffectInstance effect : new ArrayList<>(entity.getActiveEffects())) {
            if (SporeCompat.isSporeDebuff(effect)) {
                entity.removeEffect(effect.getEffect());
            }
        }
    }
}
