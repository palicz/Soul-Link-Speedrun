package net.zenzty.soullink.server.run;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.zenzty.soullink.SoulLink;

public class WorldPoolManager {
    private static final int MAX_POOL_SIZE = 1;
    private final Queue<PooledRun> readyRuns = new ConcurrentLinkedQueue<>();

    private final WorldService worldService;
    private final SpawnFinder backgroundSpawnFinder = new SpawnFinder();

    private boolean isGenerating = false;
    private PooledRun currentlyBuildingRun = null;

    public WorldPoolManager(WorldService worldService) {
        this.worldService = worldService;
    }

    public void tick(MinecraftServer server) {
        if (readyRuns.size() >= MAX_POOL_SIZE) {
            return;
        }

        if (!isGenerating) {
            SoulLink.LOGGER.info("Background worker: Preparing a new world for the pool...");
            isGenerating = true;

            currentlyBuildingRun = worldService.buildBackgroundWorlds();

            backgroundSpawnFinder.startSearch(currentlyBuildingRun.overworld().asLevel());
            return;
        }

        if (backgroundSpawnFinder.isSearchComplete()) {
            BlockPos spawn = backgroundSpawnFinder.getSpawnPos();
            if (spawn == null) spawn = new BlockPos(0, 64, 0); // Biztonsági fallback

            PooledRun finishedRun = currentlyBuildingRun.withSpawn(spawn);
            readyRuns.add(finishedRun);

            isGenerating = false;
            currentlyBuildingRun = null;
            SoulLink.LOGGER.info("Background worker: New world added to pool!");
        }
    }

    public PooledRun claimNextRun() {
        return readyRuns.poll();
    }

    public void cleanup() {
        PooledRun run;
        while ((run = readyRuns.poll()) != null) {
            if (run.overworld() != null) run.overworld().delete();
            if (run.nether() != null) run.nether().delete();
            if (run.end() != null) run.end().delete();
        }
        if (currentlyBuildingRun != null) {
            if (currentlyBuildingRun.overworld() != null)
                currentlyBuildingRun.overworld().delete();
            if (currentlyBuildingRun.nether() != null)
                currentlyBuildingRun.nether().delete();
            if (currentlyBuildingRun.end() != null) currentlyBuildingRun.end().delete();
        }
    }
}
