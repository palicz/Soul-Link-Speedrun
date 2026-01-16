package net.zenzty.soullink.mixin.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;

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

        // Sync the absorption change
        SharedStatsHandler.onAbsorptionChanged(player, amount);
    }
}
