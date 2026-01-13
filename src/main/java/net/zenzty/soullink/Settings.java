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

    // Pending shared jumping setting (applied on next run)
    private Boolean pendingSharedJumping = null;

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
     * Applies settings from a snapshot. Shared jumping is deferred until the next run if a run is
     * currently active.
     */
    public void applySnapshot(SettingsSnapshot snapshot) {
        this.difficulty = snapshot.difficulty();
        this.halfHeartMode = snapshot.halfHeartMode();
        this.sharedPotions = snapshot.sharedPotions();

        // Check if a run is active - if so, defer shared jumping until next run
        RunManager runManager = RunManager.getInstance();
        boolean runActive =
                runManager != null && (runManager.getGameState() == RunManager.GameState.RUNNING
                        || runManager.getGameState() == RunManager.GameState.GENERATING_WORLD);

        if (runActive && snapshot.sharedJumping() != this.sharedJumping) {
            // Defer the change until next run
            this.pendingSharedJumping = snapshot.sharedJumping();
            SoulLink.LOGGER.info(
                    "Settings applied: Difficulty={}, HalfHeart={}, SharedPotions={}, SharedJumping={} (deferred until next run)",
                    difficulty, halfHeartMode, sharedPotions, snapshot.sharedJumping());
        } else {
            // Apply immediately (no active run)
            this.sharedJumping = snapshot.sharedJumping();
            this.pendingSharedJumping = null;
            SoulLink.LOGGER.info(
                    "Settings applied: Difficulty={}, HalfHeart={}, SharedPotions={}, SharedJumping={}",
                    difficulty, halfHeartMode, sharedPotions, sharedJumping);
        }
    }

    /**
     * Applies any pending shared jumping setting. Called when a new run starts.
     */
    public void applyPendingSharedJumping() {
        if (pendingSharedJumping != null) {
            this.sharedJumping = pendingSharedJumping;
            SoulLink.LOGGER.info("Applied pending shared jumping setting: {}", sharedJumping);
            pendingSharedJumping = null;
        }
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
