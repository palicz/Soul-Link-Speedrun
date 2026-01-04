package net.zenzty.soullink;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
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
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionTypes;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.fantasy.RuntimeWorldHandle;

import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Manages the lifecycle of temporary Fantasy worlds, game timer, and game state.
 * Handles world creation, deletion, portal linking, and player teleportation.
 */
public class RunManager {
    
    public enum GameState {
        IDLE,
        RUNNING,
        GAMEOVER
    }
    
    private static RunManager instance;
    
    // ==================== MESSAGE FORMATTING ====================
    
    /**
     * Creates the [SoulLink] prefix with dark grey brackets and light red text.
     */
    public static Text getPrefix() {
        return Text.empty()
            .append(Text.literal("[").formatted(Formatting.DARK_GRAY))
            .append(Text.literal("SoulLink").formatted(Formatting.RED))
            .append(Text.literal("] ").formatted(Formatting.DARK_GRAY));
    }
    
    /**
     * Creates a formatted message with the [SoulLink] prefix.
     * @param message The message content (light gray)
     */
    public static Text formatMessage(String message) {
        return Text.empty()
            .append(getPrefix())
            .append(Text.literal(message).formatted(Formatting.GRAY));
    }
    
    /**
     * Creates a formatted message with a player name highlighted.
     * @param beforePlayer Text before the player name
     * @param playerName The player name (white)
     * @param afterPlayer Text after the player name
     */
    public static Text formatMessageWithPlayer(String beforePlayer, String playerName, String afterPlayer) {
        return Text.empty()
            .append(getPrefix())
            .append(Text.literal(beforePlayer).formatted(Formatting.GRAY))
            .append(Text.literal(playerName).formatted(Formatting.WHITE))
            .append(Text.literal(afterPlayer).formatted(Formatting.GRAY));
    }
    
    /**
     * Creates a clickable text with underline and hover text.
     */
    public static Text formatClickable(String text, String command, String hoverText) {
        return Text.literal(text)
            .setStyle(Style.EMPTY
                .withColor(Formatting.GREEN)
                .withUnderline(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(
                    Text.literal(hoverText).formatted(Formatting.GRAY)
                ))
            );
    }
    
    // ==================== END MESSAGE FORMATTING ====================
    
    private final MinecraftServer server;
    private Fantasy fantasy;
    
    // Temporary world handles
    private RuntimeWorldHandle overworldHandle;
    private RuntimeWorldHandle netherHandle;
    private RuntimeWorldHandle endHandle;
    
    // Timer tracking
    private long startTimeMillis;
    private long elapsedTimeMillis;
    private boolean timerRunning;
    private boolean timerStartedThisRun;
    
    // Two-phase timer start: wait for ground, then wait for input
    private boolean waitingForGround;
    private boolean waitingForInput;
    private int groundedTicks; // Count ticks player has been grounded (for stability)
    private ServerPlayerEntity trackedPlayer;
    private double trackedX, trackedY, trackedZ;
    private float trackedYaw, trackedPitch;
    
    // Game state
    private GameState gameState = GameState.IDLE;
    
    // End dimension initialization flag (resets per run)
    private boolean endInitialized = false;
    
    // Current run seed
    private long currentSeed;
    
    private RunManager(MinecraftServer server) {
        this.server = server;
        this.fantasy = Fantasy.get(server);
    }
    
    public static void init(MinecraftServer server) {
        instance = new RunManager(server);
    }
    
    public static RunManager getInstance() {
        return instance;
    }
    
    public static void cleanup() {
        if (instance != null) {
            instance.deleteWorlds();
            instance = null;
        }
    }
    
    /**
     * Gets the ServerWorld for a player.
     * In Yarn 1.21.11, ServerPlayerEntity.getEntityWorld() returns ServerWorld directly.
     */
    public static ServerWorld getPlayerWorld(ServerPlayerEntity player) {
        return player.getEntityWorld();
    }
    
