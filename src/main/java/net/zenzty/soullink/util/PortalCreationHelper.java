package net.zenzty.soullink.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Utility class for creating nether portals in temporary dimensions.
 * Shared logic for flint and steel, fire charges, and fire block placement.
 */
public class PortalCreationHelper {
    
    /**
     * Tries to create a nether portal at the given position.
     * Checks for valid obsidian frame and fills it with portal blocks.
     * 
     * @param world The server world
     * @param startPos The position inside the portal frame to start checking from
     * @return true if a portal was successfully created
     */
    public static boolean tryCreatePortal(ServerWorld world, BlockPos startPos) {
        // Try X axis first, then Z axis
        if (createPortalWithAxis(world, startPos, Direction.Axis.X)) {
            return true;
        }
        return createPortalWithAxis(world, startPos, Direction.Axis.Z);
    }
    
    /**
     * Creates a portal along the specified axis if a valid frame exists.
     */
    private static boolean createPortalWithAxis(ServerWorld world, BlockPos startPos, Direction.Axis axis) {
        // Find the bounds of the portal frame
        Direction widthDir = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        
        // Find left edge
        BlockPos leftEdge = startPos;
        while (world.getBlockState(leftEdge.offset(widthDir.getOpposite())).isAir() || 
               world.getBlockState(leftEdge.offset(widthDir.getOpposite())).isOf(Blocks.FIRE)) {
            leftEdge = leftEdge.offset(widthDir.getOpposite());
            if (Math.abs(leftEdge.getX() - startPos.getX()) > 21 || Math.abs(leftEdge.getZ() - startPos.getZ()) > 21) {
                return false; // Too wide
            }
        }
        
        // Check left wall is obsidian
        if (!world.getBlockState(leftEdge.offset(widthDir.getOpposite())).isOf(Blocks.OBSIDIAN)) {
            return false;
        }
        
        // Find bottom edge
        BlockPos bottomLeft = leftEdge;
        while (world.getBlockState(bottomLeft.down()).isAir() || 
               world.getBlockState(bottomLeft.down()).isOf(Blocks.FIRE)) {
            bottomLeft = bottomLeft.down();
            if (bottomLeft.getY() < startPos.getY() - 21) {
                return false; // Too tall
            }
        }
        
        // Check bottom is obsidian
        if (!world.getBlockState(bottomLeft.down()).isOf(Blocks.OBSIDIAN)) {
            return false;
        }
        
        // Measure width
        int width = 0;
        BlockPos current = bottomLeft;
        while (true) {
            BlockState state = world.getBlockState(current);
            BlockState bottomState = world.getBlockState(current.down());
            
            if (state.isAir() || state.isOf(Blocks.FIRE)) {
                if (!bottomState.isOf(Blocks.OBSIDIAN)) {
                    return false; // Bottom must be obsidian
                }
                width++;
                current = current.offset(widthDir);
                if (width > 21) return false;
            } else if (state.isOf(Blocks.OBSIDIAN)) {
                break; // Found right edge
            } else {
                return false; // Invalid block inside frame
            }
        }
        
        if (width < 2) return false; // Too narrow
        
        // Measure height
        int height = 0;
        current = bottomLeft;
        while (true) {
            BlockState state = world.getBlockState(current);
            BlockState leftState = world.getBlockState(current.offset(widthDir.getOpposite()));
            
            if (state.isAir() || state.isOf(Blocks.FIRE)) {
                if (!leftState.isOf(Blocks.OBSIDIAN)) {
                    return false; // Left wall must be obsidian
                }
                height++;
                current = current.up();
                if (height > 21) return false;
            } else if (state.isOf(Blocks.OBSIDIAN)) {
                break; // Found top edge
            } else {
                return false; // Invalid block inside frame
            }
        }
        
        if (height < 3) return false; // Too short
        
        // Validate the entire frame
        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                BlockPos checkPos = bottomLeft.offset(widthDir, w).up(h);
                BlockState state = world.getBlockState(checkPos);
                
                if (!state.isAir() && !state.isOf(Blocks.FIRE) && !state.isOf(Blocks.NETHER_PORTAL)) {
                    return false; // Something blocking the inside
                }
            }
            
            // Check top row is obsidian
            BlockPos topPos = bottomLeft.offset(widthDir, w).up(height);
            if (!world.getBlockState(topPos).isOf(Blocks.OBSIDIAN)) {
                return false;
            }
        }
        
        // Validate right wall
        for (int h = 0; h < height; h++) {
            BlockPos rightPos = bottomLeft.offset(widthDir, width).up(h);
            if (!world.getBlockState(rightPos).isOf(Blocks.OBSIDIAN)) {
                return false;
            }
        }
        
        // Valid frame! Fill with portal blocks
        BlockState portalState = Blocks.NETHER_PORTAL.getDefaultState()
                .with(NetherPortalBlock.AXIS, axis);
        
        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                BlockPos portalPos = bottomLeft.offset(widthDir, w).up(h);
                world.setBlockState(portalPos, portalState);
            }
        }
        
        // Play portal sound
        world.playSound(null, bottomLeft, 
            net.minecraft.sound.SoundEvents.BLOCK_PORTAL_TRIGGER, 
            net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
        
        return true;
    }
}

