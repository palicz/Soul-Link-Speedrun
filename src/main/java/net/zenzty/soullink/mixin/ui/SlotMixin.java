package net.zenzty.soullink.mixin.ui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When Synced Inventory is enabled, any UI-driven slot change (e.g. from screen handler slot
 * clicks) is synced to all participants. Right-click to equip armor and similar actions go through
 * Slot.setStack; this hook catches those when the slot's inventory is the player's.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

    @Shadow
    @Final
    public Container container;

    @Inject(method = "setByPlayer(Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
    private void onSetStack(ItemStack stack, CallbackInfo ci) {
        syncIfPlayerInventory();
    }

    @Inject(
            method = "setByPlayer(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("RETURN"))
    private void onSetStackWithPrevious(ItemStack stack, ItemStack previousStack, CallbackInfo ci) {
        syncIfPlayerInventory();
    }

    private void syncIfPlayerInventory() {
        // Prevent re-entrant syncs
        if (SharedInventoryHandler.isSyncing()) {
            return;
        }
        if (container == null || !(container instanceof Inventory playerInv)) {
            return;
        }
        Player p = playerInv.player;
        if (p == null || !(p instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // Additional safety check: don't sync if player is being removed or is invalid
        if (serverPlayer.isRemoved() || !serverPlayer.isAlive()) {
            return;
        }
        SharedInventoryHandler.syncFromPlayerToAll(serverPlayer);
    }
}
