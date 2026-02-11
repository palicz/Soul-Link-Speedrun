package net.zenzty.soullink.mixin.ui;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;

/**
 * When Synced Inventory is enabled, any UI-driven slot change (e.g. from screen handler slot
 * clicks) is synced to all participants. Right-click to equip armor and similar actions go through
 * Slot.setStack; this hook catches those when the slot's inventory is the player's.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

    @Shadow
    @Final
    public Inventory inventory;

    @Inject(method = "setStack(Lnet/minecraft/item/ItemStack;)V", at = @At("RETURN"))
    private void onSetStack(ItemStack stack, CallbackInfo ci) {
        syncIfPlayerInventory();
    }

    @Inject(method = "setStack(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)V",
            at = @At("RETURN"))
    private void onSetStackWithPrevious(ItemStack stack, ItemStack previousStack, CallbackInfo ci) {
        syncIfPlayerInventory();
    }

    private void syncIfPlayerInventory() {
        // Prevent re-entrant syncs
        if (SharedInventoryHandler.isSyncing()) {
            return;
        }
        if (inventory == null || !(inventory instanceof PlayerInventory playerInv)) {
            return;
        }
        PlayerEntity p = playerInv.player;
        if (p == null || !(p instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        // Additional safety check: don't sync if player is being removed or is invalid
        if (serverPlayer.isRemoved() || !serverPlayer.isAlive()) {
            return;
        }
        SharedInventoryHandler.syncFromPlayerToAll(serverPlayer);
    }
}
