package net.zenzty.soullink.server.run;

import java.util.Random;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.rule.GameRules;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.settings.RunDifficulty;
import net.zenzty.soullink.server.settings.Settings;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.fantasy.RuntimeWorldHandle;

/**
 * Manages the lifecycle of temporary Fantasy worlds for speedruns. Handles creation, deletion, and
 * world key lookups.
 */
public class WorldService {

    private final MinecraftServer server;
    private final Fantasy fantasy;

    // Temporary world handles
    private RuntimeWorldHandle overworldHandle;
    private RuntimeWorldHandle netherHandle;
    private RuntimeWorldHandle endHandle;

    // Old world handles (to delete after teleporting to new world)
    private RuntimeWorldHandle oldOverworldHandle;
    private RuntimeWorldHandle oldNetherHandle;
    private RuntimeWorldHandle oldEndHandle;

    // Current run seed
    private long currentSeed;

    public WorldService(MinecraftServer server) {
        this.server = server;
        this.fantasy = Fantasy.get(server);
    }

    /**
     * Creates the three temporary dimensions: Overworld, Nether, and End.
     *
     * @return The generated seed used for all worlds
     */
    public long createTemporaryWorlds() {
        // Generate new seed for this run
        currentSeed = new Random().nextLong();

        // Get the difficulty from settings
        RunDifficulty runDifficulty = Settings.getInstance().getDifficulty();
        Difficulty serverDifficulty = runDifficulty.toVanilla();
        boolean extremeMode = runDifficulty == RunDifficulty.EXTREME;

        // Create temporary Overworld
        ServerWorld vanillaOverworld = server.getOverworld();
        RuntimeWorldConfig overworldConfig = new RuntimeWorldConfig()
                .setDimensionType(DimensionTypes.OVERWORLD).setDifficulty(serverDifficulty)
                // In EXTREME mode, freeze time at midnight by disabling time advancement
                .setGameRule(GameRules.ADVANCE_TIME, !extremeMode).setSeed(currentSeed)
                .setGenerator(vanillaOverworld.getChunkManager().getChunkGenerator());

        overworldHandle = fantasy.openTemporaryWorld(overworldConfig);
        // Set initial time based on difficulty: midnight and frozen for EXTREME, dawn otherwise
        overworldHandle.asWorld().setTimeOfDay(extremeMode ? 18000L : 0L);
        SoulLink.LOGGER.info("Created temporary overworld: {}",
                overworldHandle.getRegistryKey().getValue());

        // Create temporary Nether
        ServerWorld vanillaNether = server.getWorld(World.NETHER);
        if (vanillaNether != null) {
            RuntimeWorldConfig netherConfig =
                    new RuntimeWorldConfig().setDimensionType(DimensionTypes.THE_NETHER)
                            .setDifficulty(serverDifficulty).setSeed(currentSeed)
                            .setGenerator(vanillaNether.getChunkManager().getChunkGenerator());

            netherHandle = fantasy.openTemporaryWorld(netherConfig);
            SoulLink.LOGGER.info("Created temporary nether: {}",
                    netherHandle.getRegistryKey().getValue());
        }

        // Create temporary End
        ServerWorld vanillaEnd = server.getWorld(World.END);
        if (vanillaEnd != null) {
            RuntimeWorldConfig endConfig =
                    new RuntimeWorldConfig().setDimensionType(DimensionTypes.THE_END)
                            .setDifficulty(serverDifficulty).setSeed(currentSeed)
                            .setGenerator(vanillaEnd.getChunkManager().getChunkGenerator());

            endHandle = fantasy.openTemporaryWorld(endConfig);
            SoulLink.LOGGER.info("Created temporary end: {}",
                    endHandle.getRegistryKey().getValue());
        }

        return currentSeed;
    }

    /**
     * Saves current world handles as "old" for later deletion. Call this before creating new
     * worlds.
     */
    public void saveCurrentWorldsAsOld() {
        oldOverworldHandle = overworldHandle;
        oldNetherHandle = netherHandle;
        oldEndHandle = endHandle;
        overworldHandle = null;
        netherHandle = null;
        endHandle = null;
    }

    private void safeDeleteWorld(RuntimeWorldHandle handle, String worldName) {
        if (handle != null) {
            try {
                handle.delete();
                SoulLink.LOGGER.info("Deleted {}", worldName);
            } catch (Exception e) {
                SoulLink.LOGGER.error("Failed to delete {}", worldName, e);
            }
        }
    }

    /**
     * Deletes the old world handles saved from previous run.
     */
    public void deleteOldWorlds() {
        safeDeleteWorld(oldOverworldHandle, "old temporary overworld");
        oldOverworldHandle = null;

        safeDeleteWorld(oldNetherHandle, "old temporary nether");
        oldNetherHandle = null;

        safeDeleteWorld(oldEndHandle, "old temporary end");
        oldEndHandle = null;
    }

    /**
     * Deletes all current temporary worlds.
     */
    public void deleteCurrentWorlds() {
        safeDeleteWorld(overworldHandle, "temporary overworld");
        overworldHandle = null;

        safeDeleteWorld(netherHandle, "temporary nether");
        netherHandle = null;

        safeDeleteWorld(endHandle, "temporary end");
        endHandle = null;
    }

    /**
     * Checks if a world key belongs to one of our temporary dimensions.
     */
    public boolean isTemporaryWorld(RegistryKey<World> worldKey) {
        if (worldKey == null)
            return false;

        RegistryKey<World> tempOverworld = getOverworldKey();
        RegistryKey<World> tempNether = getNetherKey();
        RegistryKey<World> tempEnd = getEndKey();

        return worldKey.equals(tempOverworld) || worldKey.equals(tempNether)
                || worldKey.equals(tempEnd);
    }

    // ==================== GETTERS ====================

    public ServerWorld getOverworld() {
        return overworldHandle != null ? overworldHandle.asWorld() : null;
    }

    public ServerWorld getNether() {
        return netherHandle != null ? netherHandle.asWorld() : null;
    }

    public ServerWorld getEnd() {
        return endHandle != null ? endHandle.asWorld() : null;
    }

    public RegistryKey<World> getOverworldKey() {
        return overworldHandle != null ? overworldHandle.getRegistryKey() : null;
    }

    public RegistryKey<World> getNetherKey() {
        return netherHandle != null ? netherHandle.getRegistryKey() : null;
    }

    public RegistryKey<World> getEndKey() {
        return endHandle != null ? endHandle.getRegistryKey() : null;
    }

    public long getCurrentSeed() {
        return currentSeed;
    }

    /**
     * Gets the linked nether world for portal travel from a given world.
     */
    public ServerWorld getLinkedNetherWorld(ServerWorld fromWorld) {
        if (fromWorld == null)
            return null;

        RegistryKey<World> fromKey = fromWorld.getRegistryKey();

        if (fromKey.equals(getOverworldKey())) {
            return getNether();
        } else if (fromKey.equals(getNetherKey())) {
            return getOverworld();
        }

        return null;
    }
}
