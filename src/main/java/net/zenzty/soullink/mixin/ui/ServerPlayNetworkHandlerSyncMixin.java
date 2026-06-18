package net.zenzty.soullink.mixin.ui;

import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the client sends SlotChangedStateC2SPacket (e.g. F-key offhand swap when not in inventory),
 * the server handles it and updates the player's inventory. We sync after the handler returns so
 * all participants see the change.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerSyncMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleContainerSlotStateChanged", at = @At("RETURN"))
    private void afterSlotChangedState(ServerboundContainerSlotStateChangedPacket packet, CallbackInfo ci) {
        if (SharedInventoryHandler.isSyncing()) return;
        if (player == null || player.isRemoved() || !player.isAlive()) return;
        try {
            SharedInventoryHandler.syncFromPlayerToAll(player);
        } catch (Exception e) {
            // Avoid breaking vanilla
        }
    }
}
