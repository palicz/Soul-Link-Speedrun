package net.zenzty.soullink.server.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.common.SoulLinkConstants;
import net.zenzty.soullink.server.health.SharedJumpHandler;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.manhunt.CompassTrackingHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.run.RunState;
import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.server.settings.SettingsPersistence;

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
        CompassTrackingHandler.register();
    }

    /**
     * Registers server lifecycle events for initialization and cleanup.
     */
    private static void registerServerEvents() {
        // Server started - initialize RunManager and load persisted settings
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SoulLink.LOGGER.info("Server started - initializing RunManager");
            RunManager.init(server);
            SettingsPersistence.load(server);
            ManhuntManager.getInstance().resetRoles();
            ManhuntManager.getInstance().cleanupTeams(server);
        });

        // Server stopping - save settings, then cleanup worlds
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SoulLink.LOGGER
                    .info("Server stopping - saving settings and cleaning up temporary worlds");
            SettingsPersistence.save(server);
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

            // IMMEDIATELY teleport if IDLE to prevent suffocation damage
            if (runManager.getGameState() == RunState.IDLE) {
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
                        ServerWorld playerWorld = player.getServerWorld();
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
                        .withUnderline(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/start"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.literal("Start a new run").formatted(Formatting.GRAY)))))
                .append(Text.literal(" to begin.").formatted(Formatting.GRAY)), false);

        // Empty line
        player.sendMessage(Text.empty(), false);

        // Settings tip
        player.sendMessage(Text.empty().append(Text.literal("TIP: ").formatted(Formatting.YELLOW))
                .append(Text.literal("Customize your next run with ").formatted(Formatting.GRAY))
                .append(Text.literal("/chaos").setStyle(Style.EMPTY.withColor(Formatting.GOLD)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chaos"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.literal("Open run options").formatted(Formatting.GRAY)))))
                .append(Text.literal(".").formatted(Formatting.GRAY)), false);

        player.sendMessage(
                Text.empty().append(Text.literal("Having troubles? ").formatted(Formatting.GRAY))
                        .append(Text.literal("/settings")
                                .setStyle(Style.EMPTY.withColor(Formatting.AQUA)
                                        .withClickEvent(new ClickEvent(
                                                ClickEvent.Action.RUN_COMMAND, "/settings"))
                                        .withHoverEvent(
                                                new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                        Text.literal("Open info settings")
                                                                .formatted(Formatting.GRAY))))),
                false);

    }

    /**
     * Registers tick events for timer updates and periodic sync.
     */
    private static void registerTickEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            processDelayedTasks(server);

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager != null) {
                runManager.tick();
                if (runManager.isRunActive() && Settings.getInstance().isManhuntMode()) {
                    CompassTrackingHandler.tick(server);
                }
            }

            SharedJumpHandler.processJumpsAtTickEnd(server);
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

            // Allow all damage through - the actual death check happens in ServerPlayerEntityMixin
            // when health truly hits 0 (after armor/enchantment reductions are applied).
            // Previously we checked raw damage here, but that caused false positives since
            // 'amount' is before armor reduction (e.g., iron golem 15 raw → 7 actual with armor).
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
                        SoulLink.LOGGER.warn(
                                "Player {} reached 0 health despite mixin check - triggering death handler",
                                player.getName().getString());
                        if (Settings.getInstance().isManhuntMode()
                                && ManhuntManager.getInstance().isHunter(player)) {
                            handleHunterDeath(player, source, runManager);
                        } else {
                            handlePlayerDeath(player, source, runManager);
                        }
                        return;
                    }

                    ServerWorld playerWorld = player.getServerWorld();
                    if (playerWorld == null) {
                        return;
                    }

                    if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey())) {
                        return;
                    }

                    if (Settings.getInstance().isManhuntMode()
                            && ManhuntManager.getInstance().isHunter(player)) {
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
     * Handles Hunter death in Manhunt: broadcast, clear bad effects, switch to spectator, drop
     * non-compass items, 5s countdown, then respawn at run spawn with full stats and a new tracking
     * compass.
     *
     * @param player the hunter who died
     * @param source the damage source
     * @param runManager the run manager (used for spawn, run state, and overworld)
     */
    public static void handleHunterDeath(ServerPlayerEntity player, DamageSource source,
            RunManager runManager) {
        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        Text deathMessage = source.getDeathMessage(player);
        Text formatted = Text.empty().append(RunManager.getPrefix())
                .append(Text.literal("☠ ").formatted(Formatting.DARK_RED))
                .append(deathMessage.copy().formatted(Formatting.RED));
        server.getPlayerManager().broadcast(formatted, false);

        List<net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.effect.StatusEffect>> toRemove =
                player.getStatusEffects().stream()
                        .filter(e -> !e.getEffectType().value().isBeneficial())
                        .map(e -> e.getEffectType()).toList();
        toRemove.forEach(player::removeStatusEffect);

        player.changeGameMode(GameMode.SPECTATOR);

        ServerWorld world = player.getServerWorld();
        double x = player.getX(), y = player.getY(), z = player.getZ();

        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && !stack.isOf(Items.COMPASS)) {
                ItemEntity ent = new ItemEntity(world, x, y, z, stack.copy());
                ent.setVelocity(world.getRandom().nextGaussian() * 0.05,
                        world.getRandom().nextGaussian() * 0.05 + 0.2,
                        world.getRandom().nextGaussian() * 0.05);
                world.spawnEntity(ent);
            }
        }

        player.getInventory().clear();
        player.getEnderChestInventory().clear();

        for (int i = 5; i >= 1; i--) {
            final int c = i;
            scheduleDelayed((5 - i) * 20, () -> {
                if (player.isRemoved() || !runManager.isRunActive())
                    return;
                player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(String.valueOf(c))
                        .formatted(Formatting.RED, Formatting.BOLD)));
                player.networkHandler.sendPacket(new SubtitleS2CPacket(
                        Text.literal("Respawning...").formatted(Formatting.GRAY)));
            });
        }

        scheduleDelayed(5 * 20, () -> {
            if (player.isRemoved() || !runManager.isRunActive())
                return;

            player.networkHandler.sendPacket(new TitleS2CPacket(
                    Text.literal("RESPAWN").formatted(Formatting.GREEN, Formatting.BOLD)));

            player.changeGameMode(GameMode.SURVIVAL);

            ServerWorld targetWorld = runManager.getTemporaryOverworld();
            BlockPos targetPos = runManager.getSpawnPos();

            if (targetWorld != null && targetPos != null) {
                player.teleport(targetWorld, targetPos.getX() + 0.5, targetPos.getY(),
                        targetPos.getZ() + 0.5, 0.0f, 0.0f);
            }

            player.setHealth(player.getMaxHealth());
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5.0f);

            CompassTrackingHandler.giveTrackingCompass(player);

            SoulLink.LOGGER.info("Hunter {} respawned after death", player.getName().getString());
        });
    }

    /**
     * Schedule a task to run after a delay in ticks.
     */
    public static void scheduleDelayed(int delayTicks, Runnable task) {
        delayedTasks.add(new DelayedTask(delayTicks, task));
    }

    /**
     * Clears all pending delayed tasks. Called when starting a new run so that tasks from a
     * previous run (e.g. hunter respawn countdown) do not carry over.
     */
    public static void clearDelayedTasks() {
        delayedTasks.clear();
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
