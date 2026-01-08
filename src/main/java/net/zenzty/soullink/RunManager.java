package net.zenzty.soullink;

import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
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
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.rule.GameRules;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.fantasy.RuntimeWorldHandle;

import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Manages the lifecycle of temporary Fantasy worlds, game timer, and game state.
 * Handles world creation, deletion, portal linking, and player teleportation.
 * 
 * Now uses incremental spawn search to prevent server freezes during world generation.
 */
public class RunManager {
    
    public enum GameState {
        IDLE,              // No active run
        GENERATING_WORLD,  // World created, players in spectator, searching for spawn
        RUNNING,           // Game in progress
        GAMEOVER           // Game ended (victory or defeat)
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
    
    // Old world handles (to delete after teleporting to new world)
    private RuntimeWorldHandle oldOverworldHandle;
    private RuntimeWorldHandle oldNetherHandle;
    private RuntimeWorldHandle oldEndHandle;
    
    // Incremental spawn search state
    private int searchRadius = 0;
    private int searchSide = 0;
    private int searchOffset = 0;
    private BlockPos validSpawnPos = null;
    private static final int MAX_SEARCH_RADIUS = 500;
    private static final int SEARCH_STEP = 32;
    private static final int CHECKS_PER_TICK = 5; // Number of spots to check per tick
    
    // Timer tracking
    private long startTimeMillis;
    private long elapsedTimeMillis;
    private boolean timerRunning;
    private boolean timerStartedThisRun;
    
    // Timer start: wait for player input (movement or camera)
    private boolean waitingForInput;
    private ServerPlayerEntity trackedPlayer;
    private double trackedX, trackedZ;
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
            instance.deleteOldWorlds(); // Clean up any pending old worlds
            instance.deleteWorlds(true); // Teleport players on server shutdown
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
    
    // ==================== BIOME-OPTIMIZED SPAWN SEARCH ====================
    
    /**
     * Checks if coordinates are in an ocean biome WITHOUT loading the chunk.
     * This is a massive optimization - avoids generating chunks just to check if they're water.
     */
    private boolean isOceanBiome(ServerWorld world, int x, int z) {
        try {
            // Use BiomeAccess to check biome without loading chunks
            BiomeAccess biomeAccess = world.getBiomeAccess();
            // Sample at y=64 (sea level) for accurate biome check
            BlockPos samplePos = new BlockPos(x, 64, z);
            RegistryEntry<Biome> biome = biomeAccess.getBiome(samplePos);
            
            // Check if it's any type of ocean or deep ocean
            return biome.isIn(BiomeTags.IS_OCEAN) || biome.isIn(BiomeTags.IS_DEEP_OCEAN);
        } catch (Exception e) {
            // If biome check fails, don't skip - let it try to load
            return false;
        }
    }
    
    /**
     * Checks if a location is suitable for spawning (solid ground, not water/lava).
     * Now optimized to check biome first before loading chunks.
     */
    private BlockPos checkSpawnLocation(ServerWorld world, int x, int z) {
        // OPTIMIZATION: Check biome first before loading the chunk
        if (isOceanBiome(world, x, z)) {
            return null; // Skip oceans entirely without loading chunks!
        }
        
        // Force chunk to load (only for non-ocean biomes now)
        world.getChunk(x >> 4, z >> 4);
        
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        
        // Y must be reasonable (not void, not too high)
        if (y < 50 || y > 200) {
            return null;
        }
        
        BlockPos groundPos = new BlockPos(x, y - 1, z);
        BlockPos standPos = new BlockPos(x, y, z);
        BlockPos headPos = new BlockPos(x, y + 1, z);
        BlockState groundState = world.getBlockState(groundPos);
        BlockState standState = world.getBlockState(standPos);
        BlockState headState = world.getBlockState(headPos);
        
        // Check multiple conditions for valid spawn:
        // 1. Ground must be solid
        // 2. Ground must not be water, ice, or lava
        // 3. Standing position must be air/passable (not solid)
        // 4. Head position must be air/passable (2 blocks of headroom)
        if (groundState.isSolidBlock(world, groundPos) && 
            !groundState.isOf(Blocks.WATER) && 
            !groundState.isOf(Blocks.LAVA) &&
            !groundState.isOf(Blocks.ICE) &&
            !groundState.isOf(Blocks.PACKED_ICE) &&
            !groundState.isOf(Blocks.BLUE_ICE) &&
            !standState.isSolidBlock(world, standPos) &&
            !standState.isOf(Blocks.WATER) &&
            !standState.isOf(Blocks.LAVA) &&
            !headState.isSolidBlock(world, headPos) &&
            !headState.isOf(Blocks.WATER) &&
            !headState.isOf(Blocks.LAVA)) {
            return new BlockPos(x, y, z);
        }
        
        return null;
    }
    
