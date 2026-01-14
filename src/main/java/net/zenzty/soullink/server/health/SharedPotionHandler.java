package net.zenzty.soullink.server.health;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;

/**
 * Handles shared potion effects between all players. Instant potions (healing/harming) are applied
 * to one player (the closest) then synced via health. Duration-based effects are synced to all
 * players immediately.
 * 
 * NOTE: This handler currently only handles instant damage (harming) effects to ensure shared
 * health is deducted immediately. Non-instant effects are generally handled by Minecraft's potion
 * logic and then synced via our mixins if needed.
 */
public class SharedPotionHandler {

    // Track effects being synced to prevent infinite loops
    private static boolean isSyncing = false;

    // Track which effects have already been synced to prevent duplicates
    // Cleared at the end of the tick via server.execute().
    private static final Set<String> recentlySyncedEffects = new HashSet<>();

    // Track pending instant effects for the current tick
    // Key: effect type ID, Value: map of player UUID to their distance from impact
    private static final Map<String, PendingSplashEvent> pendingSplashEvents = new HashMap<>();

    // The current game tick for tracking splash events
    private static long currentTick = -1;

    // Track which players have been chosen for each splash event this tick


    /**
     * Represents a pending splash event with multiple affected players.
     */
    private static class PendingSplashEvent {
        final Map<UUID, Double> playerDistances = new HashMap<>();
        final Map<UUID, Vec3d> playerPositions = new HashMap<>();
        Vec3d impactCenter = null;
        UUID closestPlayer = null;
        boolean processed = false;
        boolean scheduledProcessing = false; // Track if we've scheduled deferred processing
        StatusEffectInstance pendingEffect = null; // The effect to apply to the closest player

        void addAffectedPlayer(ServerPlayerEntity player) {
            Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
            playerPositions.put(player.getUuid(), pos);
        }

        void calculateClosestPlayer() {
            if (processed || playerPositions.isEmpty())
                return;

            // Calculate the impact center as the centroid of all affected players
            double centerX = 0, centerY = 0, centerZ = 0;
            for (Vec3d pos : playerPositions.values()) {
                centerX += pos.x;
                centerY += pos.y;
                centerZ += pos.z;
            }
            int count = playerPositions.size();
            impactCenter = new Vec3d(centerX / count, centerY / count, centerZ / count);

            // Calculate distances and find the closest player
            double minDistance = Double.MAX_VALUE;
            for (Map.Entry<UUID, Vec3d> entry : playerPositions.entrySet()) {
                double distance = entry.getValue().squaredDistanceTo(impactCenter);
                playerDistances.put(entry.getKey(), distance);

                if (distance < minDistance) {
                    minDistance = distance;
                    closestPlayer = entry.getKey();
                }
            }

            processed = true;
        }

    }

    /**
     * Checks if an effect is an instant effect (like healing or harming).
     */
    public static boolean isInstantEffect(RegistryEntry<StatusEffect> effect) {
        return effect == StatusEffects.INSTANT_HEALTH || effect == StatusEffects.INSTANT_DAMAGE;
    }

    /**
     * Updates the tick counter and cleans up old splash events.
     */
    public static void onTick(MinecraftServer server) {
        long newTick = server.getTicks();
        if (newTick != currentTick) {
            // New tick - clean up old splash events
            pendingSplashEvents.clear();
            currentTick = newTick;
        }
    }

    /**
     * Called when a player receives a status effect. For instant effects from splash potions, only
     * the closest player receives it. For duration-based effects, syncs to all other players.
     * 
     * @param player The player receiving the effect
     * @param effect The effect being applied
     * @param source The entity that caused this effect (e.g., potion thrower or null for area)
     * @return true if the effect should be applied, false to cancel
     */
    public static boolean onEffectApplied(ServerPlayerEntity player, StatusEffectInstance effect,
            Entity source) {
        if (isSyncing) {
            return true; // Allow synced effects through
        }

        Settings settings = Settings.getInstance();
        if (!settings.isSharedPotions()) {
            return true; // Shared potions disabled
        }

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) {
            return true;
        }

        if (!runManager.isTemporaryWorld(player.getEntityWorld().getRegistryKey())) {
            return true;
        }

        RegistryEntry<StatusEffect> effectType = effect.getEffectType();

        // Instant healing is excluded from shared effects - let it work normally
        // Each player hit by the splash potion gets healed independently
        if (effectType == StatusEffects.INSTANT_HEALTH) {
            return true;
        }

        // Handle instant damage - only allow the closest player to receive it
        if (effectType == StatusEffects.INSTANT_DAMAGE) {
            try {
                return handleInstantEffect(player, effect, runManager);
            } catch (Exception e) {
                if (e instanceof RuntimeException) {
                    throw e;
                }
                SoulLink.LOGGER.error("Failed to handle instant effect - allowing vanilla behavior",
                        e);
                return true;
            }
        }

