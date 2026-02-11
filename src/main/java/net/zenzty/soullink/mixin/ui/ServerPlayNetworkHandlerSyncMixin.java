package net.zenzty.soullink.mixin.ui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.network.packet.c2s.play.SlotChangedStateC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;

/**
 * When the client sends SlotChangedStateC2SPacket (e.g. F-key offhand swap when not in inventory),
 * the server handles it and updates the player's inventory. We sync after the handler returns so
 * all participants see the change.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerSyncMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onSlotChangedState", at = @At("RETURN"))
    private void afterSlotChangedState(SlotChangedStateC2SPacket packet, CallbackInfo ci) {
        if (SharedInventoryHandler.isSyncing())
            return;
        if (player == null || player.isRemoved() || !player.isAlive())
            return;
        try {
            SharedInventoryHandler.syncFromPlayerToAll(player);
        } catch (Exception e) {
            // Avoid breaking vanilla
        }
    }
}