    /**
     * Gets the next search position in the spiral pattern.
     * Returns null if we've exhausted all search positions.
     */
    private int[] getNextSearchPos() {
        while (searchRadius <= MAX_SEARCH_RADIUS) {
            // Handle the center point (radius 0)
            if (searchRadius == 0) {
                searchRadius = SEARCH_STEP;
                return new int[]{0, 0};
            }
            
            // Calculate position based on current side and offset
            int x, z;
            switch (searchSide) {
                case 0: x = searchOffset; z = -searchRadius; break; // North edge
                case 1: x = searchRadius; z = searchOffset; break;  // East edge
                case 2: x = searchOffset; z = searchRadius; break;  // South edge
                default: x = -searchRadius; z = searchOffset; break; // West edge
            }
            
            // Move to next position
            searchOffset += SEARCH_STEP;
            
            // Check if we've completed this side
            if (searchOffset > searchRadius) {
                searchOffset = -searchRadius;
                searchSide++;
                
                // Check if we've completed all sides at this radius
                if (searchSide >= 4) {
                    searchSide = 0;
                    searchOffset = -searchRadius - SEARCH_STEP; // Will be incremented at start
                    searchRadius += SEARCH_STEP;
                }
            }
            
            // Skip corner duplicates
            if (searchRadius > SEARCH_STEP && Math.abs(x) != searchRadius && Math.abs(z) != searchRadius) {
                continue;
            }
            
            return new int[]{x, z};
        }
        
        return null; // Exhausted search
    }
    
    /**
     * Resets the spawn search state for a new search.
     */
    private void resetSpawnSearch() {
        searchRadius = 0;
        searchSide = 0;
        searchOffset = -SEARCH_STEP;
        validSpawnPos = null;
    }
    
    /**
     * Processes one step of the generation (checks a few spawn locations per tick).
     * Called during GENERATING_WORLD state.
     */
    private void processGenerationStep() {
        if (overworldHandle == null) {
            SoulLink.LOGGER.error("No overworld handle during generation!");
            gameState = GameState.IDLE;
            return;
        }
        
        ServerWorld world = overworldHandle.asWorld();
        int checksThisTick = 0;
        
        // Check multiple spots per tick to speed up without freezing
        while (checksThisTick < CHECKS_PER_TICK) {
            int[] pos = getNextSearchPos();
            
            if (pos == null) {
                // Exhausted all search positions - no valid spawn found
                transitionToRunning();
                return;
            }
            
            BlockPos candidate = checkSpawnLocation(world, pos[0], pos[1]);
            if (candidate != null) {
                // Found valid spawn!
                validSpawnPos = candidate;
                SoulLink.LOGGER.info("Found land spawn at {} after searching radius {}", candidate, searchRadius);
                transitionToRunning();
                return;
            }
            
            checksThisTick++;
        }
        
        // Update action bar with progress for all players
        if (server.getTicks() % 10 == 0) {
            int progress = Math.min(100, (searchRadius * 100) / MAX_SEARCH_RADIUS);
            Text statusText = Text.empty()
                .append(Text.literal("⟳ ").formatted(Formatting.GRAY))
                .append(Text.literal("Finding spawn... " + progress + "%").formatted(Formatting.GRAY));
            
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                player.sendMessage(statusText, true);
            }
        }
    }
    
