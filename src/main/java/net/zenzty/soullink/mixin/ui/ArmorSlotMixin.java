package net.zenzty.soullink.mixin.ui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right-click to equip armor uses ArmorSlot.setStack(ItemStack, ItemStack), which overrides Slot's
 * implementation. This mixin ensures that path triggers shared inventory sync. Target by name so we
 * don't depend on ArmorSlot being public.
 */
@Mixin(targets = "net.minecraft.world.inventory.ArmorSlot")
public abstract class ArmorSlotMixin extends Slot {

    // Dummy constructor required by mixin when extending Slot
    private ArmorSlotMixin() {
        super(null, 0, 0, 0);
    }

    @Inject(
            method = "setByPlayer(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("RETURN"))
    private void onSetStack(ItemStack stack, ItemStack previousStack, CallbackInfo ci) {
        try {
            if (SharedInventoryHandler.isSyncing()) return;
            // Access inventory from parent Slot class
            var inv = this.container;
            if (inv == null || !(inv instanceof Inventory playerInv)) {
                return;
            }
            Player p = playerInv.player;
            if (p == null || !(p instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (serverPlayer.isRemoved() || !serverPlayer.isAlive()) return;
            SharedInventoryHandler.syncFromPlayerToAll(serverPlayer);
        } catch (Exception e) {
            // Silently fail to avoid breaking vanilla behavior
            SoulLink.LOGGER.debug("Error in ArmorSlotMixin sync: {}", e.getMessage());
        }
    }
}
