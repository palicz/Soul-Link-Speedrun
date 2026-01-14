package net.zenzty.soullink.mixin.player;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;

/**
 * Mixin for ServerPlayerEntity to prevent death during active runs and sync healing.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    /**
     * Final safety net - cancel any death during an active run. This prevents the death screen from
     * ever appearing.
     */
    @Inject(method = "onDeath", at = @At("HEAD"), cancellable = true)
    private void preventDeathDuringRun(DamageSource damageSource, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        RunManager runManager = RunManager.getInstance();

        // Only intercept death during active runs
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        SoulLink.LOGGER.warn(
                "onDeath called during active run for {} - this shouldn't happen! Cancelling death.",
                player.getName().getString());

        // Cancel the death event to prevent death screen
        ci.cancel();

        // Restore health
        player.setHealth(player.getMaxHealth());

        // Clear lingering harmful effects and extinguish fire
        List<RegistryEntry<StatusEffect>> effectsToRemove = player.getStatusEffects().stream()
                .filter(effect -> !effect.getEffectType().value().isBeneficial())
                .map(StatusEffectInstance::getEffectType).toList();

        effectsToRemove.forEach(player::removeStatusEffect);
        player.extinguish();

        // Trigger game over if not already in that state
        if (!runManager.isGameOver()) {
            runManager.getServer().execute(() -> {
                if (!runManager.isGameOver()) {
                    runManager.triggerGameOver();
                }
            });
        }
    }

}
