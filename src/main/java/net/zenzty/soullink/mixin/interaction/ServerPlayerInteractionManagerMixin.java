package net.zenzty.soullink.mixin.interaction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;

/**
 * Mixin for ServerPlayerInteractionManager to sync inventory after block interactions. This catches
 * block placement and other interactions that consume items (like using bone meal) which bypass the
 * normal inventory hooks.
 */
@Mixin(ServerPlayerInteractionManager.class)
public abstract class ServerPlayerInteractionManagerMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void afterInteractBlock(ServerPlayerEntity player, World world, ItemStack stack,
            Hand hand, BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
        if (SharedInventoryHandler.isSyncing()) {
            return;
        }
        if (player == null || player.isRemoved() || !player.isAlive()) {
            return;
        }
        // Sync after the interaction to capture any item count changes
        SharedInventoryHandler.syncFromPlayerToAll(player);
    }

    @Inject(method = "interactItem", at = @At("RETURN"))
    private void afterInteractItem(ServerPlayerEntity player, World world, ItemStack stack,
            Hand hand, CallbackInfoReturnable<ActionResult> cir) {
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
