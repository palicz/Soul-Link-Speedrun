package net.zenzty.soullink.mixin.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;

/**
 * When Synced Inventory mode is enabled, any change to a player's inventory is propagated to all
 * other participants. Hooks: setStack, insertStack (pickup/merge), swapSlotWithHotbar (hotbar slot
 * swap), swapStackWithHotbar (F-key offhand swap), removeStack/removeOne (block place / consume),
 * and markDirty (catch-all).
 */
@Mixin(PlayerInventory.class)
public abstract class PlayerInventoryMixin {

    @Shadow
    public PlayerEntity player;

    @Inject(method = "setStack", at = @At("RETURN"))
    private void onSetStack(int slot, ItemStack stack, CallbackInfo ci) {
        syncIfServerPlayer();
    }

    @Inject(method = "insertStack(Lnet/minecraft/item/ItemStack;)Z", at = @At("RETURN"))
    private void onInsertStack(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        syncIfServerPlayer();
    }

    @Inject(method = "insertStack(ILnet/minecraft/item/ItemStack;)Z", at = @At("RETURN"))
    private void onInsertStackSlot(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        syncIfServerPlayer();
    }

    @Inject(method = "swapSlotWithHotbar", at = @At("RETURN"))
    private void onSwapSlotWithHotbar(int slot, CallbackInfo ci) {
        syncIfServerPlayer();
    }

    @Inject(method = "swapStackWithHotbar", at = @At("RETURN"))
    private void onSwapStackWithHotbar(ItemStack stack, CallbackInfo ci) {
        syncIfServerPlayer();
    }

    @Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At("RETURN"))
    private void onRemoveStack(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        syncIfServerPlayer();
    }

    @Inject(method = "removeStack(I)Lnet/minecraft/item/ItemStack;", at = @At("RETURN"))
    private void onRemoveStackSlot(int slot, CallbackInfoReturnable<ItemStack> cir) {
        syncIfServerPlayer();
    }

    @Inject(method = "removeOne", at = @At("RETURN"))
    private void onRemoveOne(ItemStack stack, CallbackInfo ci) {
        syncIfServerPlayer();
    }

    @Inject(method = "markDirty", at = @At("RETURN"))
    private void onMarkDirty(CallbackInfo ci) {
        syncIfServerPlayer();
    }

    private void syncIfServerPlayer() {
        // Prevent re-entrant syncs (if we're already syncing, don't trigger another)
        if (SharedInventoryHandler.isSyncing()) {
            return;
        }
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        // Additional safety check: don't sync if player is being removed or is invalid
        if (serverPlayer.isRemoved() || !serverPlayer.isAlive()) {
            return;
        }
        SharedInventoryHandler.syncFromPlayerToAll(serverPlayer);
    }
}
