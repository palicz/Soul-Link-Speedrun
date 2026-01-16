package net.zenzty.soullink.mixin.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;

/**
 * Mixin for LivingEntity to intercept healing for ServerPlayerEntity instances.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    private static final float NATURAL_REGEN_THRESHOLD = 1.0f;

    @Unique
    private float preHealHealth;

    @Inject(method = "heal", at = @At("HEAD"))
    private void recordPreHeal(float amount, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayerEntity player) {
            this.preHealHealth = player.getHealth();
        }
    }

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

        // Hunters are excluded from shared mechanics - use vanilla behavior
        if (ManhuntManager.getInstance().isHunter(player)) {
            return;
        }

        // Compute actual applied healing
        float applied = player.getHealth() - preHealHealth;
        if (applied <= 0)
            return;

        // Small heal amounts (<=NATURAL_REGEN_THRESHOLD) typically indicate natural regeneration
        // from saturation. Exclude potion-based regeneration.
        // Divide by player count to normalize regen speed
        boolean isNaturalRegen = applied <= NATURAL_REGEN_THRESHOLD
                && !player.hasStatusEffect(StatusEffects.REGENERATION);
        if (isNaturalRegen) {
            // Let SharedStatsHandler handle the normalized regen
            SharedStatsHandler.onNaturalRegen(player, applied);
        } else if (player.hasStatusEffect(StatusEffects.REGENERATION)) {
            // Regeneration effect healing - normalize by player count to prevent multiplication
            // when regeneration is synced to all players
            SharedStatsHandler.onRegenerationHeal(player, applied);
        } else {
            // Larger heals (potions, golden apples) sync normally
            SharedStatsHandler.onPlayerHealed(player, player.getHealth());
        }
    }
}

