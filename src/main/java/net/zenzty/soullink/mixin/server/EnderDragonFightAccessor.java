package net.zenzty.soullink.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.boss.dragon.EnderDragonFight;

/**
 * Accessor mixin to get the bossBar from EnderDragonFight. Needed to clear the boss bar when
 * starting a new run, as the vanilla Ender Dragon bossbar persists if players start a new run
 * before the game cleans it up naturally.
 */
@Mixin(EnderDragonFight.class)
public interface EnderDragonFightAccessor {

    @Accessor("bossBar")
    ServerBossBar getBossBar();
}
