package net.zenzty.soullink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.zenzty.soullink.RunManager;
import net.zenzty.soullink.SharedStatsHandler;

/**
 * Mixin for HungerManager to sync hunger changes between players.
 */
@Mixin(HungerManager.class)
public abstract class HungerManagerMixin {

    @Shadow
    private int foodLevel;

    @Shadow
    private float saturationLevel;

    @Unique
    private int previousFoodLevel = 20;

    @Unique
    private float previousSaturation = 5.0f;

    /**
     * After each hunger update tick, check if values changed and sync. Note: In 1.21.11,
     * HungerManager.update() takes ServerPlayerEntity directly.
     */
    @Inject(method = "update", at = @At("TAIL"))
    private void afterHungerUpdate(ServerPlayerEntity player, CallbackInfo ci) {
        // Skip if syncing to prevent loops
        if (SharedStatsHandler.isSyncing()) {
            previousFoodLevel = this.foodLevel;
            previousSaturation = this.saturationLevel;
            return;
        }

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        // Get the player's world - ServerPlayerEntity.getEntityWorld() returns ServerWorld directly
        ServerWorld serverWorld = player.getEntityWorld();

        if (!runManager.isTemporaryWorld(serverWorld.getRegistryKey())) {
            return;
        }

        // Check if hunger/saturation changed
        if (this.foodLevel != previousFoodLevel
                || Math.abs(this.saturationLevel - previousSaturation) > 0.01f) {

            SharedStatsHandler.onPlayerHungerChanged(player, this.foodLevel, this.saturationLevel);

            previousFoodLevel = this.foodLevel;
            previousSaturation = this.saturationLevel;
        }
    }

    /**
     * Track when food level is directly set.
     */
    @Inject(method = "setFoodLevel", at = @At("TAIL"))
    private void afterSetFoodLevel(int foodLevel, CallbackInfo ci) {
        previousFoodLevel = this.foodLevel;
    }

    /**
     * Track when saturation is directly set.
     */
    @Inject(method = "setSaturationLevel", at = @At("TAIL"))
    private void afterSetSaturation(float saturationLevel, CallbackInfo ci) {
        previousSaturation = this.saturationLevel;
    }
}
