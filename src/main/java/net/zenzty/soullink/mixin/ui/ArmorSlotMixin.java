package net.zenzty.soullink.mixin.ui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;

/**
 * Right-click to equip armor uses ArmorSlot.setStack(ItemStack, ItemStack), which overrides Slot's
 * implementation. This mixin ensures that path triggers shared inventory sync. Target by name so we
 * don't depend on ArmorSlot being public.
 */
@Mixin(targets = "net.minecraft.screen.slot.ArmorSlot")
public abstract class ArmorSlotMixin extends Slot {

    // Dummy constructor required by mixin when extending Slot
    private ArmorSlotMixin() {
        super(null, 0, 0, 0);
    }

    @Inject(method = "setStack(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)V",
            at = @At("RETURN"))
    private void onSetStack(ItemStack stack, ItemStack previousStack, CallbackInfo ci) {
        try {
            if (SharedInventoryHandler.isSyncing())
                return;
            // Access inventory from parent Slot class
            var inv = this.inventory;
            if (inv == null || !(inv instanceof PlayerInventory playerInv)) {
                return;
            }
            PlayerEntity p = playerInv.player;
            if (p == null || !(p instanceof ServerPlayerEntity serverPlayer)) {
                return;
            }
            if (serverPlayer.isRemoved() || !serverPlayer.isAlive())
                return;
            SharedInventoryHandler.syncFromPlayerToAll(serverPlayer);
        } catch (Exception e) {
            // Silently fail to avoid breaking vanilla behavior
            SoulLink.LOGGER.debug("Error in ArmorSlotMixin sync: {}", e.getMessage());
        }
    }
}
