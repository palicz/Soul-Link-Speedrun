package net.zenzty.soullink.mixin.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.util.PortalCreationHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for FlintAndSteelItem to allow nether portal creation in temporary dimensions. Fantasy
 * dimensions have custom registry keys, so vanilla portal ignition might not work.
 */
@Mixin(FlintAndSteelItem.class)
public abstract class FlintAndSteelMixin {

    /**
     * Intercept flint and steel usage to force portal creation in temporary dimensions.
     */
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void onUseOnBlock(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Level world = context.getLevel();

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

        BlockPos clickedPos = context.getClickedPos();
        BlockState clickedState = world.getBlockState(clickedPos);

        // Check if we're clicking on obsidian
        if (!clickedState.is(Blocks.OBSIDIAN)) {
            return;
        }

        BlockPos insidePos = clickedPos.relative(context.getClickedFace());

        // Try to create a portal at this location
        if (PortalCreationHelper.tryCreatePortal(serverWorld, insidePos)) {
            SoulLink.LOGGER.info("Created nether portal in temporary dimension at {}", insidePos);

            // Damage the flint and steel
            if (context.getPlayer() instanceof ServerPlayer player) {
                context.getItemInHand().hurtAndBreak(1, player, context.getHand());
            }

            // Play sound
            world.playSound(
                    null,
                    insidePos,
                    net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE,
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    1.0f,
                    1.0f);

            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }
}
