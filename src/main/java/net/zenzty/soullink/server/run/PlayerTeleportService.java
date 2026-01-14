package net.zenzty.soullink.server.run;

import java.util.Set;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.settings.Settings;

/**
 * Handles player teleportation and reset logic for speedruns.
 */
public class PlayerTeleportService {

    private final MinecraftServer server;

    public PlayerTeleportService(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Teleports a player to the spawn position and sets up for gameplay.
     *
     * @param player The player to teleport
     * @param world The target world
     * @param spawnPos The spawn position
     * @param timerService The timer service for input tracking
     */
    public void teleportToSpawn(ServerPlayerEntity player, ServerWorld world, BlockPos spawnPos,
            TimerService timerService) {
        // Reset player for the run
        resetPlayer(player);

        // Teleport to spawn
        player.teleport(world, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                Set.of(), 0, 0, true);

        // Clear any title
        player.networkHandler.sendPacket(new ClearTitleS2CPacket(false));

        // Sync stats
        SharedStatsHandler.syncPlayerToSharedStats(player);

        // Play ready sound
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.5f);

        // Set up timer tracking for first player
        timerService.beginWaitingForInput(player);
    }

    /**
     * Teleports a player to the vanilla overworld spawn.
     */
    public void teleportToVanillaSpawn(ServerPlayerEntity player) {
        ServerWorld overworld = server.getOverworld();

        net.minecraft.world.WorldProperties.SpawnPoint spawn =
                overworld.getLevelProperties().getSpawnPoint();
        BlockPos spawnPos = spawn.globalPos().pos();

        player.teleport(overworld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                Set.of(), player.getYaw(), player.getPitch(), true);
    }

    /**
     * Forceloads chunks around spawn for smooth teleport.
     */
    public void forceloadSpawnChunks(ServerWorld world, BlockPos spawnPos) {
        int spawnChunkX = spawnPos.getX() >> 4;
        int spawnChunkZ = spawnPos.getZ() >> 4;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getChunk(spawnChunkX + dx, spawnChunkZ + dz);
            }
        }
    }

    /**
     * Fully resets a player for a new run.
     */
    private void resetPlayer(ServerPlayerEntity player) {
        // Clear inventory
        player.getInventory().clear();

        // Clear all status effects
        player.clearStatusEffects();

        // Reset experience
        player.setExperienceLevel(0);
        player.setExperiencePoints(0);

        // Apply half heart mode if enabled
        Settings settings = Settings.getInstance();
        var maxHealthAttr = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (maxHealthAttr != null) {
            if (settings.isHalfHeartMode()) {
                maxHealthAttr.setBaseValue(1.0);
                player.setHealth(1.0f);
                SoulLink.LOGGER.info("Half Heart Mode enabled for {}",
                        player.getName().getString());
            } else {
                maxHealthAttr.setBaseValue(20.0);
                player.setHealth(player.getMaxHealth());
            }
        }

        // Reset hunger
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
     */
    private void resetPlayerAdvancements(ServerPlayerEntity player) {
        PlayerAdvancementTracker tracker = player.getAdvancementTracker();

        for (AdvancementEntry advancement : server.getAdvancementLoader().getAdvancements()) {
            AdvancementProgress progress = tracker.getProgress(advancement);

            for (String criterion : progress.getObtainedCriteria()) {
                tracker.revokeCriterion(advancement, criterion);
            }
        }

        SoulLink.LOGGER.info("Reset advancements for player {}", player.getName().getString());
    }
}
