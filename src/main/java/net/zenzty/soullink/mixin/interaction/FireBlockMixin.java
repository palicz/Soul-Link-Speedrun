package net.zenzty.soullink.mixin.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.util.PortalCreationHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for FireBlock to detect when fire is placed next to obsidian and create portals.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    /**
     * Intercept fire block placement to check if it should create a portal.
     */
    @Inject(method = "onPlace", at = @At("HEAD"), cancellable = true)
    private void onFirePlaced(
            BlockState state, Level world, BlockPos pos, BlockState oldState, boolean notify, CallbackInfo ci) {
        // Quick guard to return early when the previous block was already fire
        if (oldState.is(state.getBlock())) {
            return;
        }

        // Only process on server in temporary worlds
        if (world.isClientSide() || !(world instanceof ServerLevel serverWorld)) {
            return;
        }

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        // Only handle in our temporary overworld or nether
        if (!runManager.isTemporaryWorld(world.dimension())) {
            return;
        }

        // Check all 6 directions around the fire for obsidian
        // If fire is placed next to obsidian, try to create a portal
        for (Direction direction : Direction.values()) {
            BlockPos adjacentPos = pos.relative(direction);
            BlockState adjacentState = world.getBlockState(adjacentPos);

            if (adjacentState.is(Blocks.OBSIDIAN)) {
                // Fire is next to obsidian - check if we can create a portal
                if (PortalCreationHelper.tryCreatePortal(serverWorld, pos)) {
                    if (SoulLink.LOGGER.isDebugEnabled()) {
                        SoulLink.LOGGER.debug(
                                "Created nether portal from fire placement in temporary dimension at {}", pos);
                    }
                    // Fire will be replaced by portal blocks, so we don't need to remove it
                    ci.cancel();
                    break; // Only create one portal
                }
            }
        }
    }
}
