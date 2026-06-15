package net.zenzty.soullink.server.run;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.zenzty.soullink.SoulLink;

import java.util.Random;

public class SpawnFinder {

    private BlockPos validSpawnPos = null;
    private boolean searchComplete = false;
    private int attempts = 0;
    private final Random random = new Random();

    public void reset() {
        validSpawnPos = null;
        searchComplete = false;
        attempts = 0;
    }

    public void injectSpawnPos(BlockPos pos) {
        this.validSpawnPos = pos;
        this.searchComplete = true;
    }

    public void startSearch(ServerLevel world) {
        reset();
        searchWithVanillaReroll(world, new BlockPos(0, 64, 0));
    }

    private void searchWithVanillaReroll(ServerLevel world, BlockPos suggestion) {
        attempts++;
        if (attempts > 50) {
            validSpawnPos = new BlockPos(0, 64, 0);
            searchComplete = true;
            return;
        }

        // Vanilla async findSpawn
        PlayerSpawnFinder.findSpawn(world, suggestion).whenCompleteAsync((vec, throwable) -> {
            if (throwable != null || vec == null) {
                SoulLink.LOGGER.warn("Vanilla SpawnFinder did not find a location around {}, rerolling...", suggestion);
                reroll(world, suggestion);
                return;
            }

            BlockPos foundPos = BlockPos.containing(vec);

            if (isOceanBiome(world, foundPos)) {
                SoulLink.LOGGER.info("Vanilla found water here: {}. Rerolling... (Attempt: {})", foundPos, attempts);
                reroll(world, suggestion);
            } else {
                SoulLink.LOGGER.info("Perfect land spawn found: {} (after {} attempts)", foundPos, attempts);
                validSpawnPos = foundPos;
                searchComplete = true;
            }
        }, world.getServer());
    }

    private void reroll(ServerLevel world, BlockPos oldSuggestion) {
        int jumpDistanceX = 500 + random.nextInt(1000);
        int jumpDistanceZ = 500 + random.nextInt(1000);

        int newX = oldSuggestion.getX() + (random.nextBoolean() ? jumpDistanceX : -jumpDistanceX);
        int newZ = oldSuggestion.getZ() + (random.nextBoolean() ? jumpDistanceZ : -jumpDistanceZ);

        BlockPos newSuggestion = new BlockPos(newX, 64, newZ);
        searchWithVanillaReroll(world, newSuggestion);
    }

    private boolean isOceanBiome(ServerLevel world, BlockPos pos) {
        try {
            Holder<Biome> biome = world.getBiome(pos);
            return biome.is(BiomeTags.IS_OCEAN) ||
                    biome.is(BiomeTags.IS_DEEP_OCEAN) ||
                    biome.is(BiomeTags.IS_RIVER) ||
                    biome.is(BiomeTags.IS_BEACH);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isSearchComplete() {
        return searchComplete;
    }

    public BlockPos getSpawnPos() {
        return validSpawnPos;
    }

    public boolean hasFoundSpawn() {
        return validSpawnPos != null;
    }
}