        // For duration-based effects, sync to all other players
        return handleDurationEffect(player, effect, runManager);
    }

    /**
     * Handles instant effects (healing/harming) from splash potions. Blocks ALL instant effects
     * initially, tracks affected players, then at the end of the tick applies the effect only to
     * the closest player. This prevents healing/damage multiplication when multiple players are in
     * the splash area.
     */
    private static boolean handleInstantEffect(ServerPlayerEntity player,
            StatusEffectInstance effect, RunManager runManager) {
        MinecraftServer server = runManager.getServer();
        if (server == null)
            return true;

        // Ensure we're tracking the current tick
        onTick(server);

        RegistryEntry<StatusEffect> effectType = effect.getEffectType();
        String eventKey =
                effectType.getIdAsString() + "_" + effect.getAmplifier() + "_" + currentTick;

        // Get or create the splash event for this effect
        PendingSplashEvent splashEvent =
                pendingSplashEvents.computeIfAbsent(eventKey, k -> new PendingSplashEvent());

        // Register this player as affected (store their position and the effect)
        splashEvent.addAffectedPlayer(player);

        // Store the effect details if not already stored
        if (splashEvent.pendingEffect == null) {
            splashEvent.pendingEffect = effect;
        }

        // If this is the first player being registered, schedule deferred processing
        if (!splashEvent.scheduledProcessing) {
            splashEvent.scheduledProcessing = true;

            // Use server.execute to defer processing until after all splash effects are registered
            // This runs at the end of the current tick after all addStatusEffect calls complete
            server.execute(() -> {
                processPendingSplashEvent(eventKey, splashEvent, runManager);
            });
        }

        // Block all instant effects from being applied normally
        // The deferred processing will apply the effect to the closest player only
        return false;
    }

    /**
     * Process a pending splash event - applies the instant effect only to the closest player.
     */
    private static void processPendingSplashEvent(String eventKey, PendingSplashEvent splashEvent,
            RunManager runManager) {
        if (splashEvent.processed)
            return;
        splashEvent.processed = true;

        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        // Calculate which player is closest to the splash center
        splashEvent.calculateClosestPlayer();

        UUID closestPlayerUuid = splashEvent.closestPlayer;
        if (closestPlayerUuid == null)
            return;

        StatusEffectInstance effect = splashEvent.pendingEffect;
        if (effect == null)
            return;

        // Find the closest player and apply the effect only to them
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(closestPlayerUuid);
        if (player != null) {
            // Apply the instant effect directly using the heal/damage method
            // Use SharedStatsHandler.setSyncing() to prevent heal/damage from triggering sync
            SharedStatsHandler.setSyncing(true);
            try {
                RegistryEntry<StatusEffect> effectType = effect.getEffectType();
                int amplifier = effect.getAmplifier();

                if (effectType == StatusEffects.INSTANT_HEALTH) {
                    // Instant Health heals 4 HP per level (2 hearts)
                    float healAmount = (float) (4 << amplifier);
                    player.heal(healAmount);
                    SoulLink.LOGGER.debug("Applied instant health ({} HP) to closest player: {}",
                            healAmount, player.getName().getString());
                } else if (effectType == StatusEffects.INSTANT_DAMAGE) {
                    // Instant Damage deals 6 HP per level (3 hearts)
                    float damageAmount = (float) (6 << amplifier);
                    // Use magic damage source for instant damage
                    ServerWorld world = player.getEntityWorld();
                    player.damage(world, world.getDamageSources().magic(), damageAmount);
                    SoulLink.LOGGER.debug("Applied instant damage ({} HP) to closest player: {}",
                            damageAmount, player.getName().getString());
                }
            } finally {
                SharedStatsHandler.setSyncing(false);
            }
        }

        ServerPlayerEntity closestPlayer = server.getPlayerManager().getPlayer(closestPlayerUuid);
        SoulLink.LOGGER.info("Splash instant effect: {} players affected, applied to closest: {}",
                splashEvent.playerPositions.size(),
                closestPlayer != null ? closestPlayer.getName().getString() : "unknown");
    }

    /**
     * Handles duration-based effects - syncs to all other players.
     */
    private static boolean handleDurationEffect(ServerPlayerEntity player,
            StatusEffectInstance effect, RunManager runManager) {
        RegistryEntry<StatusEffect> effectType = effect.getEffectType();

        // Create a unique key for this effect to prevent duplicate syncs
        String effectKey = effectType.getIdAsString() + "_" + effect.getDuration() + "_"
                + effect.getAmplifier();
        if (recentlySyncedEffects.contains(effectKey)) {
            return true; // Already synced this tick
        }

        // Sync duration-based effect to all other players
        syncEffectToOtherPlayers(player, effect);

        // Mark as recently synced (will be cleared after a short delay)
        recentlySyncedEffects.add(effectKey);

        // Schedule cleanup of the recently synced set
        MinecraftServer server = runManager.getServer();
        if (server != null) {
            server.execute(() -> {
                recentlySyncedEffects.remove(effectKey);
            });
        }

        return true;
    }

    /**
     * Syncs a status effect to all other players in the run.
     */
    private static void syncEffectToOtherPlayers(ServerPlayerEntity sourcePlayer,
            StatusEffectInstance effect) {
        RunManager runManager = RunManager.getInstance();
        if (runManager == null)
            return;

        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        isSyncing = true;
        try {
            for (ServerPlayerEntity otherPlayer : server.getPlayerManager().getPlayerList()) {
                if (otherPlayer == sourcePlayer)
                    continue;

                ServerWorld otherWorld = otherPlayer.getEntityWorld();
                if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                    continue;

                // Apply a copy of the effect to the other player
                StatusEffectInstance effectCopy = new StatusEffectInstance(effect.getEffectType(),
                        effect.getDuration(), effect.getAmplifier(), effect.isAmbient(),
                        effect.shouldShowParticles(), effect.shouldShowIcon());

                otherPlayer.addStatusEffect(effectCopy);
            }

            SoulLink.LOGGER.debug("Synced effect {} from {} to other players",
                    effect.getEffectType().getIdAsString(), sourcePlayer.getName().getString());

        } finally {
            isSyncing = false;
        }
    }

    /**
     * Checks if the system is currently syncing effects.
     */
    public static boolean isSyncing() {
        return isSyncing;
    }

    /**
     * Resets state for a new run.
     */
    public static void reset() {
        isSyncing = false;
        recentlySyncedEffects.clear();
        pendingSplashEvents.clear();
        currentTick = -1;
    }
}
