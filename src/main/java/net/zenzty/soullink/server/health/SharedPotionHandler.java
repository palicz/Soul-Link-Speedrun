package net.zenzty.soullink.server.health;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
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
    private static final Set<String> RECENTLY_SYNCED_EFFECTS = new HashSet<>();

    // Track pending instant effects for the current tick
    // Key: effect type ID, Value: map of player UUID to their distance from impact
    private static final Map<String, PendingSplashEvent> PENDING_SPLASH_EVENTS = new HashMap<>();

    // The current game tick for tracking splash events
    private static long currentTick = -1;

    // Track which players have been chosen for each splash event this tick

    /**
     * Represents a pending splash event with multiple affected players.
     */
    private static class PendingSplashEvent {
        final Map<UUID, Double> playerDistances = new HashMap<>();
        final Map<UUID, Vec3> playerPositions = new HashMap<>();
        Vec3 impactCenter = null;
        UUID closestPlayer = null;
        boolean processed = false;
        boolean scheduledProcessing = false; // Track if we've scheduled deferred processing
        MobEffectInstance pendingEffect = null; // The effect to apply to the closest player

        void addAffectedPlayer(ServerPlayer player) {
            Vec3 pos = new Vec3(player.getX(), player.getY(), player.getZ());
            playerPositions.put(player.getUUID(), pos);
        }

        void calculateClosestPlayer() {
            if (processed || playerPositions.isEmpty()) return;

            // Calculate the impact center as the centroid of all affected players
            double centerX = 0, centerY = 0, centerZ = 0;
            for (Vec3 pos : playerPositions.values()) {
                centerX += pos.x;
                centerY += pos.y;
                centerZ += pos.z;
            }
            int count = playerPositions.size();
            impactCenter = new Vec3(centerX / count, centerY / count, centerZ / count);

            // Calculate distances and find the closest player
            double minDistance = Double.MAX_VALUE;
            for (Map.Entry<UUID, Vec3> entry : playerPositions.entrySet()) {
                double distance = entry.getValue().distanceToSqr(impactCenter);
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
    public static boolean isInstantEffect(Holder<MobEffect> effect) {
        return effect.unwrapKey().equals(MobEffects.INSTANT_HEALTH.unwrapKey())
                || effect.unwrapKey().equals(MobEffects.INSTANT_DAMAGE.unwrapKey());
    }

    /**
     * Updates the tick counter and cleans up old splash events.
     */
    public static void onTick(MinecraftServer server) {
        long newTick = server.getTickCount();
        if (newTick != currentTick) {
            // New tick - clean up old splash events
            PENDING_SPLASH_EVENTS.clear();
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
    public static boolean onEffectApplied(ServerPlayer player, MobEffectInstance effect, Entity source) {
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

        if (!runManager.isTemporaryWorld(player.level().dimension())) {
            return true;
        }

        Holder<MobEffect> effectType = effect.getEffect();

        // Handle instant effects (healing and damage) - only allow the closest player to receive it
        if (isInstantEffect(effectType)) {
            try {
                return handleInstantEffect(player, effect, runManager);
            } catch (RuntimeException r) {
                throw r;
            } catch (Exception e) {
                SoulLink.LOGGER.error("Failed to handle instant effect - allowing vanilla behavior", e);
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
    private static boolean handleInstantEffect(ServerPlayer player, MobEffectInstance effect, RunManager runManager) {
        MinecraftServer server = runManager.getServer();
        if (server == null) return true;

        // Ensure we're tracking the current tick
        onTick(server);

        Holder<MobEffect> effectType = effect.getEffect();
        String eventKey = effectType.getRegisteredName() + "_" + effect.getAmplifier() + "_" + currentTick;

        // Get or create the splash event for this effect
        PendingSplashEvent splashEvent = PENDING_SPLASH_EVENTS.computeIfAbsent(eventKey, k -> new PendingSplashEvent());

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
    private static void processPendingSplashEvent(
            String eventKey, PendingSplashEvent splashEvent, RunManager runManager) {
        if (splashEvent.processed) return;
        splashEvent.processed = true;

        MinecraftServer server = runManager.getServer();
        if (server == null) return;

        // Calculate which player is closest to the splash center
        splashEvent.calculateClosestPlayer();

        UUID closestPlayerUuid = splashEvent.closestPlayer;
        if (closestPlayerUuid == null) return;

        MobEffectInstance effect = splashEvent.pendingEffect;
        if (effect == null) return;

        // Find the closest player and apply the effect only to them
        ServerPlayer player = server.getPlayerList().getPlayer(closestPlayerUuid);
        if (player != null) {
            // Apply the instant effect directly using the heal/damage method
            // Use SharedStatsHandler.setSyncing() to prevent heal/damage from triggering sync
            SharedStatsHandler.setSyncing(true);
            try {
                Holder<MobEffect> effectType = effect.getEffect();
                int amplifier = effect.getAmplifier();

                if (effectType == MobEffects.INSTANT_HEALTH) {
                    // Instant Health heals 4 × 2^amplifier HP (4 at level 1, 8 at level 2, etc.)
                    float healAmount = (float) (4 << amplifier);
                    player.heal(healAmount);
                    SoulLink.LOGGER.debug(
                            "Applied instant health ({} HP) to closest player: {}",
                            healAmount,
                            player.getName().getString());
                } else if (effectType == MobEffects.INSTANT_DAMAGE) {
                    // Instant Damage deals 6 HP per level (3 hearts)
                    float damageAmount = (float) (6 << amplifier);
                    // Use magic damage source for instant damage
                    ServerLevel world = player.level();
                    player.hurtServer(world, world.damageSources().magic(), damageAmount);
                    SoulLink.LOGGER.debug(
                            "Applied instant damage ({} HP) to closest player: {}",
                            damageAmount,
                            player.getName().getString());
                }
            } finally {
                SharedStatsHandler.setSyncing(false);
            }
        }

        SoulLink.LOGGER.info(
                "Splash instant effect: {} players affected, applied to closest: {}",
                splashEvent.playerPositions.size(),
                player != null ? player.getName().getString() : "unknown");
    }

    /**
     * Handles duration-based effects - syncs to all other players.
     */
    private static boolean handleDurationEffect(ServerPlayer player, MobEffectInstance effect, RunManager runManager) {
        Holder<MobEffect> effectType = effect.getEffect();

        // Create a unique key for this effect to prevent duplicate syncs
        String effectKey = effectType.getRegisteredName() + "_" + effect.getDuration() + "_" + effect.getAmplifier();
        if (RECENTLY_SYNCED_EFFECTS.contains(effectKey)) {
            return true; // Already synced this tick
        }

        // Sync duration-based effect to all other players
        syncEffectToOtherPlayers(player, effect);

        // Mark as recently synced (will be cleared after a short delay)
        RECENTLY_SYNCED_EFFECTS.add(effectKey);

        // Schedule cleanup of the recently synced set
        MinecraftServer server = runManager.getServer();
        if (server != null) {
            server.execute(() -> {
                RECENTLY_SYNCED_EFFECTS.remove(effectKey);
            });
        }

        return true;
    }

    /**
     * Syncs a status effect to all other players in the run.
     */
    private static void syncEffectToOtherPlayers(ServerPlayer sourcePlayer, MobEffectInstance effect) {
        RunManager runManager = RunManager.getInstance();
        if (runManager == null) return;

        MinecraftServer server = runManager.getServer();
        if (server == null) return;

        isSyncing = true;
        try {
            for (ServerPlayer otherPlayer : server.getPlayerList().getPlayers()) {
                if (otherPlayer == sourcePlayer) continue;

                ServerLevel otherWorld = otherPlayer.level();
                if (!runManager.isTemporaryWorld(otherWorld.dimension())) continue;

                // Apply a copy of the effect to the other player
                MobEffectInstance effectCopy = new MobEffectInstance(
                        effect.getEffect(),
                        effect.getDuration(),
                        effect.getAmplifier(),
                        effect.isAmbient(),
                        effect.isVisible(),
                        effect.showIcon());

                otherPlayer.addEffect(effectCopy);
            }

            SoulLink.LOGGER.debug(
                    "Synced effect {} from {} to other players",
                    effect.getEffect().getRegisteredName(),
                    sourcePlayer.getName().getString());

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
        RECENTLY_SYNCED_EFFECTS.clear();
        PENDING_SPLASH_EVENTS.clear();
        currentTick = -1;
    }
}
