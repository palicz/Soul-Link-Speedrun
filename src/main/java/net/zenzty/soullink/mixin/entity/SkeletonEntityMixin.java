package net.zenzty.soullink.mixin.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.server.world.ServerWorld;
import net.zenzty.soullink.common.SoulLinkConstants;
import net.zenzty.soullink.server.event.EventRegistry;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.RunDifficulty;
import net.zenzty.soullink.server.settings.Settings;

/**
 * Mixin to modify skeleton behavior in extreme mode.
 * When skeletons shoot arrows, they fire 3 arrows total with 10-tick delays between shots.
 */
@Mixin(AbstractSkeletonEntity.class)
public abstract class SkeletonEntityMixin {

    @Unique
    private boolean soullink$isScheduledShot = false;

    /**
     * Intercepts when a skeleton shoots an arrow.
     * In extreme mode, schedules 2 additional arrows with delays.
     * Only triggers on natural shots (not scheduled shots) to prevent infinite loops.
     */
    @Inject(method = "shootAt", at = @At("TAIL"))
    private void soullink$extremeModeTripleShot(LivingEntity target, float pullProgress,
            CallbackInfo ci) {
        AbstractSkeletonEntity self = (AbstractSkeletonEntity) (Object) this;
        
        // Skip if this is a scheduled shot (from our delayed task) to prevent recursion
        SkeletonEntityMixin mixin = (SkeletonEntityMixin) (Object) self;
        if (mixin.soullink$isScheduledShot) {
            mixin.soullink$isScheduledShot = false; // Reset flag
            return;
        }
        
        // Only apply to SkeletonEntity, not StrayEntity or other subclasses
        if (!(self instanceof SkeletonEntity)) {
            return;
        }

        // Only process on server
        if (!(self.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        // Check if extreme mode is active
        if (Settings.getInstance().getDifficulty() != RunDifficulty.EXTREME) {
            return;
        }

        // Verify run is active
        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            return;
        }

        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        // Only handle in temporary worlds
        if (!runManager.isTemporaryWorld(serverWorld.getRegistryKey())) {
            return;
        }

        // Ensure skeleton has a target
        if (target == null || !target.isAlive()) {
            return;
        }

        // Store references for the delayed tasks
        AbstractSkeletonEntity finalSelf = self;
        LivingEntity finalTarget = target;
        float finalPullProgress = pullProgress;

        // Schedule 2 additional arrows with delays
        // First additional arrow after delay
        EventRegistry.scheduleDelayed(SoulLinkConstants.SKELETON_ARROW_DELAY_TICK, () -> {
            if (finalSelf.isRemoved() || !finalSelf.isAlive() || finalTarget.isRemoved()
                    || !finalTarget.isAlive()) {
                return;
            }
            if (!runManager.isRunActive()) {
                return;
            }
            // Shoot another arrow at the same target
            shootArrow(finalSelf, finalTarget, finalPullProgress, serverWorld);
        });

        // Second additional arrow after double delay
        EventRegistry.scheduleDelayed(SoulLinkConstants.SKELETON_ARROW_DELAY_TICK * 2, () -> {
            if (finalSelf.isRemoved() || !finalSelf.isAlive() || finalTarget.isRemoved()
                    || !finalTarget.isAlive()) {
                return;
            }
            if (!runManager.isRunActive()) {
                return;
            }
            // Shoot another arrow at the same target
            shootArrow(finalSelf, finalTarget, finalPullProgress, serverWorld);
        });
    }

    /**
     * Manually creates and shoots an arrow from the skeleton to the target.
     * Marks the shot as scheduled to prevent the mixin from triggering again.
     */
    private static void shootArrow(AbstractSkeletonEntity skeleton, LivingEntity target,
            float pullProgress, ServerWorld world) {
        // Mark as scheduled shot to prevent recursion
        SkeletonEntityMixin mixin = (SkeletonEntityMixin) (Object) skeleton;
        mixin.soullink$isScheduledShot = true;
        // Use the skeleton's own shootAt method to ensure proper arrow creation
        skeleton.shootAt(target, pullProgress);
    }
}
