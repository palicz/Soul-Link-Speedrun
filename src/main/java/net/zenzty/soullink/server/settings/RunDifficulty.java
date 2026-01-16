package net.zenzty.soullink.server.settings;

import net.minecraft.world.Difficulty;

/**
 * Custom run difficulty options for the Soul Link mod.
 * EXTREME maps to vanilla HARD but enables extra modded behavior.
 */
public enum RunDifficulty {
    EASY,
    NORMAL,
    HARD,
    EXTREME;

    public RunDifficulty next() {
        return switch (this) {
            case EASY -> NORMAL;
            case NORMAL -> HARD;
            case HARD -> EXTREME;
            case EXTREME -> EASY;
        };
    }

    public Difficulty toVanilla() {
        return switch (this) {
            case EASY -> Difficulty.EASY;
            case NORMAL -> Difficulty.NORMAL;
            case HARD, EXTREME -> Difficulty.HARD;
        };
    }

    public static RunDifficulty fromVanilla(Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL, EASY -> EASY;
            case NORMAL -> NORMAL;
            case HARD -> HARD;
        };
    }
}
