package net.zenzty.soullink.mixin.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for ServerPlayerInteractionManager to sync inventory after block interactions. This catches
 * block placement and other interactions that consume items (like using bone meal) which bypass the
 * normal inventory hooks.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerInteractionManagerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void afterInteractBlock(
            ServerPlayer player,
            Level world,
            ItemStack stack,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (SharedInventoryHandler.isSyncing()) {
            return;
        }
        if (player == null || player.isRemoved() || !player.isAlive()) {
            return;
        }
        // Sync after the interaction to capture any item count changes
        SharedInventoryHandler.syncFromPlayerToAll(player);
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void afterInteractItem(
            ServerPlayer player,
            Level world,
            ItemStack stack,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (SharedInventoryHandler.isSyncing()) {
            return;
        }
        if (player == null || player.isRemoved() || !player.isAlive()) {
            return;
        }
        // Sync after using items (like eating, throwing, etc.)
        SharedInventoryHandler.syncFromPlayerToAll(player);
    }
}
