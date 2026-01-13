package net.zenzty.soullink;

import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Handles shared jumping functionality.
 * When a player jumps, forces all other players who didn't jump
 * in the same tick to also jump forward.
 */
public class SharedJumpHandler {

    // Track players who jumped in the current tick
    private static final Set<UUID> jumpersThisTick = new HashSet<>();
    
    // Track the last tick we processed
    private static int lastProcessedTick = -1;
    
    // Force jump strength
    private static final double UPWARD_FORCE = 0.50;  // Upward push strength (vanilla jump is ~0.42)

    /**
     * Called when a player jumps.
     * Registers them as a jumper for this tick.
     */
    public static void onPlayerJump(ServerPlayerEntity player) {
        Settings settings = Settings.getInstance();
        if (!settings.isSharedJumping()) {
            return;
        }

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        if (!runManager.isTemporaryWorld(player.getEntityWorld().getRegistryKey())) {
            return;
        }

        MinecraftServer server = runManager.getServer();
        if (server == null) return;

        int currentTick = server.getTicks();

        // If this is a new tick, clear the previous jumpers and process
        if (currentTick != lastProcessedTick) {
            jumpersThisTick.clear();
            lastProcessedTick = currentTick;
        }

        // Add this player to the jumpers set
        jumpersThisTick.add(player.getUuid());

        // Force push all non-jumping players
        forceJumpNonJumpers(player, server, runManager);

        // Log for debugging
        SoulLink.LOGGER.debug("[Shared Jump] {} jumped", player.getName().getString());
    }

    /**
     * Forces all players who are not jumping this tick to jump forward.
     */
    private static void forceJumpNonJumpers(ServerPlayerEntity jumper, MinecraftServer server, RunManager runManager) {
        for (ServerPlayerEntity otherPlayer : server.getPlayerManager().getPlayerList()) {
            // Skip the jumper
            if (otherPlayer == jumper) continue;

            // Skip players not in the run
            ServerWorld otherWorld = otherPlayer.getEntityWorld();
            if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey())) continue;

            // Skip players who also jumped this tick
            if (jumpersThisTick.contains(otherPlayer.getUuid())) continue;

            // Apply force jump to this player
            applyForceJump(otherPlayer);
        }
    }

    /**
     * Applies a jump force to a player (upward only).
     */
    private static void applyForceJump(ServerPlayerEntity player) {
        // Only apply if player is on the ground (prevent air stacking)
        if (!player.isOnGround()) {
            return;
        }
        
        // Add upward velocity (like a jump)
        player.addVelocity(0, UPWARD_FORCE, 0);
        
        // Send velocity update packet to sync with client
        player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
    }

    /**
     * Resets state for a new run.
     */
    public static void reset() {
        jumpersThisTick.clear();
        lastProcessedTick = -1;
    }
}
