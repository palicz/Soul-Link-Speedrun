package net.zenzty.soullink.mixin.entity;

import java.util.List;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.CombatMutatorConstants;
import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.util.SwarmAlertAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEntity.class)
public class ZombieEntityMixin implements SwarmAlertAccessor {

    @Unique
    private long soullink$lastSwarmAlertTick =
            -CombatMutatorConstants.SWARM_INTELLIGENCE_COOLDOWN_TICKS;

    @Unique
    private boolean soullink$skipSwarmAlert = false;

    @Override
    public void soullink$setSkipSwarmAlert(boolean skip) {
        this.soullink$skipSwarmAlert = skip;
    }

    @Override
    public boolean soullink$shouldSkipSwarmAlert() {
        return this.soullink$skipSwarmAlert;
    }

    @Inject(method = "setTarget", at = @At("TAIL"))
    private void soullink$alertNearbyZombies(LivingEntity target, CallbackInfo ci) {
        MobEntity self = (MobEntity) (Object) this;
        if (!(self instanceof ZombieEntity)
                || soullink$skipSwarmAlert
                || target == null
                || !(target instanceof PlayerEntity)) {
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

        if (!Settings.getInstance().isSwarmIntelligenceEnabled()) {
            return;
        }

        long currentTick = serverWorld.getTime();
        if (currentTick - soullink$lastSwarmAlertTick
                < CombatMutatorConstants.SWARM_INTELLIGENCE_COOLDOWN_TICKS) {
            return;
        }
        soullink$lastSwarmAlertTick = currentTick;

        Box searchBox = self.getBoundingBox().expand(
                CombatMutatorConstants.SWARM_INTELLIGENCE_RANGE);
        List<ZombieEntity> nearbyZombies = serverWorld.getEntitiesByClass(ZombieEntity.class,
                searchBox, zombie -> zombie.isAlive() && zombie != self);
        for (ZombieEntity zombie : nearbyZombies) {
            if (zombie.getTarget() == target) {
                continue;
            }
            SwarmAlertAccessor accessor = (SwarmAlertAccessor) zombie;
            accessor.soullink$setSkipSwarmAlert(true);
            zombie.setTarget(target);
            accessor.soullink$setSkipSwarmAlert(false);
        }
    }
}
