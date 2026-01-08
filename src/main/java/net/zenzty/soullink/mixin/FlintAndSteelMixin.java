package net.zenzty.soullink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.zenzty.soullink.RunManager;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.util.PortalCreationHelper;

/**
 * Mixin for FlintAndSteelItem to allow nether portal creation in temporary dimensions. Fantasy
 * dimensions have custom registry keys, so vanilla portal ignition might not work.
 */
@Mixin(FlintAndSteelItem.class)
public abstract class FlintAndSteelMixin {

    /**
     * Intercept flint and steel usage to force portal creation in temporary dimensions.
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
            SoulLink.LOGGER.info("Created nether portal in temporary dimension at {}", insidePos);

            // Damage the flint and steel
            if (context.getPlayer() != null) {
                context.getStack().damage(1, context.getPlayer(),
                        context.getPlayer().getActiveHand());
            }

            // Play sound
            world.playSound(null, insidePos, net.minecraft.sound.SoundEvents.ITEM_FLINTANDSTEEL_USE,
                    net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);

            cir.setReturnValue(ActionResult.SUCCESS);
        }
    }
}
