package net.zenzty.soullink.mixin.ui;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.zenzty.soullink.server.manhunt.SpeedrunnerSelectorGui;
import net.zenzty.soullink.server.settings.SettingsGui;
import net.zenzty.soullink.server.settings.SettingsInfoGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to allow spectators to interact with Soul Link GUIs: chaos settings, info settings, and the
 * Runner/Hunter selector (Manhunt). Normally, spectators cannot click on inventory slots.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SpectatorInteractionMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Intercepts inventory click packets to allow spectators to use Soul Link GUIs. Only bypasses
     * the spectator check; all logic runs in the ScreenHandler. Packet sync must originate from the
     * ScreenHandler to keep revision counters correct.
     */
    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void onClickSlotSpectator(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        // Only process if player is in spectator mode
        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            return;
        }

        // Chaos settings, info settings, or Runner/Hunter selector
        if (player.containerMenu instanceof SettingsGui.SettingsScreenHandler handler) {
            // Basic validation - check if the packet's syncId matches the current handler
            // Most other validation (slot bounds, etc.) is handled inside onSlotClick
            if (packet.containerId() == handler.containerId) {
                handler.clicked(packet.slotNum(), packet.buttonNum(), packet.containerInput(), player);
                ci.cancel();
            }
        } else if (player.containerMenu instanceof SettingsInfoGui.InfoSettingsScreenHandler handler) {
            if (packet.containerId() == handler.containerId) {
                handler.clicked(packet.slotNum(), packet.buttonNum(), packet.containerInput(), player);
                ci.cancel();
            }
        } else if (player.containerMenu instanceof SpeedrunnerSelectorGui.SelectorScreenHandler handler) {
            if (packet.containerId() == handler.containerId) {
                handler.clicked(packet.slotNum(), packet.buttonNum(), packet.containerInput(), player);
                ci.cancel();
            }
        }
    }
}
