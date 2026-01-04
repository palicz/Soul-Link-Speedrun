package net.zenzty.soullink.mixin;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.RunManager;
import net.zenzty.soullink.SoulLink;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for ServerPlayerEntity to prevent death during active runs.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    
    /**
     * Final safety net - cancel any death during an active run.
     * This prevents the death screen from ever appearing.
     */
    @Inject(method = "onDeath", at = @At("HEAD"), cancellable = true)
    private void preventDeathDuringRun(DamageSource damageSource, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        RunManager runManager = RunManager.getInstance();
        
        // Only intercept death during active runs
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }
        
        SoulLink.LOGGER.warn("onDeath called during active run for {} - this shouldn't happen! Cancelling death.", 
            player.getName().getString());
        
        // Cancel the death event to prevent death screen
        ci.cancel();
        
        // Restore health
        player.setHealth(player.getMaxHealth());
        
        // Trigger game over if not already in that state
        if (!runManager.isGameOver()) {
            runManager.triggerGameOver();
        }
    }
}
