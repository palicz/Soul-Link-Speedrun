package net.zenzty.soullink.mixin;

import net.minecraft.block.EndPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.zenzty.soullink.RunManager;
import net.zenzty.soullink.SoulLink;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for EndPortalBlock to redirect End portal travel to temporary dimensions.
 * Handles both entering the End (from overworld) and exiting (from End to overworld).
 */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalMixin {
    
    // The End spawn platform location (vanilla behavior)
    private static final BlockPos END_SPAWN_PLATFORM = new BlockPos(100, 49, 0);
    
    @Inject(method = "createTeleportTarget", at = @At("HEAD"), cancellable = true)
    private void redirectEndPortal(ServerWorld world, Entity entity, BlockPos pos,
                                   CallbackInfoReturnable<TeleportTarget> cir) {
        RunManager runManager = RunManager.getInstance();
        
        // Allow portal redirects during both RUNNING and GAMEOVER states
        // (players need to be able to leave the End after victory)
        if (runManager == null || (!runManager.isRunActive() && !runManager.isGameOver())) {
            return; // Let vanilla handle it
        }
        
        RegistryKey<World> currentWorldKey = world.getRegistryKey();
        
        // Only intercept if we're in a temporary world
        if (!runManager.isTemporaryWorld(currentWorldKey)) {
            return; // Let vanilla handle it
        }
        
        RegistryKey<World> tempOverworld = runManager.getTemporaryOverworldKey();
        RegistryKey<World> tempEnd = runManager.getTemporaryEndKey();
        
        if (tempOverworld == null || tempEnd == null) {
            SoulLink.LOGGER.warn("Temporary worlds not available for End portal redirect");
            return;
        }
        
        ServerWorld destinationWorld = null;
        Vec3d spawnPos = null;
        
        // Determine direction of travel
        boolean isInTempOverworld = currentWorldKey.equals(tempOverworld);
        boolean isInTempEnd = currentWorldKey.equals(tempEnd);
        
        if (isInTempOverworld) {
            // Going TO the End
            destinationWorld = runManager.getTemporaryEnd();
            if (destinationWorld != null) {
                // Initialize the End if first time entering (tracked by RunManager)
                if (!runManager.isEndInitialized()) {
                    initializeEnd(destinationWorld);
                    runManager.setEndInitialized(true);
                }
                
                // Spawn above the obsidian platform
                spawnPos = new Vec3d(
                    END_SPAWN_PLATFORM.getX() + 0.5,
                    END_SPAWN_PLATFORM.getY() + 1.0,
                    END_SPAWN_PLATFORM.getZ() + 0.5
                );
                SoulLink.LOGGER.info("Redirecting End portal: temp overworld -> temp end at {}", spawnPos);
            }
        } else if (isInTempEnd) {
            // Coming FROM the End (exit portal after dragon)
            destinationWorld = runManager.getTemporaryOverworld();
            if (destinationWorld != null) {
                // Return to overworld - find a safe spawn location near origin
                BlockPos safeSpawn = findSafeSpawn(destinationWorld, 0, 0);
                spawnPos = new Vec3d(
                    safeSpawn.getX() + 0.5,
                    safeSpawn.getY() + 1.0,
                    safeSpawn.getZ() + 0.5
                );
                SoulLink.LOGGER.info("Redirecting End exit portal: temp end -> temp overworld at {}", spawnPos);
            }
        }
        
        if (destinationWorld == null || spawnPos == null) {
            SoulLink.LOGGER.warn("Could not determine End portal destination");
            return;
        }
        
        cir.setReturnValue(new TeleportTarget(
            destinationWorld,
            spawnPos,
            Vec3d.ZERO, // Reset velocity
            entity.getYaw(),
            entity.getPitch(),
            TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET.then(TeleportTarget.ADD_PORTAL_CHUNK_TICKET)
        ));
    }
    
    /**
     * Initializes the End dimension by creating and injecting an EnderDragonFight.
     * Fantasy temporary worlds don't automatically get one, so we create it manually.
     */
    @Unique
    private void initializeEnd(ServerWorld endWorld) {
        SoulLink.LOGGER.info("Initializing temporary End dimension...");
        
        // Force-load the central chunks to ensure End island and structures are generated
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                endWorld.getChunk(x, z);
            }
        }
        SoulLink.LOGGER.info("Loaded central End chunks");
        
        // Check if EnderDragonFight already exists
        EnderDragonFight existingFight = endWorld.getEnderDragonFight();
        
        if (existingFight != null) {
            SoulLink.LOGGER.info("EnderDragonFight already exists");
            return;
        }
        
        // Fantasy temporary worlds don't get EnderDragonFight automatically
        // We need to create one and inject it using our accessor
        SoulLink.LOGGER.info("Creating EnderDragonFight for temporary End world...");
        
        try {
            // Create a fresh EnderDragonFight with default state (dragon not yet killed)
            EnderDragonFight dragonFight = new EnderDragonFight(
                endWorld,
                endWorld.getSeed(),
                EnderDragonFight.Data.DEFAULT
            );
            
            // Inject it into the world using our accessor
            ((ServerWorldAccessor) endWorld).setEnderDragonFight(dragonFight);
            
            SoulLink.LOGGER.info("EnderDragonFight created and injected successfully");
            
            // Verify it was set
            EnderDragonFight verifyFight = endWorld.getEnderDragonFight();
            if (verifyFight != null) {
                SoulLink.LOGGER.info("Verified: EnderDragonFight is now active");
            }
        } catch (Exception e) {
            SoulLink.LOGGER.error("Failed to create EnderDragonFight: {}", e.getMessage());
            e.printStackTrace();
        }
        
        SoulLink.LOGGER.info("Temporary End initialization complete");
    }
    
    /**
     * Finds a safe spawn location in the overworld.
     */
    private BlockPos findSafeSpawn(ServerWorld world, int centerX, int centerZ) {
        // Search in a spiral pattern for safe ground
        for (int radius = 0; radius <= 128; radius += 16) {
            for (int x = -radius; x <= radius; x += 16) {
                for (int z = -radius; z <= radius; z += 16) {
                    if (radius > 0 && Math.abs(x) != radius && Math.abs(z) != radius) continue;
                    
                    int checkX = centerX + x;
                    int checkZ = centerZ + z;
                    
                    // Force chunk load
                    world.getChunk(checkX >> 4, checkZ >> 4);
                    
                    int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, checkX, checkZ);
                    
                    if (y > 50 && y < 200) {
                        BlockPos groundPos = new BlockPos(checkX, y - 1, checkZ);
                        if (world.getBlockState(groundPos).isSolidBlock(world, groundPos)) {
                            return new BlockPos(checkX, y, checkZ);
                        }
                    }
                }
            }
        }
        
        // Fallback to origin at sea level
        return new BlockPos(centerX, 64, centerZ);
    }
}

