package net.zenzty.soullink.mixin;

import java.util.Comparator;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockLocating;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import net.zenzty.soullink.RunManager;
import net.zenzty.soullink.SoulLink;

/**
 * Mixin for NetherPortalBlock to redirect portal travel to temporary dimensions. Uses vanilla's POI
 * system to find existing portals, only creates new ones if needed.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalMixin {

    @Inject(method = "createTeleportTarget", at = @At("HEAD"), cancellable = true)
    private void redirectPortalDestination(ServerWorld world, Entity entity, BlockPos pos,
            CallbackInfoReturnable<TeleportTarget> cir) {
        RunManager runManager = RunManager.getInstance();

        // Allow portal redirects during both RUNNING and GAMEOVER states
        if (runManager == null || (!runManager.isRunActive() && !runManager.isGameOver())) {
            return; // Let vanilla handle it
        }

        RegistryKey<World> currentWorldKey = world.getRegistryKey();

        // Only intercept if we're in a temporary world
        if (!runManager.isTemporaryWorld(currentWorldKey)) {
            return; // Let vanilla handle it
        }

        RegistryKey<World> tempOverworld = runManager.getTemporaryOverworldKey();
        RegistryKey<World> tempNether = runManager.getTemporaryNetherKey();

        if (tempOverworld == null || tempNether == null) {
            return;
        }

        ServerWorld destinationWorld = null;

        if (currentWorldKey.equals(tempOverworld)) {
            destinationWorld = world.getServer().getWorld(tempNether);
        } else if (currentWorldKey.equals(tempNether)) {
            destinationWorld = world.getServer().getWorld(tempOverworld);
        }

        if (destinationWorld == null) {
            return;
        }

        // Calculate scaled position
        double scale = world.getDimension().coordinateScale()
                / destinationWorld.getDimension().coordinateScale();
        BlockPos scaledPos =
                BlockPos.ofFloored(entity.getX() * scale, entity.getY(), entity.getZ() * scale);

        // FIRST: Search for existing portal using POI system (like vanilla does)
        Optional<BlockPos> existingPortal = findExistingPortalPOI(destinationWorld, scaledPos);

        if (existingPortal.isPresent()) {
            BlockPos portalPos = existingPortal.get();
            Vec3d spawnPos = findSafeSpawnInPortal(destinationWorld, portalPos);
            SoulLink.LOGGER.info("Using existing portal at {}, spawn at {}", portalPos, spawnPos);

            cir.setReturnValue(new TeleportTarget(destinationWorld, spawnPos, entity.getVelocity(),
                    entity.getYaw(), entity.getPitch(),
                    TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET
                            .then(TeleportTarget.ADD_PORTAL_CHUNK_TICKET)));
            return;
        }

        // SECOND: No existing portal, create one using vanilla's PortalForcer
        Optional<BlockLocating.Rectangle> newPortal = destinationWorld.getPortalForcer()
                .createPortal(scaledPos, entity.getHorizontalFacing().getAxis());

        if (newPortal.isPresent()) {
            BlockLocating.Rectangle rect = newPortal.get();
            Vec3d spawnPos = getPortalCenter(rect);
            SoulLink.LOGGER.info("Created new portal at {}, spawn at {}", rect.lowerLeft, spawnPos);

            cir.setReturnValue(new TeleportTarget(destinationWorld, spawnPos, entity.getVelocity(),
                    entity.getYaw(), entity.getPitch(),
                    TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET
                            .then(TeleportTarget.ADD_PORTAL_CHUNK_TICKET)));
        }
    }

    /**
     * Gets the center position of a portal rectangle.
     */
    private Vec3d getPortalCenter(BlockLocating.Rectangle rect) {
        return new Vec3d(rect.lowerLeft.getX() + rect.width / 2.0, rect.lowerLeft.getY() + 0.5,
                rect.lowerLeft.getZ() + 0.5);
    }

    /**
     * Finds a safe spawn position inside an existing portal. Scans the portal to find its center.
     */
    private Vec3d findSafeSpawnInPortal(ServerWorld world, BlockPos portalBlockPos) {
        BlockState state = world.getBlockState(portalBlockPos);

        if (!state.isOf(Blocks.NETHER_PORTAL)) {
            return portalBlockPos.toCenterPos();
        }

        // Get the portal axis
        Direction.Axis axis = state.get(Properties.HORIZONTAL_AXIS);

        // Find the full extent of the portal by scanning in each direction
        BlockPos minPos = portalBlockPos;
        BlockPos maxPos = portalBlockPos;

        // Scan along the portal axis and vertically to find bounds
        Direction widthDir = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;

        // Find min along width
        BlockPos current = portalBlockPos;
        while (world.getBlockState(current.offset(widthDir.getOpposite()))
                .isOf(Blocks.NETHER_PORTAL)) {
            current = current.offset(widthDir.getOpposite());
        }
        minPos = current;

        // Find max along width
        current = portalBlockPos;
        while (world.getBlockState(current.offset(widthDir)).isOf(Blocks.NETHER_PORTAL)) {
            current = current.offset(widthDir);
        }
        maxPos = current;

        // Find min Y (bottom of portal)
        current = minPos;
        while (world.getBlockState(current.down()).isOf(Blocks.NETHER_PORTAL)) {
            current = current.down();
        }
        minPos = new BlockPos(minPos.getX(), current.getY(), minPos.getZ());

        // Calculate center of the portal
        double centerX = (minPos.getX() + maxPos.getX()) / 2.0 + 0.5;
        double centerY = minPos.getY() + 0.5;
        double centerZ = (minPos.getZ() + maxPos.getZ()) / 2.0 + 0.5;

        return new Vec3d(centerX, centerY, centerZ);
    }

    /**
     * Uses vanilla's Point of Interest system to find existing nether portals.
     */
    private Optional<BlockPos> findExistingPortalPOI(ServerWorld world, BlockPos targetPos) {
        PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();

        // Search radius: 128 blocks in overworld, 16 in nether (vanilla behavior)
        int searchRadius = world.getDimension().hasCeiling() ? 16 : 128;

        return poiStorage
                .getInSquare(poiType -> poiType.matchesKey(PointOfInterestTypes.NETHER_PORTAL),
                        targetPos, searchRadius, PointOfInterestStorage.OccupationStatus.ANY)
                .map(PointOfInterest::getPos).min(Comparator
                        .comparingDouble(portalPos -> portalPos.getSquaredDistance(targetPos)));
    }
}

