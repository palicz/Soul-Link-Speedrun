package net.zenzty.soullink.server.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.common.SoulLinkConstants;
import net.zenzty.soullink.server.health.SharedJumpHandler;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.run.RunState;

/**
 * Registers all Fabric events: server lifecycle, player connections, tick updates, entity events.
 */
public class EventRegistry {

    private static class DelayedTask {
        int remainingTicks;
        final Runnable task;

        DelayedTask(int remainingTicks, Runnable task) {
            this.remainingTicks = remainingTicks;
            this.task = task;
        }
    }

    // Track delayed tasks (list of tasks with remaining ticks)
    private static final List<DelayedTask> delayedTasks = new ArrayList<>();

    /**
     * Registers all events for the SoulLink mod.
     */
    public static void registerAll() {
        registerServerEvents();
        registerConnectionEvents();
        registerTickEvents();
        registerEntityEvents();
    }

    /**
     * Registers server lifecycle events for initialization and cleanup.
     */
    private static void registerServerEvents() {
        // Server started - initialize RunManager
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SoulLink.LOGGER.info("Server started - initializing RunManager");
            RunManager.init(server);

            // Reset roles from previous sessions
            ManhuntManager.getInstance().resetRoles();
            ManhuntManager.getInstance().cleanupTeams(server);

            // Set all players to survival mode (in case they were spectator from previous run)
            for (net.minecraft.server.network.ServerPlayerEntity player : server.getPlayerManager()
                    .getPlayerList()) {
                if (player.interactionManager
                        .getGameMode() == net.minecraft.world.GameMode.SPECTATOR) {
                    player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                }
            }
        });

        // Server stopping - cleanup worlds
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SoulLink.LOGGER.info("Server stopping - cleaning up temporary worlds");
            delayedTasks.clear(); // Clear pending tasks
            RunManager.cleanup();
        });
    }

    /**
     * Registers player connection events for player connections and disconnects.
     */
    private static void registerConnectionEvents() {
        // Player joins - show welcome or handle late join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();

            // Check if RunManager is initialized (might not be if server just started)
            // But usually SERVER_STARTED runs before player join.
            // However, use try-catch or check to be safe if getInstance throws.
            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager == null) {
                return;
            }

            // IMMEDIATELY teleport and reset if IDLE to prevent suffocation damage
            if (runManager.getGameState() == RunState.IDLE) {
                player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                runManager.teleportToVanillaSpawn(player);
            }

            // Delay other handling to ensure player is fully loaded
            scheduleDelayed(10, () -> {
                // Return early if player has disconnected in the meantime
                if (player.isRemoved()) {
                    return;
                }

                RunState state = runManager.getGameState();

                switch (state) {
                    case IDLE:
                        sendWelcomeMessage(player);
                        break;

                    case GENERATING_WORLD:
                    case RUNNING:
                        // Run in progress - teleport player to it
                        ServerWorld playerWorld = player.getEntityWorld();
                        if (playerWorld == null) {
                            return;
                        }

                        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey())) {
                            SoulLink.LOGGER.info("Late joiner detected: {} - teleporting to run",
                                    player.getName().getString());
                            runManager.teleportPlayerToRun(player);
                        }
                        break;

                    case GAMEOVER:
                        player.sendMessage(RunManager.formatMessage(
                                "Run has ended. Use /start to begin a new run."), false);
                        break;
                }
            });
        });

        // Player disconnects - log for debugging
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager != null && runManager.isRunActive()) {
                SoulLink.LOGGER.info("Player {} disconnected during active run",
                        player.getName().getString());
            }
        });
    }

    /**
     * Sends the welcome message to a player explaining the mod.
     */
    private static void sendWelcomeMessage(ServerPlayerEntity player) {
        // Title - Show beta version info only if version contains "beta"
        var container = FabricLoader.getInstance().getModContainer(SoulLinkConstants.MOD_ID);
        if (container.isPresent()) {
            String version = container.get().getMetadata().getVersion().getFriendlyString();
            if (version.contains("beta")) {
                player.sendMessage(Text.empty()
                        .append(Text.literal("SOUL LINK SPEEDRUN - BETA RELEASE " + version)
                                .formatted(Formatting.RED, Formatting.BOLD)),
                        false);
            } else {
                player.sendMessage(Text.empty().append(Text.literal("SOUL LINK SPEEDRUN")
                        .formatted(Formatting.RED, Formatting.BOLD)), false);
            }
        } else {
            player.sendMessage(Text.empty().append(
                    Text.literal("SOUL LINK SPEEDRUN").formatted(Formatting.RED, Formatting.BOLD)),
                    false);
        }

        // Empty line
        player.sendMessage(Text.empty(), false);

        // Soul Link info
        player.sendMessage(Text.empty().append(Text.literal("❤ ").formatted(Formatting.RED))
                .append(Text.literal("Soul Link").formatted(Formatting.WHITE))
                .append(Text.literal(" - All players share health and hunger.")
                        .formatted(Formatting.GRAY)),
                false);

        // Goal info
        player.sendMessage(Text.empty().append(Text.literal("⚔ ").formatted(Formatting.GOLD))
                .append(Text.literal("Goal").formatted(Formatting.WHITE))
                .append(Text.literal(" - Defeat the Ender Dragon together.")
                        .formatted(Formatting.GRAY)),
                false);

        // Death info
        player.sendMessage(Text.empty().append(Text.literal("☠ ").formatted(Formatting.DARK_RED))
                .append(Text.literal("Death").formatted(Formatting.WHITE))
                .append(Text.literal(" - If anyone dies, the run ends for all.")
                        .formatted(Formatting.GRAY)),
                false);

        // Empty line
        player.sendMessage(Text.empty(), false);

        // Start command
        player.sendMessage(Text.empty().append(Text.literal("Use ").formatted(Formatting.GRAY))
                .append(Text.literal("/start").formatted(Formatting.GOLD))
                .append(Text.literal(" or ").formatted(Formatting.GRAY))
                .append(Text.literal("click here").setStyle(Style.EMPTY.withColor(Formatting.BLUE)
                        .withUnderline(true).withClickEvent(new ClickEvent.RunCommand("/start"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.literal("Start a new run").formatted(Formatting.GRAY)))))
                .append(Text.literal(" to begin.").formatted(Formatting.GRAY)), false);

        // Empty line
        player.sendMessage(Text.empty(), false);

        // Settings tip
        player.sendMessage(Text.empty().append(Text.literal("TIP: ").formatted(Formatting.YELLOW))
                .append(Text.literal("Customize your next run with ").formatted(Formatting.GRAY))
                .append(Text.literal("/settings").setStyle(Style.EMPTY.withColor(Formatting.GOLD)
                        .withClickEvent(new ClickEvent.RunCommand("/settings"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.literal("Open run options").formatted(Formatting.GRAY)))))
                .append(Text.literal(".").formatted(Formatting.GRAY)), false);
    }

    /**
     * Registers tick events for timer updates and periodic sync.
     */
    private static void registerTickEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Process any delayed tasks first
            processDelayedTasks(server);

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager != null) {
                // Update run manager (handles generation, timer, etc.)
                runManager.tick();
            }

            // Process shared jumps at tick end (prevents race conditions with latency)
            SharedJumpHandler.processJumpsAtTickEnd(server);

            // Periodic stats sync
            SharedStatsHandler.tickSync(server);
        });
    }

    /**
     * Registers entity events for death handling and dragon victory.
     */
    private static void registerEntityEvents() {
        // Handle entity death - check for dragon
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof EnderDragonEntity dragon) {
                RunManager runManager;
                try {
                    runManager = RunManager.getInstance();
                } catch (IllegalStateException e) {
                    return;
                }

                if (runManager == null || !runManager.isRunActive()) {
                    return;
                }

                if (dragon.getEntityWorld() instanceof ServerWorld dragonWorld
                        && runManager.isTemporaryWorld(dragonWorld.getRegistryKey())) {
                    SoulLink.LOGGER
                            .info("Ender Dragon killed in temporary End - triggering victory!");
                    runManager.triggerVictory();
                }
            }
        });

        // Handle damage - intercept lethal damage to prevent death screen
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity player)) {
                return true;
            }

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return true;
            }

            if (runManager == null) {
                return true;
            }

            // Block ALL damage during game over state
            if (runManager.isGameOver()) {
                return false;
            }

            if (!runManager.isRunActive()) {
                return true;
            }

            if (SharedStatsHandler.isSyncing()) {
                return true;
            }

            if (player.isBlocking()) {
                return true;
            }

            float currentHealth = player.getHealth();

            boolean hasTotem = player.getOffHandStack()
                    .isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING)
                    || player.getMainHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING);

            if (currentHealth - amount <= 0 && !hasTotem) {
                // Hunters get custom death with respawn countdown (not game over)
                if (ManhuntManager.getInstance().isHunter(player)) {
                    SoulLink.LOGGER.info("Lethal damage to Hunter {} - starting respawn countdown",
                            player.getName().getString());
                    handleHunterDeath(player, source, runManager);
                    return false; // Block the damage, we handle death ourselves
                }

                SoulLink.LOGGER.info(
                        "Lethal damage detected for Runner {} ({} damage, {} health) - triggering game over!",
                        player.getName().getString(), amount, currentHealth);

                handlePlayerDeath(player, source, runManager);

                return false;
            }

            return true;
        });

        // After damage is applied
        ServerLivingEntityEvents.AFTER_DAMAGE
                .register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
                    if (!(entity instanceof ServerPlayerEntity player)) {
                        return;
                    }

                    RunManager runManager;
                    try {
                        runManager = RunManager.getInstance();
                    } catch (IllegalStateException e) {
                        return;
                    }

                    if (runManager == null || !runManager.isRunActive()) {
                        return;
                    }

                    if (damageTaken <= 0) {
                        return;
                    }

                    if (player.getHealth() <= 0) {
                        // Hunters use vanilla death mechanics - don't trigger game over
                        if (ManhuntManager.getInstance().isHunter(player)) {
                            SoulLink.LOGGER.info(
                                    "Hunter {} reached 0 health - allowing vanilla death",
                                    player.getName().getString());
                            return;
                        }

                        SoulLink.LOGGER.warn(
                                "Runner {} reached 0 health despite ALLOW_DAMAGE check - triggering game over",
                                player.getName().getString());

                        handlePlayerDeath(player, source, runManager);
                        return;
                    }

                    ServerWorld playerWorld = player.getEntityWorld();
                    if (playerWorld == null) {
                        return;
                    }

                    if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey())) {
                        return;
                    }

                    // Hunters use vanilla mechanics - don't sync their damage
                    if (ManhuntManager.getInstance().isHunter(player)) {
                        return;
                    }

                    SharedStatsHandler.onPlayerHealthChanged(player, player.getHealth(), source);
                });
    }

    /**
     * Handles player death logic (broadcast message, reset health, trigger game over).
     */
    private static void handlePlayerDeath(ServerPlayerEntity player, DamageSource source,
            RunManager runManager) {
        Text deathMessage = source.getDeathMessage(player);
        Text formattedDeathMessage = Text.empty().append(RunManager.getPrefix())
                .append(Text.literal("☠ ").formatted(Formatting.DARK_RED))
                .append(deathMessage.copy().formatted(Formatting.RED));
        runManager.getServer().getPlayerManager().broadcast(formattedDeathMessage, false);

        player.setHealth(player.getMaxHealth());
        runManager.triggerGameOver();
    }

    /**
     * Handles Hunter death with spectator mode and respawn countdown. Hunters don't trigger game
     * over, they respawn after 5 seconds.
     */
    public static void handleHunterDeath(ServerPlayerEntity player, DamageSource source,
            RunManager runManager) {
        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        // Broadcast death message
        Text deathMessage = source.getDeathMessage(player);
        Text formattedDeathMessage = Text.empty().append(RunManager.getPrefix())
                .append(Text.literal("☠ ").formatted(Formatting.DARK_RED))
                .append(deathMessage.copy().formatted(Formatting.RED));
        server.getPlayerManager().broadcast(formattedDeathMessage, false);

        // Clear harmful effects
        player.getStatusEffects().stream()
                .filter(effect -> !effect.getEffectType().value().isBeneficial())
                .map(effect -> effect.getEffectType()).toList().forEach(player::removeStatusEffect);
        player.extinguish();

        // Set to spectator mode
        player.changeGameMode(GameMode.SPECTATOR);

        // Clear inventory
        player.getInventory().clear();
        player.getEnderChestInventory().clear();

        // Show countdown titles (5, 4, 3, 2, 1, RESPAWN)
        for (int i = 5; i >= 1; i--) {
            final int count = i;
            scheduleDelayed((5 - i) * 20, () -> {
                if (player.isRemoved() || !runManager.isRunActive())
                    return;
                player.networkHandler
                        .sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(
                                Text.literal(String.valueOf(count)).formatted(Formatting.RED,
                                        Formatting.BOLD)));
                player.networkHandler
                        .sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(
                                Text.literal("Respawning...").formatted(Formatting.GRAY)));
            });
        }

        // After 5 seconds, show RESPAWN and teleport to spawn
        scheduleDelayed(5 * 20, () -> {
            if (player.isRemoved() || !runManager.isRunActive())
                return;

            // Show RESPAWN title
            player.networkHandler
                    .sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(
                            Text.literal("RESPAWN").formatted(Formatting.GREEN, Formatting.BOLD)));

            // Set to survival mode
            player.changeGameMode(GameMode.SURVIVAL);

            // Get the spawn point in temp world
            ServerWorld tempOverworld = runManager.getWorldService().getOverworld();
            net.minecraft.util.math.BlockPos spawnPos = runManager.getSpawnPos();

            if (tempOverworld != null && spawnPos != null) {
                // Set spawn point in the temporary world for respawning (User Request)
                net.minecraft.world.WorldProperties.SpawnPoint spawnPoint =
                        net.minecraft.world.WorldProperties.SpawnPoint
                                .create(tempOverworld.getRegistryKey(), spawnPos, 0.0f, 0.0f);
                ServerPlayerEntity.Respawn respawn =
                        new ServerPlayerEntity.Respawn(spawnPoint, true);
                player.setSpawnPoint(respawn, false);

                // Teleport to the specific run spawn point
                player.teleport(tempOverworld, spawnPos.getX() + 0.5, spawnPos.getY(),
                        spawnPos.getZ() + 0.5, java.util.Set.of(), 0.0f, 0.0f, true);
            }

            // Reset health and hunger
            player.setHealth(player.getMaxHealth());
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5.0f);

            SoulLink.LOGGER.info("Hunter {} respawned after death", player.getName().getString());
        });
    }

    /**
     * Schedule a task to run after a delay in ticks.
     */
    public static void scheduleDelayed(int delayTicks, Runnable task) {
        delayedTasks.add(new DelayedTask(delayTicks, task));
    }

    // Kept for backward compatibility if needed, but redirects to the new one
    public static void scheduleDelayed(MinecraftServer server, int delayTicks, Runnable task) {
        scheduleDelayed(delayTicks, task);
    }

    /**
     * Process any delayed tasks that are ready to run.
     */
    private static void processDelayedTasks(MinecraftServer server) {
        Iterator<DelayedTask> iterator = delayedTasks.iterator();
        while (iterator.hasNext()) {
            DelayedTask task = iterator.next();
            task.remainingTicks--;
            if (task.remainingTicks <= 0) {
                try {
                    task.task.run();
                } catch (Exception e) {
                    SoulLink.LOGGER.error("Error running delayed task", e);
                }
                iterator.remove();
            }
        }
    }
}