    /**
     * Transitions from GENERATING_WORLD to RUNNING.
     * Forceloads chunks around spawn, teleports all players, then deletes old worlds.
     */
    private void transitionToRunning() {
        if (overworldHandle == null || validSpawnPos == null) return;
        
        ServerWorld world = overworldHandle.asWorld();
        
        // Forceload chunks around spawn for smooth teleport (3x3 chunk area)
        int spawnChunkX = validSpawnPos.getX() >> 4;
        int spawnChunkZ = validSpawnPos.getZ() >> 4;
        
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getChunk(spawnChunkX + dx, spawnChunkZ + dz);
            }
        }
        
        gameState = GameState.RUNNING;
        
        // Teleport ALL players to spawn
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            teleportPlayerToSpawn(player);
        }
        
        // NOW delete old worlds (after players are safely in new world)
        deleteOldWorlds();
        
        // Broadcast ready message
        server.getPlayerManager().broadcast(formatMessage("World ready! Good luck!"), false);
        
        SoulLink.LOGGER.info("World generation complete, run started");
    }
    
    /**
     * Deletes the old world handles saved from previous run.
     */
    private void deleteOldWorlds() {
        if (oldOverworldHandle != null) {
            try {
                oldOverworldHandle.delete();
                SoulLink.LOGGER.info("Deleted old temporary overworld");
            } catch (Exception e) {
                SoulLink.LOGGER.error("Failed to delete old temporary overworld", e);
            }
            oldOverworldHandle = null;
        }
        
        if (oldNetherHandle != null) {
            try {
                oldNetherHandle.delete();
                SoulLink.LOGGER.info("Deleted old temporary nether");
            } catch (Exception e) {
                SoulLink.LOGGER.error("Failed to delete old temporary nether", e);
            }
            oldNetherHandle = null;
        }
        
        if (oldEndHandle != null) {
            try {
                oldEndHandle.delete();
                SoulLink.LOGGER.info("Deleted old temporary end");
            } catch (Exception e) {
                SoulLink.LOGGER.error("Failed to delete old temporary end", e);
            }
            oldEndHandle = null;
        }
    }
    
        /**
     * Teleports a player to the spawn position and sets up for gameplay.
     */
    private void teleportPlayerToSpawn(ServerPlayerEntity player) {
        if (overworldHandle == null || validSpawnPos == null) return;
        
        ServerWorld tempOverworld = overworldHandle.asWorld();
        
        // Reset player for the run
        resetPlayer(player);
        
        // Teleport to spawn
        player.teleport(tempOverworld, validSpawnPos.getX() + 0.5, validSpawnPos.getY() + 1, validSpawnPos.getZ() + 0.5, 
            Set.of(), 0, 0, true);
        
        // Clear any title
        player.networkHandler.sendPacket(new ClearTitleS2CPacket(false));
        
        // Sync stats
        SharedStatsHandler.syncPlayerToSharedStats(player);
        
        // Play ready sound
        tempOverworld.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.5f);
        
        // Set up timer tracking for first player - go directly to waiting for input
        if (!timerStartedThisRun && !waitingForInput) {
            waitingForInput = true;
            trackedPlayer = player;
            // Capture current position and look direction
            trackedX = player.getX();
            trackedZ = player.getZ();
            trackedYaw = player.getYaw();
            trackedPitch = player.getPitch();
        }
    }

    // ==================== RUN LIFECYCLE ====================
    
    /**
     * Starts a new run - creates temporary worlds and begins spawn search.
     * Players stay where they are until spawn is found, then teleport directly.
     */
    public void startRun() {
        if (gameState == GameState.RUNNING || gameState == GameState.GENERATING_WORLD) {
            SoulLink.LOGGER.warn("Attempted to start run while already running or generating!");
            return;
        }
        
        SoulLink.LOGGER.info("Starting new Roguelike Speedrun...");
        
        // Broadcast starting message
        server.getPlayerManager().broadcast(formatMessage("Generating new world..."), false);
        
        // Save old world handles (delete AFTER teleporting to new world to avoid vanilla kick)
        oldOverworldHandle = overworldHandle;
        oldNetherHandle = netherHandle;
        oldEndHandle = endHandle;
        overworldHandle = null;
        netherHandle = null;
        endHandle = null;
        validSpawnPos = null;
        
        // Generate new seed for this run
        currentSeed = new Random().nextLong();
        
        // Create new temporary worlds
        createTemporaryWorlds();
        
        // Reset shared stats
        SharedStatsHandler.reset();
        
        // Reset End initialization flag for the new run
        endInitialized = false;
        
        // Reset timer state
        timerStartedThisRun = false;
        timerRunning = false;
        waitingForInput = false;
        trackedPlayer = null;
        elapsedTimeMillis = 0;
        startTimeMillis = 0;
        
        // Reset spawn search and start generating
        resetSpawnSearch();
        gameState = GameState.GENERATING_WORLD;
        
        // Put all players in spectator mode (stay where they are until spawn is ready)
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.changeGameMode(GameMode.SPECTATOR);
        }
        
        SoulLink.LOGGER.info("World created with seed: {}, now searching for spawn...", currentSeed);
    }
    
    /**
     * Creates the three temporary dimensions: Overworld, Nether, and End.
     */
    private void createTemporaryWorlds() {
        // Get the vanilla overworld for reference
        ServerWorld vanillaOverworld = server.getOverworld();
        
        // Get the server's current difficulty setting
        Difficulty serverDifficulty = server.getSaveProperties().getDifficulty();
        
        // Create temporary Overworld
        RuntimeWorldConfig overworldConfig = new RuntimeWorldConfig()
                .setDimensionType(DimensionTypes.OVERWORLD)
                .setDifficulty(serverDifficulty)
                .setGameRule(GameRules.ADVANCE_TIME, true)
                .setSeed(currentSeed)
                .setGenerator(vanillaOverworld.getChunkManager().getChunkGenerator());
        
        overworldHandle = fantasy.openTemporaryWorld(overworldConfig);
        // Set time to 0 (dawn) like vanilla Minecraft new worlds start
        overworldHandle.asWorld().setTimeOfDay(0);
        SoulLink.LOGGER.info("Created temporary overworld: {}", overworldHandle.getRegistryKey().getValue());
        
        // Create temporary Nether
        ServerWorld vanillaNether = server.getWorld(World.NETHER);
        if (vanillaNether != null) {
            RuntimeWorldConfig netherConfig = new RuntimeWorldConfig()
                    .setDimensionType(DimensionTypes.THE_NETHER)
                    .setDifficulty(serverDifficulty)
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
                    .setDifficulty(serverDifficulty)
                    .setSeed(currentSeed)
                    .setGenerator(vanillaEnd.getChunkManager().getChunkGenerator());
            
            endHandle = fantasy.openTemporaryWorld(endConfig);
            SoulLink.LOGGER.info("Created temporary end: {}", endHandle.getRegistryKey().getValue());
        }
    }
    
    /**
     * Deletes all temporary worlds.
     * @param teleportPlayers If true, teleports players to vanilla spawn first.
     *                        Set to false when starting a new run (players go to new world).
     */
    public void deleteWorlds(boolean teleportPlayers) {
        if (teleportPlayers) {
            // Teleport players to vanilla spawn (used on server shutdown)
            List<ServerPlayerEntity> allPlayers = new java.util.ArrayList<>(server.getPlayerManager().getPlayerList());
            
            for (ServerPlayerEntity player : allPlayers) {
                ServerWorld playerWorld = getPlayerWorld(player);
                if (playerWorld != null && isTemporaryWorld(playerWorld.getRegistryKey())) {
                    teleportToVanillaSpawn(player);
                }
            }
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
        
        validSpawnPos = null;
    }
    
    /**
     * Teleports a player to the vanilla overworld spawn.
     */
    private void teleportToVanillaSpawn(ServerPlayerEntity player) {
        ServerWorld overworld = server.getOverworld();
        int y = overworld.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        if (y < 1) y = 64;
        
        player.teleport(overworld, 0.5, y, 0.5, 
            Set.of(), player.getYaw(), player.getPitch(), true);
    }
    
    /**
     * Teleports a late-joining player to the current run.
     * If generating, puts them in spectator. If running, teleports to spawn.
     */
    public void teleportPlayerToRun(ServerPlayerEntity player) {
        if (gameState == GameState.GENERATING_WORLD) {
            // Still searching for spawn - just put in spectator (don't teleport yet)
            player.changeGameMode(GameMode.SPECTATOR);
            player.getInventory().clear();
            player.clearStatusEffects();
            player.sendMessage(formatMessage("Finding spawn point, please wait..."), false);
            return;
        }
        
        if (gameState == GameState.RUNNING && validSpawnPos != null) {
            // Run active - teleport to spawn
            teleportPlayerToSpawn(player);
            player.sendMessage(formatMessageWithPlayer("", player.getName().getString(), " joined. Stats synced."), false);
        }
    }
    
    /**
     * Fully resets a player for a new run.
     * Clears inventory, effects, XP, advancements, and resets health/hunger.
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
        
        // Reset all advancements
        resetPlayerAdvancements(player);
        
        // Set to survival mode
        player.changeGameMode(GameMode.SURVIVAL);
        
        SoulLink.LOGGER.info("Reset player {} for new run", player.getName().getString());
    }
    
    /**
     * Resets all advancements for a player.
     * Revokes all criteria from every advancement.
     */
    private void resetPlayerAdvancements(ServerPlayerEntity player) {
        PlayerAdvancementTracker tracker = player.getAdvancementTracker();
        
        // Iterate over all advancements and revoke all criteria
        for (AdvancementEntry advancement : server.getAdvancementLoader().getAdvancements()) {
            AdvancementProgress progress = tracker.getProgress(advancement);
            
            // Revoke each obtained criterion
            for (String criterion : progress.getObtainedCriteria()) {
                tracker.revokeCriterion(advancement, criterion);
            }
        }
        
        SoulLink.LOGGER.info("Reset advancements for player {}", player.getName().getString());
    }
    
    // ==================== TIMER MANAGEMENT ====================
    
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
     * Called every server tick to update state.
     */
    public void tick() {
        // Handle incremental world generation
        if (gameState == GameState.GENERATING_WORLD) {
            processGenerationStep();
            return;
        }
        
        if (gameState != GameState.RUNNING) {
            return;
        }
        
        // Manually advance time in temporary overworld (Fantasy worlds don't auto-tick time)
        if (overworldHandle != null) {
            ServerWorld tempOverworld = overworldHandle.asWorld();
            tempOverworld.setTimeOfDay(tempOverworld.getTimeOfDay() + 1);
        }
        
        // Wait for player input (movement or camera) to start timer
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
                        if (isInRun(player)) {
                            player.sendMessage(readyText, true);
                        }
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
                if (isInRun(player)) {
                    player.sendMessage(actionBarText, true);
                }
            }
        }
    }
    
    /**
     * Checks if a player is in the active run (in a temporary world).
     */
    private boolean isInRun(ServerPlayerEntity player) {
        ServerWorld world = getPlayerWorld(player);
        return world != null && isTemporaryWorld(world.getRegistryKey());
    }
    
    /**
     * Check if the player has moved or looked around to start the timer.
     */
    private boolean checkForInput() {
        if (trackedPlayer == null || trackedPlayer.isDisconnected()) {
            // Find a new player to track
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList().stream()
                .filter(this::isInRun)
                .toList();
            if (players.isEmpty()) {
                return false;
            }
            trackedPlayer = players.get(0);
            // Re-capture their position
            trackedX = trackedPlayer.getX();
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
    
    // ==================== GAME END STATES ====================
    
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
            if (isInRun(player)) {
                player.changeGameMode(GameMode.SPECTATOR);
                
                player.getInventory().clear();
                
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
    
    // ==================== GETTERS ====================
    
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
