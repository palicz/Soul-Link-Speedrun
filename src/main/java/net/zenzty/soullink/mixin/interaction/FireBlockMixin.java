package net.zenzty.soullink.mixin.interaction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.util.PortalCreationHelper;

/**
 * Mixin for FireBlock to detect when fire is placed next to obsidian and create portals.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    /**
     * Intercept fire block placement to check if it should create a portal.
     */
    @Inject(method = "onBlockAdded", at = @At("HEAD"), cancellable = true)
    private void onFirePlaced(BlockState state, World world, BlockPos pos, BlockState oldState,
            boolean notify, CallbackInfo ci) {
        // Quick guard to return early when the previous block was already fire
        if (oldState.isOf(state.getBlock())) {
            return;
        }

        // Only process on server in temporary worlds
        if (world.isClient() || !(world instanceof ServerWorld serverWorld)) {
            return;
        }

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        // Only handle in our temporary overworld or nether
        if (!runManager.isTemporaryWorld(world.getRegistryKey())) {
            return;
        }

        // Check all 6 directions around the fire for obsidian
        // If fire is placed next to obsidian, try to create a portal
        for (Direction direction : Direction.values()) {
            BlockPos adjacentPos = pos.offset(direction);
            BlockState adjacentState = world.getBlockState(adjacentPos);

            if (adjacentState.isOf(Blocks.OBSIDIAN)) {
                // Fire is next to obsidian - check if we can create a portal
                if (PortalCreationHelper.tryCreatePortal(serverWorld, pos)) {
                    if (SoulLink.LOGGER.isDebugEnabled()) {
                        SoulLink.LOGGER.debug(
                                "Created nether portal from fire placement in temporary dimension at {}",
                                pos);
                    }
                    // Fire will be replaced by portal blocks, so we don't need to remove it
                    ci.cancel();
                    break; // Only create one portal
                }
            }
        }
    }
}

