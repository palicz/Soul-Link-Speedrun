package net.zenzty.soullink;

import java.util.List;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;

/**
 * Handles shared health, hunger, and saturation between all players. Implements the "Soul Link"
 * mechanic where all players share the same vital stats.
 */
public class SharedStatsHandler {

    // Master stat values
    private static float sharedHealth = 20.0f;
    private static int sharedHunger = 20;
    private static float sharedSaturation = 5.0f;

    // Prevent infinite sync loops
    private static boolean isSyncing = false;

    /**
     * Resets all shared stats to default values. Called when starting a new run.
     */
    public static void reset() {
        sharedHealth = 20.0f;
        sharedHunger = 20;
        sharedSaturation = 5.0f;
        isSyncing = false;
        SoulLink.LOGGER.info("Shared stats reset to defaults");
    }

    /**
     * Syncs a player's stats to the current shared values. Used for late joiners and reconnecting
     * players.
     */
    public static void syncPlayerToSharedStats(ServerPlayerEntity player) {
        if (isSyncing)
            return;

        isSyncing = true;
        try {
            player.setHealth(sharedHealth);
            player.getHungerManager().setFoodLevel(sharedHunger);
            player.getHungerManager().setSaturationLevel(sharedSaturation);
            SoulLink.LOGGER.debug("Synced {} to shared stats: HP={}, Food={}, Sat={}",
                    player.getName().getString(), sharedHealth, sharedHunger, sharedSaturation);
        } finally {
            isSyncing = false;
        }
    }

    /**
     * Gets the ServerWorld for a player. In Yarn 1.21.11, ServerPlayerEntity.getEntityWorld()
     * returns ServerWorld directly.
     */
    private static ServerWorld getPlayerWorld(ServerPlayerEntity player) {
        return player.getEntityWorld();
    }

