package net.zenzty.soullink.server.health;

import java.util.List;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;

// Note: Settings import is used for half heart mode max health calculations

/**
 * Handles shared health, hunger, and saturation between all players. Implements the "Soul Link"
 * mechanic where all players share the same vital stats.
 */
public class SharedStatsHandler {

    // Master stat values
    private static volatile float sharedHealth = 20.0f;
    private static volatile int sharedHunger = 20;
    private static volatile float sharedSaturation = 5.0f;
    private static volatile float sharedAbsorption = 0.0f; // Absorption hearts (golden apples, etc)

    // Prevent infinite sync loops
    private static volatile boolean isSyncing = false;

    // Accumulator for fractional natural regen (since we divide by player count)
    private static volatile float regenAccumulator = 0.0f;

    // Accumulator for fractional regeneration effect healing (since we divide by player count)
    private static volatile float regenerationHealAccumulator = 0.0f;

    // Accumulators for fractional hunger/saturation drain (since we divide by player count)
    private static volatile float hungerDrainAccumulator = 0.0f;
    private static volatile float saturationDrainAccumulator = 0.0f;

    // Accumulator for fractional damage (Poison/Wither)
    private static volatile float damageAccumulator = 0.0f;

    /**
     * Gets the current max health based on settings.
     */
    private static float getMaxHealth() {
        return Settings.getInstance().isHalfHeartMode() ? 1.0f : 20.0f;
    }

    /**
     * Resets all shared stats to default values. Called when starting a new run.
     */
    public static void reset() {
        // Use half heart mode max health if enabled
        float maxHealth = getMaxHealth();

        sharedHealth = maxHealth;
        sharedHunger = 20;
        sharedSaturation = 5.0f;
        sharedAbsorption = 0.0f;
        isSyncing = false;
        regenAccumulator = 0.0f;
        regenerationHealAccumulator = 0.0f;
        hungerDrainAccumulator = 0.0f;
        saturationDrainAccumulator = 0.0f;
        damageAccumulator = 0.0f;

        // Also reset other shared handlers
        SharedPotionHandler.reset();
        SharedJumpHandler.reset();

        SoulLink.LOGGER.info("Shared stats reset to defaults (maxHealth={})", maxHealth);
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
            player.setAbsorptionAmount(sharedAbsorption);
            player.getHungerManager().setFoodLevel(sharedHunger);
            player.getHungerManager().setSaturationLevel(sharedSaturation);
            SoulLink.LOGGER.debug(
                    "Synced {} to shared stats: HP={}, Absorption={}, Food={}, Sat={}",
                    player.getName().getString(), sharedHealth, sharedAbsorption, sharedHunger,
                    sharedSaturation);
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
            float currentDamageAmount = oldHealth - newHealth;

            // Handle periodic damage (Poison/Wither) - normalize by player count
            // Without this, N players poisoned = Nx damage speed
            String damageType = damageSource.getName();
            if (damageType.equals("poison") || damageType.equals("wither")) {
                handlePeriodicDamage(damagedPlayer, currentDamageAmount);
                return;
            }

            // Update the master health to match the damaged player's health
            sharedHealth = MathHelper.clamp(newHealth, 0.0f, getMaxHealth());

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

                float syncedDamageAmount = oldHealth - sharedHealth;
                List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();

                // Broadcast damage notification to all players
                // Convert from half-hearts to full hearts for display (Minecraft stores health as
                // 0-20, where 1 heart = 2)
                float damageInHearts = syncedDamageAmount / 2.0f;
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
                    if (player == damagedPlayer || player.isSpectator() || player.isCreative())
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
                    player.damage(otherWorld, syncDamage, syncedDamageAmount);

                    // Safety check: if player "died" due to local damage but shared health remains,
                    // restore them
                    if (!player.isAlive() && sharedHealth > 0) {
                        player.setHealth(Math.max(1.0f, sharedHealth));
                    } else {
                        // Ensure health is exactly what we expect (in case of any rounding)
                        player.setHealth(sharedHealth);
                    }
                }

