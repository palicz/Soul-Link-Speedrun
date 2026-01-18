package net.zenzty.soullink.server.settings;

import net.minecraft.world.Difficulty;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.run.RunState;

/**
 * Holds all configurable settings for the Soul Link mod. Settings are applied on the next run when
 * confirmed in the settings GUI.
 */
public class Settings {

    private static final Settings instance = new Settings();

    // Current active settings (used during runs)
    private Difficulty difficulty = Difficulty.NORMAL;
    private boolean halfHeartMode = false;
    private boolean sharedPotions = false;
    private boolean sharedJumping = false;

    // Pending settings to be applied on next run
    private SettingsSnapshot pendingSnapshot = null;

    private Settings() {}

    public static Settings getInstance() {
        return instance;
    }

    // ==================== DIFFICULTY ====================

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        // Normalize Peaceful to Easy since mod doesn't support it
        this.difficulty = (difficulty == Difficulty.PEACEFUL) ? Difficulty.EASY : difficulty;
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
     * Applies settings from a snapshot. If a run is active, all changes except difficulty are
     * deferred until the next run.
     */
    public void applySnapshot(SettingsSnapshot snapshot) {
        // Check if a run is active
        RunManager runManager = RunManager.getInstance();
        boolean runActive = runManager != null && (runManager.getGameState() == RunState.RUNNING
                || runManager.getGameState() == RunState.GENERATING_WORLD);

        if (runActive) {
            // Check if anything actually changed
            SettingsSnapshot current = createSnapshot();
            if (snapshot.equals(current)) {
                this.pendingSnapshot = null;
                return;
            }

            // Defer all changes until next run
            this.pendingSnapshot = snapshot;
            SoulLink.LOGGER.info("Settings changes queued for next run: {}", snapshot);
        } else {
            // No active run - apply immediately
            applySnapshotInternal(snapshot);
            this.pendingSnapshot = null;
        }
    }

    /**
     * Internal method to apply all settings from a snapshot immediately.
     */
    private void applySnapshotInternal(SettingsSnapshot snapshot) {
        setDifficulty(snapshot.difficulty());
        this.halfHeartMode = snapshot.halfHeartMode();
        this.sharedPotions = snapshot.sharedPotions();
        this.sharedJumping = snapshot.sharedJumping();

        SoulLink.LOGGER.info(
                "Settings applied: Difficulty={}, HalfHeart={}, SharedPotions={}, SharedJumping={}",
                difficulty, halfHeartMode, sharedPotions, sharedJumping);
    }

    /**
     * Applies any pending settings. Called when a new run starts.
     */
    public void applyPendingSettings() {
        if (pendingSnapshot != null) {
            SoulLink.LOGGER.info("Applying pending settings for new run...");
            applySnapshotInternal(pendingSnapshot);
            pendingSnapshot = null;
        }
    }

    /**
     * Immutable snapshot of settings for comparison and temporary editing.
     */
    public record SettingsSnapshot(Difficulty difficulty, boolean halfHeartMode,
            boolean sharedPotions, boolean sharedJumping) {
    }
}
