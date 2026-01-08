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
     * 
     * For natural regeneration (small heals <= 1.0), the healing is divided by the number of
     * players to normalize regen speed regardless of player count.
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

        // Small heal amounts (<=1.0) typically indicate natural regeneration from saturation
        // Divide by player count to normalize regen speed
        boolean isNaturalRegen = amount <= 1.0f;
        if (isNaturalRegen) {
            // Let SharedStatsHandler handle the normalized regen
            SharedStatsHandler.onNaturalRegen(player, amount);
        } else {
            // Larger heals (potions, golden apples) sync normally
            float newHealth = player.getHealth();
            SharedStatsHandler.onPlayerHealed(player, newHealth);
        }
    }
}

