package net.zenzty.soullink.mixin.server;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin to get the raids map from RaidManager. Needed to iterate through all active raids
 * to clear their bossbars when starting a new run.
 */
@Mixin(Raids.class)
public interface RaidManagerAccessor {

    @Accessor("raidMap")
    Int2ObjectMap<Raid> getRaids();
}
