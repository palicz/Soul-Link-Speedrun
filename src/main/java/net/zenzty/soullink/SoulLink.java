package net.zenzty.soullink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class SoulLink implements ModInitializer {
    public static final String MOD_ID = "soullink";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Soul Link Speedrun");

        // Register commands
        registerCommands();

        // Register server lifecycle events
        registerServerEvents();

        // Register player connection events
        registerConnectionEvents();

        // Register tick events
        registerTickEvents();

        // Register entity events
        registerEntityEvents();

        LOGGER.info("Soul Link Speedrun initialized successfully!");
    }

    /**
     * Helper to get ServerWorld from a player. In Yarn 1.21.11, ServerPlayerEntity.getEntityWorld()
     * returns ServerWorld directly.
     */
    private static ServerWorld getPlayerWorld(ServerPlayerEntity player) {
        return player.getEntityWorld();
    }

    /**
     * Registers the /start command to begin a new run.
     */
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /start - Start a new run
            dispatcher.register(CommandManager.literal("start").executes(context -> {
                RunManager runManager = RunManager.getInstance();

                if (runManager == null) {
                    context.getSource()
                            .sendError(RunManager.formatMessage("Run manager not initialized."));
                    return 0;
                }

                // Start the run
                runManager.startRun();

                return Command.SINGLE_SUCCESS;
            }));

            // /stoprun - Admin command to stop current run (requires gamemaster permission)
            dispatcher.register(CommandManager.literal("stoprun")
                    .requires(
                            CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                    .executes(context -> {
                        RunManager runManager = RunManager.getInstance();

                        if (runManager == null || !runManager.isRunActive()) {
                            context.getSource()
                                    .sendError(RunManager.formatMessage("No active run."));
                            return 0;
                        }

                        runManager.triggerGameOver();

                        context.getSource()
                                .sendFeedback(() -> RunManager.formatMessage("Run stopped."), true);

                        return Command.SINGLE_SUCCESS;
                    }));

            // /runinfo - Display current run info
            dispatcher.register(CommandManager.literal("runinfo").executes(context -> {
                RunManager runManager = RunManager.getInstance();

                if (runManager == null) {
                    context.getSource()
                            .sendError(RunManager.formatMessage("Run manager not initialized."));
                    return 0;
                }

                Text info = Text.empty().append(RunManager.getPrefix())
                        .append(Text.literal("State: ").formatted(Formatting.GRAY))
                        .append(Text.literal(runManager.getGameState().name())
                                .formatted(Formatting.WHITE))
                        .append(Text.literal(" | Time: ").formatted(Formatting.GRAY))
                        .append(Text.literal(runManager.getFormattedTime())
                                .formatted(Formatting.WHITE))
                        .append(Text.literal(" | Health: ").formatted(Formatting.GRAY))
                        .append(Text
                                .literal(
                                        String.format("%.1f", SharedStatsHandler.getSharedHealth()))
                                .formatted(Formatting.WHITE))
                        .append(Text.literal(" | Hunger: ").formatted(Formatting.GRAY))
                        .append(Text.literal(String.valueOf(SharedStatsHandler.getSharedHunger()))
                                .formatted(Formatting.WHITE));

                context.getSource().sendFeedback(() -> info, false);

                return Command.SINGLE_SUCCESS;
            }));
        });
    }


    // Track delayed tasks (tick -> runnable)
    private static final java.util.Map<Integer, java.util.List<Runnable>> delayedTasks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Schedule a task to run after a delay in ticks.
     */
    private static void scheduleDelayed(net.minecraft.server.MinecraftServer server, int delayTicks,
            Runnable task) {
        int targetTick = server.getTicks() + delayTicks;
        delayedTasks.computeIfAbsent(targetTick, k -> new java.util.ArrayList<>()).add(task);
    }

    /**
     * Process any delayed tasks that are ready to run.
     */
    private static void processDelayedTasks(net.minecraft.server.MinecraftServer server) {
        int currentTick = server.getTicks();
        java.util.List<Runnable> tasks = delayedTasks.remove(currentTick);
        if (tasks != null) {
            for (Runnable task : tasks) {
                try {
                    task.run();
                } catch (Exception e) {
                    LOGGER.error("Error running delayed task", e);
                }
            }
        }
    }

    /**
     * Registers server lifecycle events for initialization and cleanup.
     */
    private void registerServerEvents() {
        // Server started - initialize RunManager
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Server started - initializing RunManager");
            RunManager.init(server);
        });

        // Server stopping - cleanup worlds
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping - cleaning up temporary worlds");
            RunManager.cleanup();
        });
    }

    /**
     * Registers player connection events for welcome message and late join handling.
     */
    private void registerConnectionEvents() {
        // Player joins - show welcome or handle late join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            RunManager runManager = RunManager.getInstance();

            if (runManager == null) {
                return;
            }

            // IMMEDIATELY teleport if IDLE to prevent suffocation damage
            // (handles server restart mid-run where player coords were saved from temp world)
            if (runManager.getGameState() == RunManager.GameState.IDLE) {
                runManager.teleportToVanillaSpawn(player);
            }

            // Delay other handling to ensure player is fully loaded
            scheduleDelayed(server, 10, () -> {
                RunManager.GameState state = runManager.getGameState();

                switch (state) {
                    case IDLE:
                        // Already teleported above, just show welcome message
                        sendWelcomeMessage(player);
                        break;

                    case GENERATING_WORLD:
                    case RUNNING:
                        // Run in progress - teleport player to it
                        ServerWorld playerWorld = getPlayerWorld(player);
                        if (playerWorld == null)
                            return;

                        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey())) {
                            LOGGER.info("Late joiner detected: {} - teleporting to run",
                                    player.getName().getString());
                            runManager.teleportPlayerToRun(player);
                        }
                        break;

                    case GAMEOVER:
                        // Game over - show restart message
                        player.sendMessage(RunManager.formatMessage(
                                "Run has ended. Use /start to begin a new run."), false);
                        break;
                }
            });
        });

        // Player disconnects - log for debugging
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            RunManager runManager = RunManager.getInstance();

            if (runManager != null && runManager.isRunActive()) {
                LOGGER.info("Player {} disconnected during active run",
                        player.getName().getString());
            }
        });
    }

    /**
     * Sends the welcome message to a player explaining the mod.
     */
    private void sendWelcomeMessage(ServerPlayerEntity player) {
        // Header line
        player.sendMessage(Text
                .literal("                                                                      ")
                .formatted(Formatting.DARK_GRAY, Formatting.STRIKETHROUGH), false);

        // Title
        player.sendMessage(Text.empty().append(
                Text.literal("SOUL LINK SPEEDRUN").formatted(Formatting.RED, Formatting.BOLD)),
                false);

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

        // Footer line
        player.sendMessage(Text
                .literal("                                                                      ")
                .formatted(Formatting.DARK_GRAY, Formatting.STRIKETHROUGH), false);
    }

    /**
     * Registers tick events for timer updates and periodic sync.
     */
    private void registerTickEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Process any delayed tasks first
            processDelayedTasks(server);

            RunManager runManager = RunManager.getInstance();

            if (runManager != null) {
                // Update run manager (handles generation, timer, etc.)
                runManager.tick();
            }

            // Periodic stats sync
            SharedStatsHandler.tickSync(server);
        });
    }

    /**
     * Registers entity events for death handling and dragon victory.
     */
    private void registerEntityEvents() {
        // Handle entity death - check for dragon
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            // Check if it's the Ender Dragon
            if (entity instanceof EnderDragonEntity dragon) {
                RunManager runManager = RunManager.getInstance();

                if (runManager == null || !runManager.isRunActive()) {
                    return;
                }

                // Check if dragon died in our temporary End
                // Entity.getEntityWorld() returns World, so cast to ServerWorld
                if (dragon.getEntityWorld() instanceof ServerWorld dragonWorld
                        && runManager.isTemporaryWorld(dragonWorld.getRegistryKey())) {
                    LOGGER.info("Ender Dragon killed in temporary End - triggering victory!");
                    runManager.triggerVictory();
                }
            }
        });

        // Handle damage - intercept lethal damage to prevent death screen
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            // Only process player damage
            if (!(entity instanceof ServerPlayerEntity player)) {
                return true;
            }

            RunManager runManager = RunManager.getInstance();
            if (runManager == null) {
                return true;
            }

            // Block ALL damage during game over state (players are spectators)
            if (runManager.isGameOver()) {
                return false;
            }

            if (!runManager.isRunActive()) {
                return true;
            }

            // Skip if already syncing (prevents infinite loops)
            if (SharedStatsHandler.isSyncing()) {
                return true;
            }

            // If player is blocking with a shield, let the damage through
            // The shield will handle blocking it, and AFTER_DAMAGE will check the blocked flag
            if (player.isBlocking()) {
                return true;
            }

            // Check if this damage would be lethal
            // Note: 'amount' is raw damage before armor reduction
            float currentHealth = player.getHealth();

            // Check if player has a Totem of Undying - if so, let vanilla handle the damage
            // The totem activates during damage processing and will save them
            boolean hasTotem = player.getOffHandStack()
                    .isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING)
                    || player.getMainHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING);

            // Conservative check - if raw damage >= health, it's likely lethal
            // This might trigger slightly early due to armor, but better safe than showing death
            // screen
            // BUT: if player has a totem, let damage through so the totem can activate
            if (currentHealth - amount <= 0 && !hasTotem) {
                LOGGER.info(
                        "Lethal damage detected for {} ({} damage, {} health) - triggering game over!",
                        player.getName().getString(), amount, currentHealth);

                // Broadcast death message to all players (use vanilla death message format)
                Text deathMessage = source.getDeathMessage(player);
                Text formattedDeathMessage = Text.empty().append(RunManager.getPrefix())
                        .append(Text.literal("☠ ").formatted(Formatting.DARK_RED))
                        .append(deathMessage.copy().formatted(Formatting.RED));
                runManager.getServer().getPlayerManager().broadcast(formattedDeathMessage, false);

                // Heal player to max so they don't look dead
                player.setHealth(player.getMaxHealth());

                // Trigger game over (switches everyone to spectator)
                runManager.triggerGameOver();

                return false; // Block the lethal damage
            }

            // Allow non-lethal damage to proceed - we'll sync in AFTER_DAMAGE
            return true;
        });

        // After damage is applied, check for death and sync the health
        ServerLivingEntityEvents.AFTER_DAMAGE
                .register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
                    if (!(entity instanceof ServerPlayerEntity player)) {
                        return;
                    }

                    RunManager runManager = RunManager.getInstance();
                    if (runManager == null || !runManager.isRunActive()) {
                        return;
                    }

                    // If damage was blocked by a shield, skip syncing entirely
                    // This fixes shields not working against Creepers/TNT during RUNNING state
                    if (blocked) {
                        return;
                    }

                    // If player health hit 0 or below (shouldn't happen but just in case)
                    // This is a backup check - ALLOW_DAMAGE should have caught this
                    if (player.getHealth() <= 0) {
                        LOGGER.warn(
                                "Player {} reached 0 health despite ALLOW_DAMAGE check - triggering game over",
                                player.getName().getString());

                        // Broadcast death message to all players (use vanilla death message format)
                        Text deathMessage = source.getDeathMessage(player);
                        Text formattedDeathMessage = Text.empty().append(RunManager.getPrefix())
                                .append(Text.literal("☠ ").formatted(Formatting.DARK_RED))
                                .append(deathMessage.copy().formatted(Formatting.RED));
                        runManager.getServer().getPlayerManager().broadcast(formattedDeathMessage,
                                false);

                        player.setHealth(player.getMaxHealth());
                        runManager.triggerGameOver();
                        return;
                    }

                    // Only sync health for players in temporary worlds
                    ServerWorld playerWorld = getPlayerWorld(player);
                    if (playerWorld == null)
                        return;

                    if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey())) {
                        return;
                    }

                    // Sync the new health value to all players
                    SharedStatsHandler.onPlayerHealthChanged(player, player.getHealth(), source);
                });
    }

}
