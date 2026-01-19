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
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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

                // Hunters use custom death logic (spectator -> countdown -> respawn)
                if (manhuntManager.isHunter(player)) {
                        SoulLink.LOGGER.info("Hunter {} died - triggering custom respawn logic",
                                        player.getName().getString());

                        // Cancel vanilla death
                        ci.cancel();

                        // CRITICAL: Restore health immediately to prevent death screen
                        // The death screen is triggered client-side when health is 0,
                        // even if onDeath is cancelled.
                        player.setHealth(player.getMaxHealth());

                        // Trigger custom death handler
                        net.zenzty.soullink.server.event.EventRegistry.handleHunterDeath(player,
                                        damageSource, runManager);
                        return;
                }

                // Runners: prevent death and trigger game over
                SoulLink.LOGGER.info("Runner {} died - triggering game over",
                                player.getName().getString());

                // Cancel the death event to prevent death screen
                ci.cancel();

                // Broadcast death message to all players (use vanilla death message format)
                Text deathMessage = damageSource.getDeathMessage(player);
                Text formattedDeathMessage = Text.empty().append(RunManager.getPrefix())
                                .append(Text.literal("☠ ").formatted(Formatting.DARK_RED))
                                .append(deathMessage.copy().formatted(Formatting.RED));
                runManager.getServer().getPlayerManager().broadcast(formattedDeathMessage, false);

                // Restore health so player doesn't look dead
                player.setHealth(player.getMaxHealth());

                // Clear lingering harmful effects and extinguish fire
                List<RegistryEntry<StatusEffect>> effectsToRemove = player.getStatusEffects()
                                .stream()
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
