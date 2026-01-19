package net.zenzty.soullink.server.run;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.mixin.server.EnderDragonFightAccessor;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.settings.Settings;

/**
 * Facade that coordinates run lifecycle using dedicated services. Manages game state transitions
 * and player coordination.
 */
public class RunManager {

    private static volatile RunManager instance;

    private final MinecraftServer server;
    private final WorldService worldService;
    private final SpawnFinder spawnFinder;
    private final PlayerTeleportService teleportService;

    // Game state
    private volatile RunState gameState = RunState.IDLE;

    // End dimension initialization flag (resets per run)
    private volatile boolean endInitialized = false;

    // ==================== MESSAGE FORMATTING ====================

    /**
     * Creates the [SoulLink] prefix with dark grey brackets and light red text.
     */
    public static Text getPrefix() {
        return Text.empty().append(Text.literal("[").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("SoulLink").formatted(Formatting.RED))
                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY));
    }

    /**
     * Creates a formatted message with the [SoulLink] prefix.
     */
    public static Text formatMessage(String message) {
        return Text.empty().append(getPrefix())
                .append(Text.literal(message).formatted(Formatting.GRAY));
    }

    /**
     * Creates a formatted message with a player name highlighted.
     */
    public static Text formatMessageWithPlayer(String beforePlayer, String playerName,
            String afterPlayer) {
        return Text.empty().append(getPrefix())
                .append(Text.literal(beforePlayer).formatted(Formatting.GRAY))
                .append(Text.literal(playerName).formatted(Formatting.WHITE))
                .append(Text.literal(afterPlayer).formatted(Formatting.GRAY));
    }

    /**
     * Creates a clickable text with underline and hover text.
     */
    public static Text formatClickable(String text, String command, String hoverText) {
        return Text.literal(text)
                .setStyle(Style.EMPTY.withColor(Formatting.GREEN).withUnderline(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.literal(hoverText).formatted(Formatting.GRAY))));
    }

    // ==================== LIFECYCLE ====================

    private RunManager(MinecraftServer server) {
        this.server = server;
        this.worldService = new WorldService(server);
        this.spawnFinder = new SpawnFinder();
        this.teleportService = new PlayerTeleportService(server);
    }

    public static synchronized void init(MinecraftServer server) {
        if (instance != null) {
            SoulLink.LOGGER.warn("RunManager already initialized!");
            return;
        }
        instance = new RunManager(server);
    }

    public static RunManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("RunManager not initialized");
        }
        return instance;
    }

    public static synchronized void cleanup() {
        RunManager currentInstance = instance;
        if (currentInstance != null) {
            currentInstance.worldService.deleteOldWorlds();
            currentInstance.deleteWorlds(true);
            instance = null;
        }
    }

    /**
     * Gets the ServerWorld for a player.
     */
    public static ServerWorld getPlayerWorld(ServerPlayerEntity player) {
        return player.getEntityWorld();
    }

    // ==================== RUN LIFECYCLE ====================

    /**
     * Starts a new run - creates temporary worlds and begins spawn search.
     */
    public void startRun() {
        if (gameState == RunState.RUNNING || gameState == RunState.GENERATING_WORLD) {
            SoulLink.LOGGER.warn("Attempted to start run while already running or generating!");
            return;
        }

        SoulLink.LOGGER.info("Starting new run...");

        // Clear any lingering Ender Dragon bossbar from previous run
        clearEnderDragonBossbar();

        // Apply any pending settings
        Settings.getInstance().applyPendingSettings();

        // Broadcast starting message
        server.getPlayerManager().broadcast(formatMessage("Generating new world..."), false);

        // Save old worlds for later deletion
        worldService.saveCurrentWorldsAsOld();

        // Create new temporary worlds
        long seed = worldService.createTemporaryWorlds();

        // Reset shared stats
        SharedStatsHandler.reset();

        // Reset End initialization flag
        endInitialized = false;



        // Reset spawn search and start generating
        spawnFinder.reset();
        gameState = RunState.GENERATING_WORLD;

        // Put all players in spectator mode
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.changeGameMode(GameMode.SPECTATOR);
        }

        SoulLink.LOGGER.info("World created with seed: {}, now searching for spawn...", seed);
    }

    /**
     * Called every server tick to update state.
     */
    public void tick() {
        // Handle incremental world generation
        if (gameState == RunState.GENERATING_WORLD) {
            ServerWorld overworld = worldService.getOverworld();
            if (overworld == null) {
                SoulLink.LOGGER.error("No overworld handle during generation!");
                gameState = RunState.IDLE;
                return;
            }

            if (spawnFinder.processStep(overworld, server)) {
                transitionToRunning();
            }
            return;
        }

        if (gameState != RunState.RUNNING) {
            return;
        }

        // Manually advance time in temporary overworld
        ServerWorld tempOverworld = worldService.getOverworld();
        if (tempOverworld != null) {
            tempOverworld.setTimeOfDay(tempOverworld.getTimeOfDay() + 1);
        }
    }

    /**
     * Transitions from GENERATING_WORLD to RUNNING.
     */
    private void transitionToRunning() {
        ServerWorld overworld = worldService.getOverworld();
        BlockPos spawnPos = spawnFinder.getSpawnPos();

        if (overworld == null)
            return;

        // Use fallback if no spawn found
        if (spawnPos == null) {
            spawnPos = new BlockPos(0, 64, 0);
            SoulLink.LOGGER.warn("Using fallback spawn at {}", spawnPos);
        }

        // Forceload chunks around spawn
        teleportService.forceloadSpawnChunks(overworld, spawnPos);

        gameState = RunState.RUNNING;

        // Create and assign scoreboard teams for Manhunt mode
        ManhuntManager manhuntManager = ManhuntManager.getInstance();
        manhuntManager.createTeams(server);
        manhuntManager.assignPlayersToTeams(server);

        // Teleport ALL players to spawn
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            teleportService.teleportToSpawn(player, overworld, spawnPos);
        }

        // Delete old worlds
        worldService.deleteOldWorlds();

        // Broadcast ready message
        server.getPlayerManager().broadcast(formatMessage("World ready! Good luck!"), false);

        SoulLink.LOGGER.info("World generation complete, run started");
    }

    /**
     * Deletes all temporary worlds.
     */
    public void deleteWorlds(boolean teleportPlayers) {
        if (teleportPlayers) {
            List<ServerPlayerEntity> allPlayers =
                    new ArrayList<>(server.getPlayerManager().getPlayerList());

            for (ServerPlayerEntity player : allPlayers) {
                ServerWorld playerWorld = getPlayerWorld(player);
                if (playerWorld != null && isTemporaryWorld(playerWorld.getRegistryKey())) {
                    teleportService.teleportToVanillaSpawn(player);
                }
            }
        }

        worldService.deleteCurrentWorlds();
    }

    /**
     * Handles a late-joining player during an active run. Late joiners become spectators - they
     * cannot participate until the next run.
     */
    public void teleportPlayerToRun(ServerPlayerEntity player) {
        if (gameState == RunState.GENERATING_WORLD) {
            player.changeGameMode(GameMode.SPECTATOR);
            player.getInventory().clear();
            player.clearStatusEffects();
            player.sendMessage(formatMessage("Finding spawn point, please wait..."), false);
            return;
        }

        if (gameState == RunState.RUNNING && spawnFinder.hasFoundSpawn()) {
            ServerWorld overworld = worldService.getOverworld();
            if (overworld != null) {
                // Late joiners become spectators - they cannot participate mid-run
                player.changeGameMode(GameMode.SPECTATOR);
                player.getInventory().clear();
                player.clearStatusEffects();

                // Teleport to spawn area so they can spectate
                BlockPos spawnPos = spawnFinder.getSpawnPos();
                player.teleport(overworld, spawnPos.getX() + 0.5, spawnPos.getY() + 10,
                        spawnPos.getZ() + 0.5, java.util.Set.of(), 0, 0, true);

                player.sendMessage(
                        formatMessage("A run is in progress. You are spectating until it ends."),
                        false);
                SoulLink.LOGGER.info("Late joiner {} set to spectator mode",
                        player.getName().getString());
            }
        }
    }

    // ==================== GAME END STATES ====================

    /**
     * Handles game over - all players died.
     */
    public synchronized void triggerGameOver() {
        if (gameState != RunState.RUNNING) {
            return;
        }

        SoulLink.LOGGER.info("Game Over triggered!");

        gameState = RunState.GAMEOVER;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (isInRun(player)) {
                player.changeGameMode(GameMode.SPECTATOR);
                player.getInventory().clear();

                ServerWorld world = getPlayerWorld(player);
                if (world != null) {
                    world.playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.ENTITY_WITHER_DEATH, SoundCategory.PLAYERS, 0.5f, 0.8f);
                }
            }
        }

        Text restartMessage = Text.empty().append(getPrefix())
                .append(Text.literal("All players are dead. Click ").formatted(Formatting.GRAY))
                .append(Text.literal("here").setStyle(Style.EMPTY.withColor(Formatting.BLUE)
                        .withUnderline(true).withClickEvent(new ClickEvent.RunCommand("/start"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.literal("Start a new attempt").formatted(Formatting.GRAY)))))
                .append(Text.literal(" or use ").formatted(Formatting.GRAY))
                .append(Text.literal("/start").formatted(Formatting.GOLD))
                .append(Text.literal(" to start a new attempt.").formatted(Formatting.GRAY));

        server.getPlayerManager().broadcast(restartMessage, false);
    }


    // ==================== HELPER METHODS ====================

    /**
     * Checks if a player is in the active run (in a temporary world).
     */
    private boolean isInRun(ServerPlayerEntity player) {
        ServerWorld world = getPlayerWorld(player);
        return world != null && isTemporaryWorld(world.getRegistryKey());
    }

    /**
     * Clears the Ender Dragon bossbar from all players. This is needed when starting a new run
     * before Minecraft has naturally cleaned up the dragon bossbar from a completed run.
     */
    private void clearEnderDragonBossbar() {
        // Check all worlds for an active dragon fight and clear its bossbar
        for (ServerWorld world : server.getWorlds()) {
            EnderDragonFight fight = world.getEnderDragonFight();
            if (fight != null) {
                try {
                    ServerBossBar bossBar = ((EnderDragonFightAccessor) fight).getBossBar();
                    if (bossBar != null) {
                        bossBar.clearPlayers();
                        SoulLink.LOGGER.debug("Cleared Ender Dragon bossbar from world: {}",
                                world.getRegistryKey().getValue());
                    }
                } catch (Exception e) {
                    SoulLink.LOGGER.warn("Failed to clear Ender Dragon bossbar: {}",
                            e.getMessage());
                }
            }
        }
    }

    /**
     * Teleports a player to the vanilla overworld spawn.
     */
    public void teleportToVanillaSpawn(ServerPlayerEntity player) {
        teleportService.teleportToVanillaSpawn(player);
    }

    public WorldService getWorldService() {
        return worldService;
    }

    // ==================== GETTERS ====================

    public RunState getGameState() {
        return gameState;
    }

    public boolean isRunActive() {
        return gameState == RunState.RUNNING;
    }

    public boolean isGameOver() {
        return gameState == RunState.GAMEOVER;
    }

    public boolean isEndInitialized() {
        return endInitialized;
    }

    public void setEndInitialized(boolean initialized) {
        this.endInitialized = initialized;
    }

    public ServerWorld getTemporaryOverworld() {
        return worldService.getOverworld();
    }

    public ServerWorld getTemporaryNether() {
        return worldService.getNether();
    }

    public ServerWorld getTemporaryEnd() {
        return worldService.getEnd();
    }

    public RegistryKey<World> getTemporaryOverworldKey() {
        return worldService.getOverworldKey();
    }

    public RegistryKey<World> getTemporaryNetherKey() {
        return worldService.getNetherKey();
    }

    public RegistryKey<World> getTemporaryEndKey() {
        return worldService.getEndKey();
    }

    public boolean isTemporaryWorld(RegistryKey<World> worldKey) {
        return worldService.isTemporaryWorld(worldKey);
    }

    public ServerWorld getLinkedNetherWorld(ServerWorld fromWorld) {
        return worldService.getLinkedNetherWorld(fromWorld);
    }

    public net.minecraft.util.math.BlockPos getSpawnPos() {
        return spawnFinder != null ? spawnFinder.getSpawnPos() : null;
    }

    public MinecraftServer getServer() {
        return server;
    }


}
