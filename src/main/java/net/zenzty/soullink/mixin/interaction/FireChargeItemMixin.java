package net.zenzty.soullink.mixin.interaction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.FireChargeItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.util.PortalCreationHelper;

/**
 * Mixin for FireChargeItem to allow nether portal creation in temporary dimensions.
 */
@Mixin(FireChargeItem.class)
public abstract class FireChargeItemMixin {

    /**
     * Intercept fire charge usage to force portal creation in temporary dimensions.
     */
    @Inject(method = "useOnBlock", at = @At("HEAD"), cancellable = true)
    private void onUseOnBlock(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        World world = context.getWorld();

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

        BlockPos clickedPos = context.getBlockPos();
        BlockState clickedState = world.getBlockState(clickedPos);

        // Check if we're clicking on obsidian
        if (!clickedState.isOf(Blocks.OBSIDIAN)) {
            return;
        }

        BlockPos insidePos = clickedPos.offset(context.getSide());

        // Try to create a portal at this location
        if (PortalCreationHelper.tryCreatePortal(serverWorld, insidePos)) {
            SoulLink.LOGGER.info(
                    "Created nether portal with fire charge in temporary dimension at {}",
                    insidePos);

            // Consume the fire charge
            var player = context.getPlayer();
            if (player != null && !player.getAbilities().creativeMode) {
                context.getStack().decrement(1);
            }

            // Play sound
            world.playSound(null, insidePos, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS,
                    1.0f, 1.0f);

            cir.setReturnValue(ActionResult.SUCCESS);
        }
    }
}

