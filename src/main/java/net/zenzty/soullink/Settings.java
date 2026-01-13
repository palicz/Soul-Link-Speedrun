package net.zenzty.soullink;

import net.minecraft.world.Difficulty;

/**
 * Holds all configurable settings for the Soul Link mod. Settings are applied on the next run when
 * confirmed in the settings GUI.
 */
public class Settings {

    private static Settings instance = new Settings();

    // Current active settings (used during runs)
    private Difficulty difficulty = Difficulty.NORMAL;
    private boolean halfHeartMode = false;
    private boolean sharedPotions = false;
    private boolean sharedJumping = false;

    private Settings() {}

    public static Settings getInstance() {
        return instance;
    }

    // ==================== DIFFICULTY ====================

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    /**
     * Cycles to the next difficulty level. Order: EASY -> NORMAL -> HARD -> EASY (no Peaceful)
     */
    public Difficulty getNextDifficulty() {
        return switch (difficulty) {
            case PEACEFUL, EASY -> Difficulty.NORMAL;
            case NORMAL -> Difficulty.HARD;
            case HARD -> Difficulty.EASY;
        };
    }

    // ==================== HALF HEART MODE ====================

    public boolean isHalfHeartMode() {
        return halfHeartMode;
    }

    public void setHalfHeartMode(boolean halfHeartMode) {
        this.halfHeartMode = halfHeartMode;
    }

    // ==================== SHARED POTIONS ====================

    public boolean isSharedPotions() {
        return sharedPotions;
    }

    public void setSharedPotions(boolean sharedPotions) {
        this.sharedPotions = sharedPotions;
    }

    // ==================== SHARED JUMPING ====================

    public boolean isSharedJumping() {
        return sharedJumping;
    }

    public void setSharedJumping(boolean sharedJumping) {
        this.sharedJumping = sharedJumping;
    }

    // ==================== UTILITY ====================

    /**
     * Creates a copy of the current settings for temporary editing in the GUI.
     */
    public SettingsSnapshot createSnapshot() {
        return new SettingsSnapshot(difficulty, halfHeartMode, sharedPotions, sharedJumping);
    }

    /**
     * Applies settings from a snapshot.
     */
    public void applySnapshot(SettingsSnapshot snapshot) {
        this.difficulty = snapshot.difficulty();
        this.halfHeartMode = snapshot.halfHeartMode();
        this.sharedPotions = snapshot.sharedPotions();
        this.sharedJumping = snapshot.sharedJumping();
        SoulLink.LOGGER.info(
                "Settings applied: Difficulty={}, HalfHeart={}, SharedPotions={}, SharedJumping={}",
                difficulty, halfHeartMode, sharedPotions, sharedJumping);
    }

    /**
     * Immutable snapshot of settings for comparison and temporary editing.
     */
    public record SettingsSnapshot(Difficulty difficulty, boolean halfHeartMode,
            boolean sharedPotions, boolean sharedJumping) {
        public boolean equals(SettingsSnapshot other) {
            return this.difficulty == other.difficulty && this.halfHeartMode == other.halfHeartMode
                    && this.sharedPotions == other.sharedPotions
                    && this.sharedJumping == other.sharedJumping;
        }
    }
}
