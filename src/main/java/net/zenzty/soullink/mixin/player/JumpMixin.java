package net.zenzty.soullink.mixin.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.server.health.SharedJumpHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;

/**
 * Mixin to detect when players jump. When shared jumping is enabled, registers the jump for
 * synchronized force-jumping of other players at tick end.
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

        // Hunters are excluded from shared jumping
        if (ManhuntManager.getInstance().isHunter(player)) {
            return;
        }

        // Delegate to the handler
        SharedJumpHandler.onPlayerJump(player);
    }
}