    /**
     * Gets spawn position for a world.
     * Searches in a spiral pattern to find solid land (not ocean).
     */
    private BlockPos getWorldSpawnPos(ServerWorld world) {
        // Search in a spiral pattern with increasing radius
        // Check many more locations to avoid ocean spawns
        int maxRadius = 500; // Search up to 500 blocks out
        int step = 32; // Check every 32 blocks (2 chunks)
        
        for (int radius = 0; radius <= maxRadius; radius += step) {
            // Check points at this radius in a square pattern
            for (int side = 0; side < 4; side++) {
                for (int offset = -radius; offset <= radius; offset += step) {
                    int x, z;
                    switch (side) {
                        case 0: x = offset; z = -radius; break; // North edge
                        case 1: x = radius; z = offset; break;  // East edge
                        case 2: x = offset; z = radius; break;  // South edge
                        default: x = -radius; z = offset; break; // West edge
                    }
                    
                    // Skip if we've already checked this point (corners)
                    if (radius > 0 && Math.abs(x) != radius && Math.abs(z) != radius) continue;
                    
                    BlockPos safePos = checkSpawnLocation(world, x, z);
                    if (safePos != null) {
                        SoulLink.LOGGER.info("Found land spawn at {} after searching radius {}", safePos, radius);
                        return safePos;
                    }
                }
            }
        }
        
        // Absolute fallback - create a platform at 0,0
        SoulLink.LOGGER.warn("Could not find land spawn, creating platform at origin");
        return createSpawnPlatform(world, 0, 0);
    }
    
    /**
     * Checks if a location is suitable for spawning (solid ground, not water/lava).
     */
    private BlockPos checkSpawnLocation(ServerWorld world, int x, int z) {
        // Force chunk to load
        world.getChunk(x >> 4, z >> 4);
        
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        
        // Y must be reasonable (not void, not too high)
        if (y < 50 || y > 200) {
            return null;
        }
        
        BlockPos groundPos = new BlockPos(x, y - 1, z);
        BlockPos standPos = new BlockPos(x, y, z);
        BlockState groundState = world.getBlockState(groundPos);
        BlockState standState = world.getBlockState(standPos);
        
        // Check multiple conditions for valid spawn:
        // 1. Ground must be solid
        // 2. Ground must not be water, ice, or lava
        // 3. Standing position must not be water or lava
        if (groundState.isSolidBlock(world, groundPos) && 
            !groundState.isOf(Blocks.WATER) && 
            !groundState.isOf(Blocks.LAVA) &&
            !groundState.isOf(Blocks.ICE) &&
            !groundState.isOf(Blocks.PACKED_ICE) &&
            !groundState.isOf(Blocks.BLUE_ICE) &&
            !standState.isOf(Blocks.WATER) &&
            !standState.isOf(Blocks.LAVA)) {
            return new BlockPos(x, y, z);
        }
        
        return null;
    }
    
    /**
     * Creates a small platform for spawning when no land is found.
     */
    private BlockPos createSpawnPlatform(ServerWorld world, int x, int z) {
        // Force chunk to load
        world.getChunk(x >> 4, z >> 4);
        
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y < 62) y = 62;
        
