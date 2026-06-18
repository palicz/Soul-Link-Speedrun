package net.zenzty.soullink.mixin.player;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.zenzty.soullink.server.health.SharedJumpHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to detect when players jump. When shared jumping is enabled, registers the jump for
 * synchronized force-jumping of other players at tick end.
 */
@Mixin(LivingEntity.class)
public abstract class JumpMixin {

    /**
     * Intercepts the jump method to handle shared jumping.
     */
    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void onJump(CallbackInfo ci) {
        // Only process if this is a ServerPlayerEntity
        if (!((Object) this instanceof ServerPlayer player)) {
            return;
        }

        // Delegate to the handler
        SharedJumpHandler.onPlayerJump(player);
    }
}
