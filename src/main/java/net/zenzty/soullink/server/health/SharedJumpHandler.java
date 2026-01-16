package net.zenzty.soullink.server.health;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;

/**
 * Handles shared jumping functionality. When a player jumps, forces all other players who didn't
 * jump in the same tick to also jump forward.
 * 
 * Uses deferred processing at tick end to prevent race conditions where multiple players jump in
 * the same tick, which could cause double velocity due to latency.
 */
public class SharedJumpHandler {

    // Track players who jumped naturally in the current tick
    private static final Set<UUID> jumpersThisTick = new HashSet<>();

    // Track players who have been forced to jump this tick (to prevent double velocity)
    private static final Set<UUID> forcedJumpersThisTick = new HashSet<>();

    // Track the last tick we collected jumpers for
    private static int lastCollectedTick = -1;

    // Track the last tick we processed jumps for
    private static int lastProcessedTick = -1;

    // Flag to prevent processing jumps multiple times per tick
    private static boolean processingJumps = false;



    /**
     * Called when a player jumps naturally. Registers them as a jumper for this tick, but defers
     * forcing others until the end of the tick to prevent race conditions.
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

        // Hunters are excluded from shared mechanics
        if (ManhuntManager.getInstance().isHunter(player)) {
            return;
        }

        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        int currentTick = server.getTicks();

        // If this is a new tick, clear the sets only if we've already processed the previous tick
        // This prevents clearing sets before processing if a new tick starts early
        if (currentTick != lastCollectedTick) {
            // Only clear if we've already processed the previous tick's jumps
            // or if this is the first tick we're tracking
            if (lastProcessedTick >= lastCollectedTick || lastCollectedTick == -1) {
                jumpersThisTick.clear();
                forcedJumpersThisTick.clear();
            }
            lastCollectedTick = currentTick;
        }

        // Add this player to the jumpers set (only if not already processing)
        // This prevents double-counting if somehow called during processing
        if (!processingJumps) {
            jumpersThisTick.add(player.getUuid());
        }

        // Log for debugging
        SoulLink.LOGGER.debug("[Shared Jump] {} jumped (tick {})", player.getName().getString(),
                currentTick);
    }

    /**
     * Processes all jumps at the end of a tick. Forces all players who didn't jump naturally to
     * jump. This is called at tick end to prevent race conditions.
     */
    public static void processJumpsAtTickEnd(MinecraftServer server) {
        Settings settings = Settings.getInstance();
        if (!settings.isSharedJumping()) {
            return;
        }

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        // If no one jumped in the last processed tick, nothing to do
        if (jumpersThisTick.isEmpty()) {
            return;
        }

        // Prevent recursive calls
        if (processingJumps) {
            return;
        }

        // Process jumps for the tick that just ended (lastProcessedTick)
        // END_SERVER_TICK may fire when the tick has already advanced, so we process
        // based on lastProcessedTick rather than current tick
        processingJumps = true;
        try {
            // Force all non-jumping players to jump
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                // Skip players not in the run
                ServerWorld playerWorld = player.getEntityWorld();
                if (playerWorld == null
                        || !runManager.isTemporaryWorld(playerWorld.getRegistryKey())) {
                    continue;
                }

                // Skip players who already jumped naturally this tick
                if (jumpersThisTick.contains(player.getUuid()))
                    continue;

                // Hunters are excluded from shared jumping
                if (ManhuntManager.getInstance().isHunter(player))
                    continue;

                // Skip players who have already been forced to jump this tick
                if (forcedJumpersThisTick.contains(player.getUuid()))
                    continue;

                // Apply force jump to this player
                applyForceJump(player);
                forcedJumpersThisTick.add(player.getUuid());
            }

            SoulLink.LOGGER.debug(
                    "[Shared Jump] Processed {} natural jumpers at tick end (tick {})",
                    jumpersThisTick.size(), lastCollectedTick);

            // Mark this tick as processed
            lastProcessedTick = lastCollectedTick;

            // Clear the sets after processing to prepare for the next tick
            // This ensures we don't process the same jumps twice
            jumpersThisTick.clear();
            forcedJumpersThisTick.clear();
        } finally {
            processingJumps = false;
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

        // Invoke vanilla jump path to trigger stats, exhaustion, and sounds
        player.jump();

        // If server->client velocity sync is still required, follow it with the packet send
        // Note: player.jump() usually handles velocity, but server-side jump might need explicit
        // sync for some entities

        // Send velocity update packet to sync with client
        player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));

        SoulLink.LOGGER.debug("[Shared Jump] Forced jump applied to {}",
                player.getName().getString());
    }

    /**
     * Resets state for a new run.
     */
    public static void reset() {
        jumpersThisTick.clear();
        forcedJumpersThisTick.clear();
        lastCollectedTick = -1;
        lastProcessedTick = -1;
        processingJumps = false;
    }
}
