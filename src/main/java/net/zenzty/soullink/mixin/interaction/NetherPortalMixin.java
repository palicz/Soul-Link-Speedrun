package net.zenzty.soullink.mixin.interaction;

import java.util.Comparator;
import java.util.Optional;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for NetherPortalBlock to redirect portal travel to temporary dimensions. Uses vanilla's POI
 * system to find existing portals, only creates new ones if needed.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalMixin {

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void redirectPortalDestination(
            ServerLevel world, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTransition> cir) {
        RunManager runManager = RunManager.getInstance();

        // Allow portal redirects during both RUNNING and GAMEOVER states
        if (runManager == null || (!runManager.isRunActive() && !runManager.isGameOver())) {
            return; // Let vanilla handle it
        }

        ResourceKey<Level> currentWorldKey = world.dimension();

        // Only intercept if we're in a temporary world
        if (!runManager.isTemporaryWorld(currentWorldKey)) {
            return; // Let vanilla handle it
        }

        ResourceKey<Level> tempOverworld = runManager.getTemporaryOverworldKey();
        ResourceKey<Level> tempNether = runManager.getTemporaryNetherKey();

        if (tempOverworld == null || tempNether == null) {
            return;
        }

        ServerLevel destinationWorld = null;

        if (currentWorldKey.equals(tempOverworld)) {
            destinationWorld = world.getServer().getLevel(tempNether);
        } else if (currentWorldKey.equals(tempNether)) {
            destinationWorld = world.getServer().getLevel(tempOverworld);
        }

        if (destinationWorld == null) {
            return;
        }

        // Calculate scaled position and clamp to world border and build height
        double scale = world.dimensionType().coordinateScale()
                / destinationWorld.dimensionType().coordinateScale();

        double scaledX = net.minecraft.util.Mth.clamp(
                entity.getX() * scale,
                destinationWorld.getWorldBorder().getMinX(),
                destinationWorld.getWorldBorder().getMaxX());
        double scaledZ = net.minecraft.util.Mth.clamp(
                entity.getZ() * scale,
                destinationWorld.getWorldBorder().getMinZ(),
                destinationWorld.getWorldBorder().getMaxZ());
        int scaledY = net.minecraft.util.Mth.clamp(
                entity.getBlockY(),
                destinationWorld.getMinY(),
                destinationWorld.getMinY() + destinationWorld.getHeight() - 1);

        BlockPos scaledPos = new BlockPos((int) scaledX, scaledY, (int) scaledZ);

        // FIRST: Search for existing portal using POI system (like vanilla does)
        Optional<BlockPos> existingPortal = findExistingPortalPOI(destinationWorld, scaledPos);

        if (existingPortal.isPresent()) {
            BlockPos portalPos = existingPortal.get();
            Vec3 spawnPos = findSafeSpawnInPortal(destinationWorld, portalPos);
            SoulLink.LOGGER.debug("Using existing portal at {}, spawn at {}", portalPos, spawnPos);

            // Trigger advancement for players using the vanilla dimension keys
            final boolean goingToNether = currentWorldKey.equals(tempOverworld);

            cir.setReturnValue(createPortalTeleportTarget(destinationWorld, spawnPos, entity, goingToNether));
            return;
        }

        // SECOND: No existing portal, create one using vanilla's PortalForcer
        // Derive axis from source block state if possible, otherwise fallback to entity facing
        BlockState sourceState = world.getBlockState(pos);
        Direction.Axis axis = sourceState.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)
                ? sourceState.getValue(BlockStateProperties.HORIZONTAL_AXIS)
                : entity.getDirection().getAxis();

        Optional<BlockUtil.FoundRectangle> newPortal =
                destinationWorld.getPortalForcer().createPortal(scaledPos, axis);

        if (newPortal.isPresent()) {
            BlockUtil.FoundRectangle rect = newPortal.get();
            Vec3 spawnPos = getPortalCenter(rect, axis);
            SoulLink.LOGGER.debug("Created new portal at {}, spawn at {}", rect.minCorner, spawnPos);

            // Trigger advancement for players using the vanilla dimension keys
            final boolean goingToNether = currentWorldKey.equals(tempOverworld);

            cir.setReturnValue(createPortalTeleportTarget(destinationWorld, spawnPos, entity, goingToNether));
        }
    }

    /**
     * Helper to create a TeleportTarget with common settings.
     */
    @Unique private TeleportTransition createPortalTeleportTarget(
            ServerLevel destinationWorld, Vec3 spawnPos, Entity entity, boolean goingToNether) {
        return new TeleportTransition(
                destinationWorld,
                spawnPos,
                entity.getDeltaMovement(),
                entity.getYRot(),
                entity.getXRot(),
                TeleportTransition.PLAY_PORTAL_SOUND
                        .then(TeleportTransition.PLACE_PORTAL_TICKET)
                        .then(teleportedEntity -> triggerNetherAdvancement(teleportedEntity, goingToNether)));
    }

    /**
     * Triggers the changed_dimension advancement for nether portal travel. Uses vanilla dimension
     * keys so the advancement system recognizes it.
     */
    @Unique private void triggerNetherAdvancement(Entity entity, boolean goingToNether) {
        if (entity instanceof ServerPlayer player) {
            ResourceKey<Level> from = goingToNether ? Level.OVERWORLD : Level.NETHER;
            ResourceKey<Level> to = goingToNether ? Level.NETHER : Level.OVERWORLD;
            CriteriaTriggers.CHANGED_DIMENSION.trigger(player, from, to);
            SoulLink.LOGGER.debug(
                    "Triggered nether advancement for {}: {} -> {}",
                    player.getName().getString(),
                    from.identifier(),
                    to.identifier());
        }
    }

    /**
     * Gets the center position of a portal rectangle.
     */
    @Unique private Vec3 getPortalCenter(BlockUtil.FoundRectangle rect, Direction.Axis axis) {
        double centerX = rect.minCorner.getX() + 0.5;
        double centerZ = rect.minCorner.getZ() + 0.5;

        if (axis == Direction.Axis.X) {
            centerX += rect.axis1Size / 2.0;
        } else if (axis == Direction.Axis.Z) {
            centerZ += rect.axis1Size / 2.0;
        }

        return new Vec3(centerX, rect.minCorner.getY() + 0.5, centerZ);
    }

    /**
     * Finds a safe spawn position inside an existing portal. Scans the portal to find its center.
     */
    @Unique private Vec3 findSafeSpawnInPortal(ServerLevel world, BlockPos portalBlockPos) {
        BlockState state = world.getBlockState(portalBlockPos);

        if (!state.is(Blocks.NETHER_PORTAL)) {
            return portalBlockPos.getCenter();
        }

        // Get the portal axis
        Direction.Axis axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);

        // Find the full extent of the portal by scanning in each direction
        BlockPos minPos = portalBlockPos;
        BlockPos maxPos = portalBlockPos;

        // Scan along the portal axis and vertically to find bounds
        Direction widthDir = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;

        // Find min along width
        BlockPos current = portalBlockPos;
        while (world.getBlockState(current.relative(widthDir.getOpposite())).is(Blocks.NETHER_PORTAL)) {
            current = current.relative(widthDir.getOpposite());
        }
        minPos = current;

        // Find max along width
        current = portalBlockPos;
        while (world.getBlockState(current.relative(widthDir)).is(Blocks.NETHER_PORTAL)) {
            current = current.relative(widthDir);
        }
        maxPos = current;

        // Find min Y (bottom of portal)
        current = minPos;
        while (world.getBlockState(current.below()).is(Blocks.NETHER_PORTAL)) {
            current = current.below();
        }
        minPos = new BlockPos(minPos.getX(), current.getY(), minPos.getZ());

        // Calculate center of the portal
        double centerX = (minPos.getX() + maxPos.getX()) / 2.0 + 0.5;
        double centerY = minPos.getY() + 0.5;
        double centerZ = (minPos.getZ() + maxPos.getZ()) / 2.0 + 0.5;

        return new Vec3(centerX, centerY, centerZ);
    }

    /**
     * Uses vanilla's Point of Interest system to find existing nether portals.
     */
    @Unique private Optional<BlockPos> findExistingPortalPOI(ServerLevel world, BlockPos targetPos) {
        PoiManager poiStorage = world.getPoiManager();

        // Search radius: 128 blocks in overworld, 16 in nether (vanilla behavior)
        int searchRadius = world.dimensionType().hasCeiling() ? 16 : 128;

        return poiStorage
                .getInSquare(
                        poiType -> poiType.is(PoiTypes.NETHER_PORTAL),
                        targetPos,
                        searchRadius,
                        PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .min(Comparator.comparingDouble(portalPos -> portalPos.distSqr(targetPos)));
    }
}
