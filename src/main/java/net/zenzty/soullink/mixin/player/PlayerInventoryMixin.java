package net.zenzty.soullink.mixin.player;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When Synced Inventory mode is enabled, any change to a player's inventory is propagated to all
 * other participants. Hooks: setStack, insertStack (pickup/merge), swapSlotWithHotbar (hotbar slot
 * swap), swapStackWithHotbar (F-key offhand swap), removeStack/removeOne (block place / consume),
 * and markDirty (catch-all).
 */
@Mixin(Inventory.class)
public abstract class PlayerInventoryMixin {

    @Shadow
    public Player player;

    @Inject(method = "setItem", at = @At("RETURN"))
    private void onSetStack(int slot, ItemStack stack, CallbackInfo ci) {
        syncIfServerPlayer();
    }

    @Inject(method = "add(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("RETURN"))
    private void onInsertStack(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        syncIfServerPlayer();
    }

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("RETURN"))
    private void onInsertStackSlot(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        syncIfServerPlayer();
    }

    @Inject(method = "pickSlot", at = @At("RETURN"))
    private void onSwapSlotWithHotbar(int slot, CallbackInfo ci) {
        syncIfServerPlayer();
    }

    @Inject(method = "addAndPickItem", at = @At("RETURN"))
    private void onSwapStackWithHotbar(ItemStack stack, CallbackInfo ci) {
        syncIfServerPlayer();
    }

    @Inject(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
    private void onRemoveStack(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        syncIfServerPlayer();
    }

    @Inject(method = "removeItemNoUpdate(I)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
    private void onRemoveStackSlot(int slot, CallbackInfoReturnable<ItemStack> cir) {
        syncIfServerPlayer();
    }

    @Inject(method = "removeItem(Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
    private void onRemoveOne(ItemStack stack, CallbackInfo ci) {
        syncIfServerPlayer();
    }

    @Inject(method = "setChanged", at = @At("RETURN"))
    private void onMarkDirty(CallbackInfo ci) {
        syncIfServerPlayer();
    }

    private void syncIfServerPlayer() {
        // Prevent re-entrant syncs (if we're already syncing, don't trigger another)
        if (SharedInventoryHandler.isSyncing()) {
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // Additional safety check: don't sync if player is being removed or is invalid
        if (serverPlayer.isRemoved() || !serverPlayer.isAlive()) {
            return;
        }
        SharedInventoryHandler.syncFromPlayerToAll(serverPlayer);
    }
}
