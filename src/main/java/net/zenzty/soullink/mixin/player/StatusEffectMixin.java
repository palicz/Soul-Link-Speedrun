package net.zenzty.soullink.mixin.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.server.health.SharedPotionHandler;

/**
 * Mixin to intercept status effect application for shared potions.
 */
@Mixin(LivingEntity.class)
public abstract class StatusEffectMixin {

    /**
     * Intercepts addStatusEffect to sync potion effects between players. For instant splash
     * potions, only the closest player receives the effect.
     */
    @Inject(method = "addStatusEffect(Lnet/minecraft/entity/effect/StatusEffectInstance;Lnet/minecraft/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void onAddStatusEffect(StatusEffectInstance effect, Entity source,
            CallbackInfoReturnable<Boolean> cir) {
        // Only process if this is a ServerPlayerEntity
        if (!((Object) this instanceof ServerPlayerEntity player)) {
            return;
        }

        // Check if potion syncing allows this effect
        try {
            if (!SharedPotionHandler.onEffectApplied(player, effect, source)) {
                cir.setReturnValue(false);
                cir.cancel();
            }
        } catch (Exception e) {
            net.zenzty.soullink.SoulLink.LOGGER.error(
                    "Error in SharedPotionHandler.onEffectApplied - allowing vanilla behavior", e);
        }
    }
}
