package net.zenzty.soullink.mixin.ui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import net.zenzty.soullink.server.settings.SettingsGui;
import net.zenzty.soullink.server.settings.SettingsInfoGui;

/**
 * Mixin to allow spectators to interact with Soul Link GUIs (chaos settings and info settings).
 * Normally, spectators cannot click on inventory slots.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpectatorInteractionMixin {

    @Shadow
    public ServerPlayerEntity player;

    /**
     * Intercepts inventory click packets to allow spectators to use the settings GUI. This mixin
     * only bypasses the spectator check and delegates all logic to the ScreenHandler. All packet
     * synchronization must originate from the ScreenHandler to maintain revision counter integrity.
     */
    @Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
    private void onClickSlotSpectator(ClickSlotC2SPacket packet, CallbackInfo ci) {
        // Only process if player is in spectator mode
        if (player.interactionManager.getGameMode() != GameMode.SPECTATOR) {
            return;
        }

        // Check if player is using our chaos settings GUI or info settings GUI
        if (player.currentScreenHandler instanceof SettingsGui.SettingsScreenHandler handler) {
            // Basic validation - check if the packet's syncId matches the current handler
            // Most other validation (slot bounds, etc.) is handled inside onSlotClick
            if (packet.syncId() == handler.syncId) {
                handler.onSlotClick(packet.slot(), packet.button(), packet.actionType(), player);
                ci.cancel();
            }
        } else if (player.currentScreenHandler instanceof SettingsInfoGui.InfoSettingsScreenHandler handler) {
            if (packet.syncId() == handler.syncId) {
                handler.onSlotClick(packet.slot(), packet.button(), packet.actionType(), player);
                ci.cancel();
            }
        }
    }
}
