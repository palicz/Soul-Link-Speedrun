package net.zenzty.soullink.mixin.server;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.village.raid.Raid;
import net.minecraft.village.raid.RaidManager;

/**
 * Accessor mixin to get the raids map from RaidManager. Needed to iterate through all active raids
 * to clear their bossbars when starting a new run.
 */
@Mixin(RaidManager.class)
public interface RaidManagerAccessor {

    @Accessor("raids")
    Int2ObjectMap<Raid> getRaids();
}
