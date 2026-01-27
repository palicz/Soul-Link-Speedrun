package net.zenzty.soullink.mixin.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.zenzty.soullink.common.SoulLinkConstants;
import net.zenzty.soullink.server.event.EventRegistry;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;

@Mixin(CreeperEntity.class)
public class CreeperEntityMixin {

    @Accessor("CHARGED")
    static TrackedData<Boolean> soullink$getChargedTrackedData() {
        throw new AssertionError();
    }

    @Unique
    private long soullink$lastSlownessCloudTick =
            -SoulLinkConstants.STATIC_DISCHARGE_SLOWNESS_COOLDOWN_TICKS;

    @Inject(method = "tick", at = @At("HEAD"))
    private void soullink$ensureAlwaysCharged(CallbackInfo ci) {
        CreeperEntity self = (CreeperEntity) (Object) this;
        if (!self.getEntityWorld().isClient()) {
            self.getDataTracker().set(soullink$getChargedTrackedData(), true);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void soullink$emitStaticDischargeCloud(CallbackInfo ci) {
        CreeperEntity self = (CreeperEntity) (Object) this;
        if (self.getFuseSpeed() <= 0) {
            return;
        }

        if (!(self.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            return;
        }

        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        if (!runManager.isTemporaryWorld(serverWorld.getRegistryKey())) {
            return;
        }

        if (!Settings.getInstance().isStaticDischargeEnabled()) {
            return;
        }

        long currentTick = serverWorld.getTime();
        if (currentTick
                - soullink$lastSlownessCloudTick < SoulLinkConstants.STATIC_DISCHARGE_SLOWNESS_COOLDOWN_TICKS) {
            return;
        }
        soullink$lastSlownessCloudTick = currentTick;

        AreaEffectCloudEntity cloud =
                new AreaEffectCloudEntity(serverWorld, self.getX(), self.getY(), self.getZ());
        cloud.setRadius(SoulLinkConstants.STATIC_DISCHARGE_SLOWNESS_RADIUS);
        cloud.setDuration(SoulLinkConstants.STATIC_DISCHARGE_SLOWNESS_CLOUD_TICKS);
        cloud.setWaitTime(0);
        cloud.addEffect(new StatusEffectInstance(StatusEffects.SLOWNESS,
                SoulLinkConstants.STATIC_DISCHARGE_SLOWNESS_EFFECT_TICKS, 0));
        cloud.setOwner(self);
        serverWorld.spawnEntity(cloud);
    }

    @Inject(method = "explode", at = @At("TAIL"))
    private void soullink$spawnStaticDischargeLightning(CallbackInfo ci) {
        CreeperEntity self = (CreeperEntity) (Object) this;

        if (!(self.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            return;
        }

        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        if (!runManager.isTemporaryWorld(serverWorld.getRegistryKey())) {
            return;
        }

        if (!Settings.getInstance().isStaticDischargeEnabled()) {
            return;
        }

        Random random = serverWorld.getRandom();
        int cumulativeDelay = 0;
        for (int i = 0; i < SoulLinkConstants.STATIC_DISCHARGE_LIGHTNING_BOLTS; i++) {
            double offsetX = (random.nextDouble() * 2.0 - 1.0)
                    * SoulLinkConstants.STATIC_DISCHARGE_LIGHTNING_RADIUS;
            double offsetZ = (random.nextDouble() * 2.0 - 1.0)
                    * SoulLinkConstants.STATIC_DISCHARGE_LIGHTNING_RADIUS;
            BlockPos strikePos =
                    BlockPos.ofFloored(self.getX() + offsetX, self.getY(), self.getZ() + offsetZ);
            int delayTicks = SoulLinkConstants.STATIC_DISCHARGE_LIGHTNING_DELAY_MIN_TICKS
                    + random.nextInt(SoulLinkConstants.STATIC_DISCHARGE_LIGHTNING_DELAY_MAX_TICKS
                            - SoulLinkConstants.STATIC_DISCHARGE_LIGHTNING_DELAY_MIN_TICKS + 1);
            cumulativeDelay += delayTicks;
            EventRegistry.scheduleDelayed(cumulativeDelay, () -> {
                LightningEntity lightning = EntityType.LIGHTNING_BOLT.create(serverWorld, null,
                        strikePos, SpawnReason.TRIGGERED, true, true);
                if (lightning != null) {
                    serverWorld.spawnEntity(lightning);
                }
            });
        }
    }
}
