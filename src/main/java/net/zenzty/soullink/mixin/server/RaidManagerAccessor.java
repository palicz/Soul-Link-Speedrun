package net.zenzty.soullink.mixin.server;

import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.village.raid.Raid;
import net.minecraft.village.raid.RaidManager;

/**
 * Accessor mixin to get the raids map from RaidManager. Needed to iterate through all active raids
 * to clear their bossbars when starting a new run.
 *
 * @see <a href=
 *      "https://maven.fabricmc.net/docs/yarn-1.21.1+build.3/net/minecraft/village/raid/RaidManager.html">RaidManager
 *      (yarn 1.21.1+build.3)</a>
 */
@Mixin(RaidManager.class)
public interface RaidManagerAccessor {

    @Accessor("raids")
    Map<Integer, Raid> getRaids();
}