    /**
     * Called when a player's health changes after taking damage. Updates the master health and
     * syncs to all other players with visual feedback.
     * 
     * @param damagedPlayer The player who took damage
     * @param newHealth The player's health AFTER damage was applied (armor already calculated)
     * @param damageSource The source of the damage
     */
    public static void onPlayerHealthChanged(ServerPlayerEntity damagedPlayer, float newHealth,
            DamageSource damageSource) {
        if (isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(damagedPlayer);
        if (playerWorld == null)
            return;

        // Only process if in a temporary world
        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        isSyncing = true;
        try {
            float oldHealth = sharedHealth;

            // Update the master health to match the damaged player's health
            sharedHealth = MathHelper.clamp(newHealth, 0.0f, 20.0f);

            // Check for death condition
            if (sharedHealth <= 0) {
                SoulLink.LOGGER.info("Shared health depleted - triggering game over");
                runManager.triggerGameOver();
                return;
            }

            // Only sync to other players if health actually decreased
            if (sharedHealth < oldHealth) {
                MinecraftServer server = runManager.getServer();
                if (server == null)
                    return;

                float damageAmount = oldHealth - sharedHealth;
                List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();

                // Broadcast damage notification to all players
                // Convert from half-hearts to full hearts for display (Minecraft stores health as
                // 0-20, where 1 heart = 2)
                float damageInHearts = damageAmount / 2.0f;
                String damageText = String.format(java.util.Locale.US, "%.1f", damageInHearts);
                net.minecraft.text.Text damageNotification = net.minecraft.text.Text.empty()
                        .append(RunManager.getPrefix())
                        .append(net.minecraft.text.Text.literal(damagedPlayer.getName().getString())
                                .formatted(net.minecraft.util.Formatting.WHITE))
                        .append(net.minecraft.text.Text.literal(" has taken ")
                                .formatted(net.minecraft.util.Formatting.GRAY))
                        .append(net.minecraft.text.Text.literal(damageText + " ❤")
                                .formatted(net.minecraft.util.Formatting.RED))
                        .append(net.minecraft.text.Text.literal(" damage.")
                                .formatted(net.minecraft.util.Formatting.GRAY));
                server.getPlayerManager().broadcast(damageNotification, false);

                for (ServerPlayerEntity player : players) {
                    if (player == damagedPlayer)
                        continue;

                    ServerWorld otherWorld = getPlayerWorld(player);
                    if (otherWorld == null)
                        continue;

                    // Skip players not in the run
                    if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                        continue;

                    // Apply actual damage to trigger all client-side effects (red flash, screen
                    // shake, sound)
                    // Use the world's damage sources for correct API usage
                    DamageSource syncDamage = otherWorld.getDamageSources().generic();

                    // Apply damage using the world-aware damage method
                    // The isSyncing flag prevents onPlayerHealthChanged from recursing
                    player.damage(otherWorld, syncDamage, damageAmount);

                    // Ensure health is exactly what we expect (in case of any rounding)
                    player.setHealth(sharedHealth);
                }

                SoulLink.LOGGER.debug("Health synced: {} -> {} (from {})", oldHealth, sharedHealth,
                        damagedPlayer.getName().getString());
            }

        } finally {
            isSyncing = false;
        }
    }

    /**
     * Called when a player heals (regeneration, eating golden apple, etc.) Updates the master
     * health and syncs to all players.
     */
    public static void onPlayerHealed(ServerPlayerEntity healedPlayer, float newHealth) {
        if (isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(healedPlayer);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        isSyncing = true;
        try {
            float oldHealth = sharedHealth;
            sharedHealth = MathHelper.clamp(newHealth, 0.0f, 20.0f);

            // Only sync if health increased
            if (sharedHealth > oldHealth) {
                MinecraftServer server = runManager.getServer();
                if (server == null)
                    return;

                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (player == healedPlayer)
                        continue;

                    ServerWorld otherWorld = getPlayerWorld(player);
                    if (otherWorld == null)
                        continue;

                    if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                        continue;

                    player.setHealth(sharedHealth);
                }

                SoulLink.LOGGER.debug("Healing synced: {} -> {}", oldHealth, sharedHealth);
            }

        } finally {
            isSyncing = false;
        }
    }

    /**
     * Called when a player's hunger changes. Updates master values and syncs to all other players.
     */
    public static void onPlayerHungerChanged(ServerPlayerEntity player, int newFoodLevel,
            float newSaturation) {
        if (isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(player);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        isSyncing = true;
        try {
            // Check if values actually changed
            boolean foodChanged = newFoodLevel != sharedHunger;
            boolean satChanged = Math.abs(newSaturation - sharedSaturation) > 0.01f;

            if (!foodChanged && !satChanged) {
                return;
            }

            sharedHunger = MathHelper.clamp(newFoodLevel, 0, 20);
            sharedSaturation = MathHelper.clamp(newSaturation, 0.0f, 20.0f);

            MinecraftServer server = runManager.getServer();
            if (server == null)
                return;

            for (ServerPlayerEntity otherPlayer : server.getPlayerManager().getPlayerList()) {
                if (otherPlayer == player)
                    continue;

                ServerWorld otherWorld = getPlayerWorld(otherPlayer);
                if (otherWorld == null)
                    continue;

                if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                    continue;

                otherPlayer.getHungerManager().setFoodLevel(sharedHunger);
                otherPlayer.getHungerManager().setSaturationLevel(sharedSaturation);
            }

            SoulLink.LOGGER.debug("Hunger synced: Food={}, Saturation={}", sharedHunger,
                    sharedSaturation);

        } finally {
            isSyncing = false;
        }
    }

    /**
     * Periodic sync check - ensures all players stay in sync. Called from server tick event.
     */
    public static void tickSync(MinecraftServer server) {
        if (isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        // Only run every 20 ticks (1 second)
        if (server.getTicks() % 20 != 0)
            return;

        isSyncing = true;
        try {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerWorld playerWorld = getPlayerWorld(player);
                if (playerWorld == null)
                    continue;

                if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
                    continue;

                // If player's values drift from master, correct them
                float playerHealth = player.getHealth();
                int playerFood = player.getHungerManager().getFoodLevel();
                float playerSat = player.getHungerManager().getSaturationLevel();

                if (Math.abs(playerHealth - sharedHealth) > 0.5f) {
                    player.setHealth(sharedHealth);
                }
                if (playerFood != sharedHunger) {
                    player.getHungerManager().setFoodLevel(sharedHunger);
                }
                if (Math.abs(playerSat - sharedSaturation) > 0.5f) {
                    player.getHungerManager().setSaturationLevel(sharedSaturation);
                }
            }
        } finally {
            isSyncing = false;
        }
    }

    /**
     * Checks if the system is currently syncing (to prevent loops).
     */
    public static boolean isSyncing() {
        return isSyncing;
    }

    // Getters

    public static float getSharedHealth() {
        return sharedHealth;
    }

    public static int getSharedHunger() {
        return sharedHunger;
    }

    public static float getSharedSaturation() {
        return sharedSaturation;
    }

    /**
     * Force sets the shared health (for admin/debug purposes).
     */
    public static void setSharedHealth(float health, MinecraftServer server) {
        sharedHealth = MathHelper.clamp(health, 0.0f, 20.0f);

        RunManager runManager = RunManager.getInstance();
        if (runManager == null)
            return;

        isSyncing = true;
        try {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerWorld playerWorld = getPlayerWorld(player);
                if (playerWorld == null)
                    continue;

                if (runManager.isTemporaryWorld(playerWorld.getRegistryKey())) {
                    player.setHealth(sharedHealth);
                }
            }
        } finally {
            isSyncing = false;
        }
    }
}
