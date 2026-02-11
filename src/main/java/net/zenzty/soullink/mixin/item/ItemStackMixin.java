package net.zenzty.soullink.mixin.item;

import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;

/**
 * Mixin for ItemStack to sync inventory when tool durability changes. This catches all durability
 * damage from mining, attacking, etc.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    /**
     * Hook the main damage method used when tools take durability damage from mining or combat. The
     * LivingEntity parameter tells us which entity is using the item.
     */
    @Inject(method = "damage(ILnet/minecraft/entity/LivingEntity;Lnet/minecraft/entity/EquipmentSlot;)V",
            at = @At("RETURN"))
    private void onDamageWithEntity(int amount, LivingEntity entity, EquipmentSlot slot,
            CallbackInfo ci) {
        if (!(entity instanceof ServerPlayerEntity player)) {
            return;
        }
        if (SharedInventoryHandler.isSyncing()) {
            return;
        }
        if (player.isRemoved() || !player.isAlive()) {
            return;
        }
        SharedInventoryHandler.syncFromPlayerToAll(player);
    }

    /**
     * Hook the damage method with ServerWorld/ServerPlayerEntity parameters. This is called in some
     * durability damage scenarios.
     */
    @Inject(method = "damage(ILnet/minecraft/server/world/ServerWorld;Lnet/minecraft/server/network/ServerPlayerEntity;Ljava/util/function/Consumer;)V",
            at = @At("RETURN"))
    private void onDamageWithWorld(int amount, ServerWorld world, ServerPlayerEntity player,
            Consumer<Item> breakCallback, CallbackInfo ci) {
        if (player == null) {
            return;
        }
        if (SharedInventoryHandler.isSyncing()) {
            return;
        }
        if (player.isRemoved() || !player.isAlive()) {
            return;
        }
        SharedInventoryHandler.syncFromPlayerToAll(player);
    }
}
