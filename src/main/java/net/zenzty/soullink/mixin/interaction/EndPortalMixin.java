package net.zenzty.soullink.mixin.interaction;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.mixin.server.ServerWorldAccessor;
import net.zenzty.soullink.server.run.RunManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for EndPortalBlock to redirect End portal travel to temporary dimensions. Handles both
 * entering the End (from overworld) and exiting (from End to overworld).
 */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalMixin {

    // The End spawn platform location (vanilla behavior)
    private static final BlockPos END_SPAWN_PLATFORM = new BlockPos(100, 49, 0);

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void redirectEndPortal(
            ServerLevel world, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTransition> cir) {
        RunManager runManager = RunManager.getInstance();

        // Allow portal redirects during both RUNNING and GAMEOVER states
        // (players need to be able to leave the End after victory)
        if (runManager == null || (!runManager.isRunActive() && !runManager.isGameOver())) {
            return; // Let vanilla handle it
        }

        ResourceKey<Level> currentWorldKey = world.dimension();

        // Only intercept if we're in a temporary world
        if (!runManager.isTemporaryWorld(currentWorldKey)) {
            return; // Let vanilla handle it
        }

        ResourceKey<Level> tempOverworld = runManager.getTemporaryOverworldKey();
        ResourceKey<Level> tempEnd = runManager.getTemporaryEndKey();

        if (tempOverworld == null || tempEnd == null) {
            SoulLink.LOGGER.warn("Temporary worlds not available for End portal redirect");
            return;
        }

        ServerLevel destinationWorld = null;
        Vec3 spawnPos = null;

        // Determine direction of travel
        boolean isInTempOverworld = currentWorldKey.equals(tempOverworld);
        boolean isInTempEnd = currentWorldKey.equals(tempEnd);

        if (isInTempOverworld) {
            // Going TO the End
            destinationWorld = runManager.getTemporaryEnd();
            if (destinationWorld != null) {
                // Initialize the End if first time entering (tracked by RunManager)
                if (!runManager.isEndInitialized()) {
                    if (initializeEnd(destinationWorld)) {
                        runManager.setEndInitialized(true);
                    }
                }

                // Spawn above the obsidian platform
                spawnPos = new Vec3(
                        END_SPAWN_PLATFORM.getX() + 0.5,
                        END_SPAWN_PLATFORM.getY() + 1.0,
                        END_SPAWN_PLATFORM.getZ() + 0.5);
                SoulLink.LOGGER.info("Redirecting End portal: temp overworld -> temp end at {}", spawnPos);
            }
        } else if (isInTempEnd) {
            // Coming FROM the End (exit portal after dragon)
            destinationWorld = runManager.getTemporaryOverworld();
            if (destinationWorld != null) {
                // Return to overworld - find a safe spawn location near origin
                BlockPos safeSpawn = findSafeSpawn(destinationWorld, 0, 0);
                spawnPos = new Vec3(safeSpawn.getX() + 0.5, safeSpawn.getY() + 1.0, safeSpawn.getZ() + 0.5);
                SoulLink.LOGGER.info("Redirecting End exit portal: temp end -> temp overworld at {}", spawnPos);
            }
        }

        if (destinationWorld == null || spawnPos == null) {
            SoulLink.LOGGER.warn("Could not determine End portal destination");
            return;
        }

        // Trigger advancement for players using the vanilla dimension keys
        final boolean goingToEnd = isInTempOverworld;

        cir.setReturnValue(new TeleportTransition(
                destinationWorld,
                spawnPos,
                Vec3.ZERO, // Reset
                // velocity
                entity.getYRot(),
                entity.getXRot(),
                TeleportTransition.PLAY_PORTAL_SOUND
                        .then(TeleportTransition.PLACE_PORTAL_TICKET)
                        .then(teleportedEntity -> triggerEndAdvancement(teleportedEntity, goingToEnd))));
    }

    /**
     * Triggers the changed_dimension advancement for End portal travel. Uses vanilla dimension keys
     * so the advancement system recognizes it.
     */
    @Unique private void triggerEndAdvancement(Entity entity, boolean goingToEnd) {
        if (entity instanceof ServerPlayer player) {
            ResourceKey<Level> from = goingToEnd ? Level.OVERWORLD : Level.END;
            ResourceKey<Level> to = goingToEnd ? Level.END : Level.OVERWORLD;
            CriteriaTriggers.CHANGED_DIMENSION.trigger(player, from, to);
            SoulLink.LOGGER.info(
                    "Triggered End advancement for {}: {} -> {}",
                    player.getName().getString(),
                    from.identifier(),
                    to.identifier());
        }
    }

    /**
     * Initializes the End dimension by creating and injecting an EnderDragonFight. Fantasy
     * temporary worlds don't automatically get one, so we create it manually.
     */
    @Unique private boolean initializeEnd(ServerLevel endWorld) {
        SoulLink.LOGGER.info("Initializing temporary End dimension...");

        // Force-load the central chunks to ensure End island and structures are generated
        // Load a smaller area to reduce server spike
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                endWorld.getChunk(x, z);
            }
        }
        SoulLink.LOGGER.info("Loaded central End chunks");

        // Check if EnderDragonFight already exists
        EnderDragonFight existingFight = endWorld.getDragonFight();

        if (existingFight != null) {
            SoulLink.LOGGER.info("EnderDragonFight already exists");
            return true;
        }

        // Fantasy temporary worlds don't get EnderDragonFight automatically
        // We need to create one and inject it using our accessor
        SoulLink.LOGGER.info("Creating EnderDragonFight for temporary End world...");

        try {
            // Create a fresh EnderDragonFight with default state (dragon not yet killed)
            EnderDragonFight dragonFight = EnderDragonFight.createDefault();
            dragonFight.init(endWorld, endWorld.getSeed(), net.minecraft.core.BlockPos.ZERO);

            // Inject it into the world using our accessor
            ((ServerWorldAccessor) endWorld).setEnderDragonFight(dragonFight);

            SoulLink.LOGGER.info("EnderDragonFight created and injected successfully");

            // Verify it was set
            EnderDragonFight verifyFight = endWorld.getDragonFight();
            if (verifyFight != null) {
                SoulLink.LOGGER.info("Verified: EnderDragonFight is now active");
                SoulLink.LOGGER.info("Temporary End initialization complete");
                return true;
            }
        } catch (Exception e) {
            SoulLink.LOGGER.error("Failed to create EnderDragonFight", e);
        }

        SoulLink.LOGGER.warn("Temporary End initialization failed");
        return false;
    }

    /**
     * Finds a safe spawn location in the overworld.
     */
    @Unique private BlockPos findSafeSpawn(ServerLevel world, int centerX, int centerZ) {
        // Search in a spiral pattern for safe ground
        for (int radius = 0; radius <= 128; radius += 16) {
            for (int x = -radius; x <= radius; x += 16) {
                for (int z = -radius; z <= radius; z += 16) {
                    if (radius > 0 && Math.abs(x) != radius && Math.abs(z) != radius) continue;

                    int checkX = centerX + x;
                    int checkZ = centerZ + z;

                    // Force chunk load
                    world.getChunk(checkX >> 4, checkZ >> 4);

                    int y = world.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            checkX,
                            checkZ);

                    if (y > 50 && y < 200) {
                        BlockPos groundPos = new BlockPos(checkX, y - 1, checkZ);
                        if (world.getBlockState(groundPos).isRedstoneConductor(world, groundPos)) {
                            return new BlockPos(checkX, y, checkZ);
                        }
                    }
                }
            }
        }

        // Fallback to origin at top surface
        int topY = world.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
        return new BlockPos(centerX, Math.max(topY, world.getMinY() + 64), centerZ);
    }
}
