package net.zenzty.soullink.mixin.item;

import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    @Inject(
            method =
                    "hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V",
            at = @At("RETURN"))
    private void onDamageWithEntity(int amount, LivingEntity entity, EquipmentSlot slot, CallbackInfo ci) {
        if (!(entity instanceof ServerPlayer player)) {
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
    @Inject(
            method =
                    "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
            at = @At("RETURN"))
    private void onDamageWithWorld(
            int amount, ServerLevel world, ServerPlayer player, Consumer<Item> breakCallback, CallbackInfo ci) {
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
