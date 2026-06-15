package net.zenzty.soullink.server.run;

import net.minecraft.core.BlockPos;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/**
 * Pregenerated, in-memory run data
 */
public record PooledRun(
        RuntimeLevelHandle overworld,
        RuntimeLevelHandle nether,
        RuntimeLevelHandle end,
        long seed,
        BlockPos spawnPos
) {
    public PooledRun withSpawn(BlockPos newSpawn) {
        return new PooledRun(this.overworld, this.nether, this.end, this.seed, newSpawn);
    }
}