        // Create a 3x3 stone platform
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONE.getDefaultState());
            }
        }
        
        // Clear space above
        for (int dy = 1; dy <= 3; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.setBlockState(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.getDefaultState());
                }
            }
        }
        
        return new BlockPos(x, y + 1, z);
    }
    
    /**
     * Starts a new run - creates temporary worlds and teleports all players.
     */
    public void startRun() {
        if (gameState == GameState.RUNNING) {
            SoulLink.LOGGER.warn("Attempted to start run while already running!");
            return;
        }
        
        SoulLink.LOGGER.info("Starting new Roguelike Speedrun...");
        
        // Show "Generating world..." title to all players with fade-in
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            // Set fade times: fadeIn=20 ticks (1s), stay=200 ticks (10s), fadeOut=10 ticks (0.5s)
            player.networkHandler.sendPacket(new TitleFadeS2CPacket(20, 200, 10));
            player.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal("Generating world...").formatted(Formatting.GRAY)
            ));
        }
        
        // Delete old worlds first to free disk space
        deleteWorlds();
        
        // Generate new seed for this run
        currentSeed = new Random().nextLong();
        
        // Create new temporary worlds
        createTemporaryWorlds();
        
        // Reset shared stats
        SharedStatsHandler.reset();
        
        // Reset End initialization flag for the new run
        endInitialized = false;
        
        // Set game state to running
        gameState = GameState.RUNNING;
        
        // Reset timer state - timer will start after player lands and moves
        timerStartedThisRun = false;
        timerRunning = false;
        waitingForGround = false;
        waitingForInput = false;
        groundedTicks = 0;
        trackedPlayer = null;
        elapsedTimeMillis = 0;
        startTimeMillis = 0;
        
        // Teleport all players to the new overworld
        teleportAllPlayersToSpawn();
        
        SoulLink.LOGGER.info("Roguelike Speedrun started with seed: {}", currentSeed);
    }
    
    /**
     * Creates the three temporary dimensions: Overworld, Nether, and End.
     */
    private void createTemporaryWorlds() {
        // Get the vanilla overworld for reference
        ServerWorld vanillaOverworld = server.getOverworld();
        
        // Create temporary Overworld
        RuntimeWorldConfig overworldConfig = new RuntimeWorldConfig()
                .setDimensionType(DimensionTypes.OVERWORLD)
                .setDifficulty(Difficulty.NORMAL)
                .setSeed(currentSeed)
                .setGenerator(vanillaOverworld.getChunkManager().getChunkGenerator());
        
        overworldHandle = fantasy.openTemporaryWorld(overworldConfig);
        SoulLink.LOGGER.info("Created temporary overworld: {}", overworldHandle.getRegistryKey().getValue());
        
        // Create temporary Nether
        ServerWorld vanillaNether = server.getWorld(World.NETHER);
        if (vanillaNether != null) {
            RuntimeWorldConfig netherConfig = new RuntimeWorldConfig()
                    .setDimensionType(DimensionTypes.THE_NETHER)
                    .setDifficulty(Difficulty.NORMAL)
                    .setSeed(currentSeed)
                    .setGenerator(vanillaNether.getChunkManager().getChunkGenerator());
            
            netherHandle = fantasy.openTemporaryWorld(netherConfig);
            SoulLink.LOGGER.info("Created temporary nether: {}", netherHandle.getRegistryKey().getValue());
        }
        
        // Create temporary End
        ServerWorld vanillaEnd = server.getWorld(World.END);
        if (vanillaEnd != null) {
            RuntimeWorldConfig endConfig = new RuntimeWorldConfig()
                    .setDimensionType(DimensionTypes.THE_END)
                    .setDifficulty(Difficulty.NORMAL)
                    .setSeed(currentSeed)
                    .setGenerator(vanillaEnd.getChunkManager().getChunkGenerator());
            
            endHandle = fantasy.openTemporaryWorld(endConfig);
            SoulLink.LOGGER.info("Created temporary end: {}", endHandle.getRegistryKey().getValue());
        }
    }
    
    /**
     * Deletes all temporary worlds to free disk space.
     */
    public void deleteWorlds() {
        // First, teleport ALL players to vanilla spawn (copy list to avoid ConcurrentModificationException)
        List<ServerPlayerEntity> allPlayers = new java.util.ArrayList<>(server.getPlayerManager().getPlayerList());
        for (ServerPlayerEntity player : allPlayers) {
            teleportToVanillaSpawn(player);
        }
        
        // Now delete the worlds
        if (overworldHandle != null) {
            try {
                overworldHandle.delete();
                SoulLink.LOGGER.info("Deleted temporary overworld");
            } catch (Exception e) {
                SoulLink.LOGGER.error("Failed to delete temporary overworld", e);
            }
            overworldHandle = null;
        }
        
        if (netherHandle != null) {
            try {
                netherHandle.delete();
                SoulLink.LOGGER.info("Deleted temporary nether");
            } catch (Exception e) {
                SoulLink.LOGGER.error("Failed to delete temporary nether", e);
            }
            netherHandle = null;
        }
        
        if (endHandle != null) {
            try {
                endHandle.delete();
                SoulLink.LOGGER.info("Deleted temporary end");
            } catch (Exception e) {
                SoulLink.LOGGER.error("Failed to delete temporary end", e);
            }
            endHandle = null;
        }
    }
    
    /**
     * Teleports a player to the vanilla overworld spawn.
     */
    private void teleportToVanillaSpawn(ServerPlayerEntity player) {
        ServerWorld overworld = server.getOverworld();
        BlockPos spawnPos = getWorldSpawnPos(overworld);
        player.teleport(overworld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 
            Set.of(), player.getYaw(), player.getPitch(), true);
    }
    
    /**
     * Teleports all players to the temporary overworld spawn point.
     */
    private void teleportAllPlayersToSpawn() {
        if (overworldHandle == null) {
            SoulLink.LOGGER.error("Cannot teleport players - no temporary overworld exists!");
            return;
        }
        
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            teleportPlayerToRun(player);
        }
    }
    
    /**
     * Teleports a specific player to the current run and syncs their stats.
     * Used for both initial teleportation and late joiners.
     */
    public void teleportPlayerToRun(ServerPlayerEntity player) {
        if (overworldHandle == null) {
            SoulLink.LOGGER.warn("No active run to teleport player to");
            return;
        }
        
        ServerWorld tempOverworld = overworldHandle.asWorld();
        BlockPos spawnPos = getWorldSpawnPos(tempOverworld);
        
        // Set game mode to survival
        player.changeGameMode(GameMode.SURVIVAL);
        
        // FULL PLAYER RESET - clear everything from previous run
        resetPlayer(player);
        
        // Teleport to spawn
        player.teleport(tempOverworld, spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5, 
            Set.of(), 0, 0, true);
        
        // Clear the "Generating world..." title
        player.networkHandler.sendPacket(new ClearTitleS2CPacket(false));
        
        // Sync stats from shared handler
        SharedStatsHandler.syncPlayerToSharedStats(player);
        
        // Set up two-phase timer start for first player
        if (!timerStartedThisRun && !waitingForGround && !waitingForInput) {
            waitingForGround = true;
            groundedTicks = 0;
            trackedPlayer = player;
            SoulLink.LOGGER.info("Waiting for {} to land on ground...", player.getName().getString());
        }
        
        SoulLink.LOGGER.info("Teleported {} to temporary overworld at {}", player.getName().getString(), spawnPos);
    }
    
    /**
     * Fully resets a player for a new run.
     * Clears inventory, effects, XP, and resets health/hunger.
     */
    private void resetPlayer(ServerPlayerEntity player) {
        // Clear inventory
        player.getInventory().clear();
        
        // Clear all status effects
        player.clearStatusEffects();
        
        // Reset experience
        player.setExperienceLevel(0);
        player.setExperiencePoints(0);
        
        // Reset health and hunger
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5.0f);
        
        // Clear ender chest
        player.getEnderChestInventory().clear();
        
        // Reset fire and freeze ticks
        player.setFireTicks(0);
        player.setFrozenTicks(0);
        
        // Set to survival mode
        player.changeGameMode(GameMode.SURVIVAL);
        
        SoulLink.LOGGER.info("Reset player {} for new run", player.getName().getString());
    }
    
    /**
     * Starts the game timer.
     */
    /**
     * Stops the game timer.
     */
    public void stopTimer() {
        if (timerRunning) {
            elapsedTimeMillis = System.currentTimeMillis() - startTimeMillis;
            timerRunning = false;
        }
    }
    
    /**
     * Gets the current elapsed time in milliseconds.
     */
    public long getElapsedTimeMillis() {
        if (timerRunning) {
            long elapsed = System.currentTimeMillis() - startTimeMillis;
            // Don't return negative time (timer hasn't officially started yet)
            return Math.max(0, elapsed);
        }
        return elapsedTimeMillis;
    }
    
    /**
     * Formats the elapsed time as HH:MM:SS.
     */
    public String getFormattedTime() {
        long elapsed = getElapsedTimeMillis();
        long seconds = (elapsed / 1000) % 60;
        long minutes = (elapsed / (1000 * 60)) % 60;
        long hours = (elapsed / (1000 * 60 * 60));
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
    
    /**
     * Called every server tick to update the timer display.
     */
    public void tick() {
        if (gameState != GameState.RUNNING) {
            return;
        }
        
        // Phase 1: Wait for player to be on ground (no message shown)
        if (waitingForGround) {
            checkForGrounded();
            return;
        }
        
        // Phase 2: Wait for player input (movement or camera)
        if (waitingForInput) {
            if (checkForInput()) {
                // Player moved or looked around - START THE TIMER!
                waitingForInput = false;
                timerStartedThisRun = true;
                startTimeMillis = System.currentTimeMillis();
                timerRunning = true;
                trackedPlayer = null;
                SoulLink.LOGGER.info("Player input detected! Timer started at 00:00:00");
            } else {
                // Show ready message - timer at 00:00:00 waiting for input
                if (server.getTicks() % 10 == 0) {
                    Text readyText = Text.empty()
                        .append(Text.literal("00:00:00").formatted(Formatting.WHITE))
                        .append(Text.literal(" - Move to start").formatted(Formatting.GRAY));
                    for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                        player.sendMessage(readyText, true);
                    }
                }
            }
            return;
        }
        
        if (!timerRunning) {
            return;
        }
        
        // Update action bar every 10 ticks (0.5 seconds) for performance
        if (server.getTicks() % 10 == 0) {
            Text actionBarText = Text.literal(getFormattedTime()).formatted(Formatting.WHITE);
            
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                player.sendMessage(actionBarText, true);
            }
        }
    }
    
    /**
     * Phase 1: Check if the tracked player is on the ground and stable.
     */
    private void checkForGrounded() {
        if (trackedPlayer == null || trackedPlayer.isDisconnected()) {
            // Find a new player to track
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) {
                return;
            }
            trackedPlayer = players.get(0);
            groundedTicks = 0;
        }
        
        // Check if player is on ground
        if (trackedPlayer.isOnGround()) {
            groundedTicks++;
            
            // Require 10 ticks (0.5 seconds) of being grounded for stability
            if (groundedTicks >= 10) {
                // Player is stable on ground - move to phase 2
                waitingForGround = false;
                waitingForInput = true;
                
                // Capture current position and look direction
                trackedX = trackedPlayer.getX();
                trackedY = trackedPlayer.getY();
                trackedZ = trackedPlayer.getZ();
                trackedYaw = trackedPlayer.getYaw();
                trackedPitch = trackedPlayer.getPitch();
                
                // Play arrival sound for all players
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    ServerWorld world = getPlayerWorld(player);
                    if (world != null) {
                        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.7f, 1.2f);
                    }
                }
                
                SoulLink.LOGGER.info("Player grounded! Now waiting for input to start timer...");
            }
        } else {
            // Reset counter if player leaves ground
            groundedTicks = 0;
        }
    }
    
    /**
     * Phase 2: Check if the player has moved or looked around.
     */
    private boolean checkForInput() {
        if (trackedPlayer == null || trackedPlayer.isDisconnected()) {
            // Find a new player to track
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) {
                return false;
            }
            trackedPlayer = players.get(0);
            // Re-capture their position
            trackedX = trackedPlayer.getX();
            trackedY = trackedPlayer.getY();
            trackedZ = trackedPlayer.getZ();
            trackedYaw = trackedPlayer.getYaw();
            trackedPitch = trackedPlayer.getPitch();
            return false;
        }
        
        // Check for horizontal movement (ignore Y to avoid false positives from small terrain adjustments)
        double dx = Math.abs(trackedPlayer.getX() - trackedX);
        double dz = Math.abs(trackedPlayer.getZ() - trackedZ);
        
        // Check for look direction change
        float dYaw = Math.abs(trackedPlayer.getYaw() - trackedYaw);
        float dPitch = Math.abs(trackedPlayer.getPitch() - trackedPitch);
        
        // Handle yaw wrapping (e.g., 359 to 1 degrees)
        if (dYaw > 180) dYaw = 360 - dYaw;
        
        // Thresholds for detecting intentional input
        boolean hasMoved = dx > 0.05 || dz > 0.05;  // Moved more than 0.05 blocks horizontally
        boolean hasLooked = dYaw > 1.0f || dPitch > 1.0f;  // Rotated more than 1 degree
        
        return hasMoved || hasLooked;
    }
    
    /**
     * Decrements the grace period counter each tick.
     */
    
    /**
     * Handles game over - all players died.
     */
    public void triggerGameOver() {
        if (gameState != GameState.RUNNING) {
            return;
        }
        
        SoulLink.LOGGER.info("Game Over triggered!");
        
        stopTimer();
        gameState = GameState.GAMEOVER;
        
        String finalTime = getFormattedTime();
        
        // Set all players to spectator mode
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.changeGameMode(GameMode.SPECTATOR);
            
            // Play game over sound
            ServerWorld world = getPlayerWorld(player);
            if (world != null) {
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_WITHER_DEATH, SoundCategory.PLAYERS, 0.5f, 0.8f);
            }
            
            // Send GAME OVER title
            player.networkHandler.sendPacket(
                new TitleS2CPacket(
                    Text.literal("GAME OVER").formatted(Formatting.RED, Formatting.BOLD)
                )
            );
            
            // Send subtitle with just the timer in white
            player.networkHandler.sendPacket(
                new SubtitleS2CPacket(
                    Text.literal(finalTime).formatted(Formatting.WHITE)
                )
            );
        }
        
        // Send clickable restart message with proper formatting
        Text restartMessage = Text.empty()
            .append(getPrefix())
            .append(Text.literal("All players are dead. Click ").formatted(Formatting.GRAY))
            .append(Text.literal("here")
                .setStyle(Style.EMPTY
                    .withColor(Formatting.BLUE)
                    .withUnderline(true)
                    .withClickEvent(new ClickEvent.RunCommand("/start"))
                    .withHoverEvent(new HoverEvent.ShowText(
                        Text.literal("Start a new attempt").formatted(Formatting.GRAY)
                    ))
                ))
            .append(Text.literal(" or use ").formatted(Formatting.GRAY))
            .append(Text.literal("/start").formatted(Formatting.GOLD))
            .append(Text.literal(" to start a new attempt.").formatted(Formatting.GRAY));
        
        server.getPlayerManager().broadcast(restartMessage, false);
    }
    
    /**
     * Handles victory - Ender Dragon killed.
     */
    public void triggerVictory() {
        if (gameState != GameState.RUNNING) {
            return;
        }
        
        SoulLink.LOGGER.info("Victory! Dragon defeated!");
        
        stopTimer();
        gameState = GameState.GAMEOVER;
        
        String finalTime = getFormattedTime();
        
        // Broadcast victory to all players
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            // Play victory sound
            ServerWorld world = getPlayerWorld(player);
            if (world != null) {
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.0f);
            }
            
            // Send VICTORY title
            player.networkHandler.sendPacket(
                new TitleS2CPacket(
                    Text.literal("VICTORY").formatted(Formatting.GOLD, Formatting.BOLD)
                )
            );
            
            // Send subtitle with just the timer in white
            player.networkHandler.sendPacket(
                new SubtitleS2CPacket(
                    Text.literal(finalTime).formatted(Formatting.WHITE)
                )
            );
        }
        
        // Broadcast final time to chat with proper formatting
        Text victoryMessage = Text.empty()
            .append(getPrefix())
            .append(Text.literal("Dragon defeated in ").formatted(Formatting.GRAY))
            .append(Text.literal(finalTime).formatted(Formatting.WHITE));
        server.getPlayerManager().broadcast(victoryMessage, false);
        
        // Send clickable restart message with proper formatting
        Text clickableHere = Text.literal("here")
            .setStyle(Style.EMPTY
                .withColor(Formatting.AQUA)
                .withUnderline(true)
                .withClickEvent(new ClickEvent.RunCommand("/start"))
                .withHoverEvent(new HoverEvent.ShowText(
                    Text.literal("Click to start a new run!").formatted(Formatting.GRAY)
                ))
            );
        
        Text restartMessage = Text.empty()
            .append(getPrefix())
            .append(Text.literal("Victory! Click ").formatted(Formatting.GRAY))
            .append(clickableHere)
            .append(Text.literal(" or use ").formatted(Formatting.GRAY))
            .append(Text.literal("/start").formatted(Formatting.GOLD))
            .append(Text.literal(" to challenge again.").formatted(Formatting.GRAY));
        server.getPlayerManager().broadcast(restartMessage, false);
    }
    
    // Getters
    
    public GameState getGameState() {
        return gameState;
    }
    
    public boolean isRunActive() {
        return gameState == GameState.RUNNING;
    }
    
    public boolean isGameOver() {
        return gameState == GameState.GAMEOVER;
    }
    
    public boolean isEndInitialized() {
        return endInitialized;
    }
    
    public void setEndInitialized(boolean initialized) {
        this.endInitialized = initialized;
    }
    
    public ServerWorld getTemporaryOverworld() {
        return overworldHandle != null ? overworldHandle.asWorld() : null;
    }
    
    public ServerWorld getTemporaryNether() {
        return netherHandle != null ? netherHandle.asWorld() : null;
    }
    
    public ServerWorld getTemporaryEnd() {
        return endHandle != null ? endHandle.asWorld() : null;
    }
    
    public RegistryKey<World> getTemporaryOverworldKey() {
        return overworldHandle != null ? overworldHandle.getRegistryKey() : null;
    }
    
    public RegistryKey<World> getTemporaryNetherKey() {
        return netherHandle != null ? netherHandle.getRegistryKey() : null;
    }
    
    public RegistryKey<World> getTemporaryEndKey() {
        return endHandle != null ? endHandle.getRegistryKey() : null;
    }
    
    /**
     * Checks if a world key belongs to one of our temporary dimensions.
     */
    public boolean isTemporaryWorld(RegistryKey<World> worldKey) {
        if (worldKey == null) return false;
        
        RegistryKey<World> tempOverworld = getTemporaryOverworldKey();
        RegistryKey<World> tempNether = getTemporaryNetherKey();
        RegistryKey<World> tempEnd = getTemporaryEndKey();
        
        return worldKey.equals(tempOverworld) || 
               worldKey.equals(tempNether) || 
               worldKey.equals(tempEnd);
    }
    
    /**
     * Gets the linked nether world for portal travel from a given world.
     * If in temp overworld, returns temp nether. If in temp nether, returns temp overworld.
     */
    public ServerWorld getLinkedNetherWorld(ServerWorld fromWorld) {
        if (fromWorld == null) return null;
        
        RegistryKey<World> fromKey = fromWorld.getRegistryKey();
        
        if (fromKey.equals(getTemporaryOverworldKey())) {
            return getTemporaryNether();
        } else if (fromKey.equals(getTemporaryNetherKey())) {
            return getTemporaryOverworld();
        }
        
        return null;
    }
    
    public MinecraftServer getServer() {
        return server;
    }
}
