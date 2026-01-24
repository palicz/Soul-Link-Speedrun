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
import net.zenzty.soullink.server.manhunt.SpeedrunnerSelectorGui;
import net.zenzty.soullink.server.settings.SettingsGui;
import net.zenzty.soullink.server.settings.SettingsInfoGui;

/**
 * Mixin to allow spectators to interact with Soul Link GUIs: chaos settings, info settings, and the
 * Runner/Hunter selector (Manhunt). Normally, spectators cannot click on inventory slots.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SpectatorInteractionMixin {

    @Shadow
    public ServerPlayerEntity player;

    /**
     * Intercepts inventory click packets to allow spectators to use Soul Link GUIs. Only bypasses
     * the spectator check; all logic runs in the ScreenHandler. Packet sync must originate from the
     * ScreenHandler to keep revision counters correct.
     */
    @Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
    private void onClickSlotSpectator(ClickSlotC2SPacket packet, CallbackInfo ci) {
        // Only process if player is in spectator mode
        if (player.interactionManager.getGameMode() != GameMode.SPECTATOR) {
            return;
        }

        // Chaos settings, info settings, or Runner/Hunter selector
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
        } else if (player.currentScreenHandler instanceof SpeedrunnerSelectorGui.SelectorScreenHandler handler) {
            if (packet.syncId() == handler.syncId) {
                handler.onSlotClick(packet.slot(), packet.button(), packet.actionType(), player);
                ci.cancel();
            }
        }
    }
}
