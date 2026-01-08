package net.zenzty.soullink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.RunManager;
import net.zenzty.soullink.SharedStatsHandler;

/**
 * Mixin for LivingEntity to intercept healing for ServerPlayerEntity instances.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    /**
     * Intercepts healing to sync health increases to all players. Called after the heal() method
     * has updated the entity's health.
     */
    @Inject(method = "heal", at = @At("TAIL"))
    private void onHeal(float amount, CallbackInfo ci) {
        // Only process if this is a ServerPlayerEntity
        if (!((Object) this instanceof ServerPlayerEntity player)) {
            return;
        }

        // Only sync if run is active and not already syncing
        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive() || SharedStatsHandler.isSyncing()) {
            return;
        }

        // Get the player's current health after healing
        float newHealth = player.getHealth();

        // Sync the healing to all players
        SharedStatsHandler.onPlayerHealed(player, newHealth);
    }
}

