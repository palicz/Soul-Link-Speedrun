package net.zenzty.soullink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.SharedJumpHandler;

/**
 * Mixin to detect when players jump.
 * When shared jumping is enabled, plays a sound to other players.
 */
@Mixin(LivingEntity.class)
public abstract class JumpMixin {

    /**
     * Intercepts the jump method to handle shared jumping.
     */
    @Inject(method = "jump", at = @At("HEAD"))
    private void onJump(CallbackInfo ci) {
        // Only process if this is a ServerPlayerEntity
        if (!((Object) this instanceof ServerPlayerEntity player)) {
            return;
        }

        // Delegate to the handler
        SharedJumpHandler.onPlayerJump(player);
    }
}
