package net.zenzty.soullink.mixin;

import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin to set the EnderDragonFight on ServerWorld.
 * Needed because Fantasy temporary End worlds don't automatically get one.
 */
@Mixin(ServerWorld.class)
public interface ServerWorldAccessor {
    
    @Accessor("enderDragonFight")
    EnderDragonFight getEnderDragonFight();
    
    @Mutable
    @Accessor("enderDragonFight")
    void setEnderDragonFight(EnderDragonFight fight);
}