                SoulLink.LOGGER.debug("Health synced: {} -> {} (from {})", oldHealth, sharedHealth,
                        damagedPlayer.getName().getString());
            }

        } finally {
            isSyncing = false;
        }
    }

    /**
     * Handles periodic damage (Poison/Wither) by normalizing it by player count and using an
     * accumulator.
     */
    private static void handlePeriodicDamage(ServerPlayerEntity damagedPlayer, float damageAmount) {
        RunManager runManager = RunManager.getInstance();
        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        // Count players in the run
        int playerCount = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.getRegistryKey())) {
                playerCount++;
            }
        }

        if (playerCount == 0)
            return;

        // Divide the damage by player count and accumulate
        float normalizedDamage = damageAmount / playerCount;
        damageAccumulator += normalizedDamage;

        SoulLink.LOGGER.debug(
                "[DAMAGE DEBUG] Player {} took {} periodic damage, normalized to {} ({} players), accumulator now {}",
                damagedPlayer.getName().getString(), damageAmount, normalizedDamage, playerCount,
                damageAccumulator);

        // Only apply damage when we've accumulated at least 0.5 HP
        if (damageAccumulator >= 0.5f) {
            float damageToApply = damageAccumulator;
            damageAccumulator = 0.0f;

            float oldHealth = sharedHealth;
            sharedHealth = MathHelper.clamp(sharedHealth - damageToApply, 0.0f, getMaxHealth());

            // Sync to all players
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerWorld otherWorld = getPlayerWorld(player);
                if (otherWorld == null || !runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                    continue;

                player.setHealth(sharedHealth);
            }

            SoulLink.LOGGER.debug("[DAMAGE DEBUG] Applied {} periodic damage: {} -> {}",
                    damageToApply, oldHealth, sharedHealth);

            if (sharedHealth <= 0) {
                runManager.triggerGameOver();
            }
        } else {
            // Revert the damage to the player since it hasn't reached the threshold yet
            damagedPlayer.setHealth(sharedHealth);
        }
    }

    /**
     * Called when a player heals (potions, etc.) Updates the master health and syncs to all
     * players.
     * 
     * Note: Regeneration effect healing is handled separately by onRegenerationHeal() to normalize
     * by player count.
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
            sharedHealth = MathHelper.clamp(newHealth, 0.0f, getMaxHealth());

            // Only sync if health increased
            if (sharedHealth > oldHealth) {
                MinecraftServer server = runManager.getServer();
                if (server == null)
                    return;

                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (player == healedPlayer)
                        continue;

                    if (player.isSpectator() || player.isCreative())
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
     * Called when a player heals from a regeneration effect. The healing amount is divided by the
     * number of players in the run to normalize regen speed.
     * 
     * Without this, N players with regeneration = Nx healing speed since each player's regen would
     * stack.
     */
    public static void onRegenerationHeal(ServerPlayerEntity regenPlayer, float healAmount) {
        if (isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(regenPlayer);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        // Count players in the run
        int playerCount = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.getRegistryKey())) {
                playerCount++;
            }
        }

        if (playerCount == 0)
            return;

        // Divide the heal amount by player count and accumulate
        float normalizedHeal = healAmount / playerCount;
        regenerationHealAccumulator += normalizedHeal;

        SoulLink.LOGGER.debug(
                "[REGEN EFFECT DEBUG] Player {} healed {} HP from regeneration, normalized to {} ({} players), accumulator now {}",
                regenPlayer.getName().getString(), healAmount, normalizedHeal, playerCount,
                regenerationHealAccumulator);

        // Only apply healing when we've accumulated at least 0.5 HP (prevents constant tiny
        // updates)
        if (regenerationHealAccumulator >= 0.5f) {
            float healToApply = regenerationHealAccumulator;
            regenerationHealAccumulator = 0.0f;

            isSyncing = true;
            try {
                float oldHealth = sharedHealth;
                sharedHealth = MathHelper.clamp(sharedHealth + healToApply, 0.0f, getMaxHealth());

                if (sharedHealth > oldHealth) {
                    // Sync to all players
                    for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                        ServerWorld otherWorld = getPlayerWorld(player);
                        if (otherWorld == null)
                            continue;

                        if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                            continue;

                        player.setHealth(sharedHealth);
                    }

                    SoulLink.LOGGER.debug(
                            "[REGEN EFFECT DEBUG] Applied {} HP healing from regeneration: {} -> {} ({} players in run)",
                            healToApply, oldHealth, sharedHealth, playerCount);
                }
            } finally {
                isSyncing = false;
            }
        } else {
            // Revert the healing to the player since it hasn't reached the threshold yet
            regenPlayer.setHealth(sharedHealth);
        }
    }

    /**
     * Called when a player's absorption amount changes (from golden apples, etc). Updates the
     * master absorption and syncs to all other players.
     */
    public static void onAbsorptionChanged(ServerPlayerEntity changedPlayer, float newAbsorption) {
        if (isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(changedPlayer);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        // Only sync if absorption actually changed
        if (Math.abs(newAbsorption - sharedAbsorption) < 0.1f)
            return;

        isSyncing = true;
        try {
            float oldAbsorption = sharedAbsorption;
            sharedAbsorption = MathHelper.clamp(newAbsorption, 0.0f, 20.0f);

            MinecraftServer server = runManager.getServer();
            if (server == null)
                return;

            // Sync to all other players
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player == changedPlayer)
                    continue;

                ServerWorld otherWorld = getPlayerWorld(player);
                if (otherWorld == null)
                    continue;

                if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                    continue;

                player.setAbsorptionAmount(sharedAbsorption);
            }

            SoulLink.LOGGER.debug("Absorption synced: {} -> {} (from {})", oldAbsorption,
                    sharedAbsorption, changedPlayer.getName().getString());

        } finally {
            isSyncing = false;
        }
    }

    /**
     * Called when a player naturally regenerates health (from saturation/hunger). The healing
     * amount is divided by the number of players in the run to normalize regen speed.
     * 
     * Without this, N players = Nx regen speed since each player's regen would stack.
     */
    public static void onNaturalRegen(ServerPlayerEntity regenPlayer, float healAmount) {
        if (isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(regenPlayer);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        // Count players in the run
        int playerCount = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.getRegistryKey())) {
                playerCount++;
            }
        }

        if (playerCount == 0)
            return;

        // Divide the heal amount by player count and accumulate
        float normalizedHeal = healAmount / playerCount;
        regenAccumulator += normalizedHeal;

        SoulLink.LOGGER.debug(
                "[REGEN DEBUG] Player {} healed {} HP, normalized to {} ({} players), accumulator now {}",
                regenPlayer.getName().getString(), healAmount, normalizedHeal, playerCount,
                regenAccumulator);

        // Only apply healing when we've accumulated at least 0.5 HP (prevents constant tiny
        // updates)
        if (regenAccumulator >= 0.5f) {
            float healToApply = regenAccumulator;
            regenAccumulator = 0.0f;

            isSyncing = true;
            try {
                float oldHealth = sharedHealth;
                sharedHealth = MathHelper.clamp(sharedHealth + healToApply, 0.0f, getMaxHealth());

                if (sharedHealth > oldHealth) {
                    // Sync to all players
                    for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                        ServerWorld otherWorld = getPlayerWorld(player);
                        if (otherWorld == null)
                            continue;

                        if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                            continue;

                        player.setHealth(sharedHealth);
                    }

                    SoulLink.LOGGER.debug(
                            "[REGEN DEBUG] Applied {} HP healing: {} -> {} ({} players in run)",
                            healToApply, oldHealth, sharedHealth, playerCount);
                }
            } finally {
                isSyncing = false;
            }
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
     * Called when a player's hunger/saturation drains from natural regeneration. The drain is
     * divided by the number of players to normalize drain rate.
     * 
     * Without this, N players = Nx hunger drain since each player's regen consumes hunger.
     */
    public static void onNaturalHungerDrain(ServerPlayerEntity drainPlayer, int foodDrain,
            float satDrain) {
        if (isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(drainPlayer);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        // Count players in the run
        int playerCount = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.getRegistryKey())) {
                playerCount++;
            }
        }

        if (playerCount == 0)
            return;

        // Divide the drain by player count and accumulate
        float normalizedFoodDrain = (float) foodDrain / playerCount;
        float normalizedSatDrain = satDrain / playerCount;

        hungerDrainAccumulator += normalizedFoodDrain;
        saturationDrainAccumulator += normalizedSatDrain;

        // Check if we should apply the accumulated drain
        boolean shouldApply = hungerDrainAccumulator >= 1.0f || saturationDrainAccumulator >= 0.5f;

        if (shouldApply) {
            isSyncing = true;
            try {
                // Apply accumulated food drain (whole numbers only)
                int foodToApply = (int) hungerDrainAccumulator;
                if (foodToApply > 0) {
                    sharedHunger = MathHelper.clamp(sharedHunger - foodToApply, 0, 20);
                    hungerDrainAccumulator -= foodToApply;
                }

                // Apply accumulated saturation drain
                if (saturationDrainAccumulator >= 0.1f) {
                    float satToApply = saturationDrainAccumulator;
                    sharedSaturation = MathHelper.clamp(sharedSaturation - satToApply, 0.0f, 20.0f);
                    saturationDrainAccumulator = 0.0f;
                }

                // Sync to all players
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    ServerWorld otherWorld = getPlayerWorld(player);
                    if (otherWorld == null)
                        continue;

                    if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                        continue;

                    player.getHungerManager().setFoodLevel(sharedHunger);
                    player.getHungerManager().setSaturationLevel(sharedSaturation);
                }

                SoulLink.LOGGER.debug(
                        "Natural hunger drain applied: Food={}, Sat={} (from {} players)",
                        sharedHunger, sharedSaturation, playerCount);
            } finally {
                isSyncing = false;
            }
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
                float playerAbsorption = player.getAbsorptionAmount();
                int playerFood = player.getHungerManager().getFoodLevel();
                float playerSat = player.getHungerManager().getSaturationLevel();

                if (Math.abs(playerHealth - sharedHealth) > 0.5f) {
                    player.setHealth(sharedHealth);
                }
                if (Math.abs(playerAbsorption - sharedAbsorption) > 0.5f) {
                    player.setAbsorptionAmount(sharedAbsorption);
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

    /**
     * Sets the syncing flag. Used by other shared handlers (like SharedPotionHandler) to prevent
     * heal/damage operations from triggering additional syncs.
     */
    public static void setSyncing(boolean syncing) {
        isSyncing = syncing;
    }

    /**
     * Executes a task with syncing temporarily disabled.
     */
    public static void withSyncingDisabled(Runnable task) {
        boolean wasSyncing = isSyncing;
        setSyncing(true); // "Syncing" means we are currently applying a sync, so ignore local
                          // changes
        try {
            task.run();
        } finally {
            setSyncing(wasSyncing);
        }
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
        float clampedHealth = MathHelper.clamp(health, 0.0f, getMaxHealth());

        if (server == null) {
            sharedHealth = clampedHealth;
            return;
        }

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) {
            sharedHealth = clampedHealth;
            return;
        }
        sharedHealth = clampedHealth;

        isSyncing = true;
        try {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerWorld playerWorld = getPlayerWorld(player);
                if (playerWorld == null)
                    continue;

                if (runManager.isTemporaryWorld(playerWorld.getRegistryKey())) {
                    // Skip spectators and creative mode players for health sync
                    if (player.isSpectator() || player.isCreative()) {
                        continue;
                    }
                    player.setHealth(sharedHealth);
                }
            }
        } finally {
            isSyncing = false;
        }
    }
}
