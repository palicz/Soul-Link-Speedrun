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
import net.zenzty.soullink.server.event.EventRegistry;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;

/**
 * Mixin for ServerPlayerEntity: Runners (and non-Manhunt) trigger game over on death. Hunters use
 * custom respawn (spectator, drop items, 5s countdown, respawn).
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

        @Inject(method = "onDeath", at = @At("HEAD"), cancellable = true)
        private void onDeathHandler(DamageSource damageSource, CallbackInfo ci) {
                ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
                RunManager runManager = RunManager.getInstance();

                if (runManager == null || !runManager.isRunActive()) {
                        return;
                }

                if (Settings.getInstance().isManhuntMode()
                                && ManhuntManager.getInstance().isHunter(player)) {
                        SoulLink.LOGGER.info("Hunter {} died - triggering custom respawn",
                                        player.getName().getString());
                        ci.cancel();
                        player.setHealth(player.getMaxHealth());
                        EventRegistry.handleHunterDeath(player, damageSource, runManager);
                        return;
                }

                SoulLink.LOGGER.info("Player {} died during active run - triggering game over",
                                player.getName().getString());

                ci.cancel();

                Text deathMessage = damageSource.getDeathMessage(player);
                Text formattedDeathMessage = Text.empty().append(RunManager.getPrefix())
                                .append(Text.literal("☠ ").formatted(Formatting.DARK_RED))
                                .append(deathMessage.copy().formatted(Formatting.RED));
                runManager.getServer().getPlayerManager().broadcast(formattedDeathMessage, false);

                player.setHealth(player.getMaxHealth());

                List<RegistryEntry<StatusEffect>> effectsToRemove = player.getStatusEffects()
                                .stream()
                                .filter(effect -> !effect.getEffectType().value().isBeneficial())
                                .map(StatusEffectInstance::getEffectType).toList();

                effectsToRemove.forEach(player::removeStatusEffect);
                player.extinguish();

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
