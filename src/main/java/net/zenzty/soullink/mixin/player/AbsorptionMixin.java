package net.zenzty.soullink.mixin.player;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.run.RunManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept absorption amount changes for syncing between players. When a player's
 * absorption changes (from golden apples, etc.), it gets synced to the shared health pool.
 */
@Mixin(LivingEntity.class)
public abstract class AbsorptionMixin {

    /**
     * Intercepts setAbsorptionAmount to sync absorption changes to all players.
     */
    @Inject(method = "setAbsorptionAmount", at = @At("TAIL"))
    private void onSetAbsorptionAmount(float amount, CallbackInfo ci) {
        // Only process if this is a ServerPlayerEntity
        if (!((Object) this instanceof ServerPlayer player)) {
            return;
        }

        // Only sync if run is active and not already syncing
        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive() || SharedStatsHandler.isSyncing()) {
            return;
        }

        // Sync the absorption change
        SharedStatsHandler.onAbsorptionChanged(player, amount);
    }
}
