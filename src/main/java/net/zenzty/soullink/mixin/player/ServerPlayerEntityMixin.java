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
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;

/**
 * Mixin for ServerPlayerEntity to handle death during active runs. - Runners: Trigger game over
 * (all players share fate) - Hunters: Allow vanilla respawn in the temporary world
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    /**
     * Handle death during an active run. - Runners trigger game over (shared health mechanic) -
     * Hunters are allowed to respawn normally (vanilla mechanics)
     */
    @Inject(method = "onDeath", at = @At("HEAD"), cancellable = true)
    private void onDeathHandler(DamageSource damageSource, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        RunManager runManager = RunManager.getInstance();

        // Only intercept death during active runs
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        ManhuntManager manhuntManager = ManhuntManager.getInstance();

        // Hunters respawn normally with vanilla mechanics
        if (manhuntManager.isHunter(player)) {
            SoulLink.LOGGER.info("Hunter {} died - allowing vanilla respawn",
                    player.getName().getString());
            // Don't cancel - let Minecraft handle the death normally
            // The spawn point was set in PlayerTeleportService, so they'll respawn in temp world
            return;
        }

        // Runners: prevent death and trigger game over
        SoulLink.LOGGER.info("Runner {} died - triggering game over", player.getName().getString());

        // Cancel the death event to prevent death screen
        ci.cancel();

        // Restore health temporarily (will be reset on new run)
        player.setHealth(player.getMaxHealth());

        // Clear lingering harmful effects and extinguish fire
        List<RegistryEntry<StatusEffect>> effectsToRemove = player.getStatusEffects().stream()
                .filter(effect -> !effect.getEffectType().value().isBeneficial())
                .map(StatusEffectInstance::getEffectType).toList();

        effectsToRemove.forEach(player::removeStatusEffect);
        player.extinguish();

        // Trigger game over if not already in that state
        if (!runManager.isGameOver()) {
            net.minecraft.server.MinecraftServer server = runManager.getServer();
            if (server != null) {
                server.execute(() -> {
                    if (!runManager.isGameOver()) {
                        runManager.triggerGameOver();
                    }
                });
            }
        }
    }

}
