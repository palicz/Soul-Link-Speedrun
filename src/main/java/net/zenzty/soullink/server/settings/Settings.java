package net.zenzty.soullink.server.settings;

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
    private RunDifficulty difficulty = RunDifficulty.NORMAL;
    private boolean halfHeartMode = false;
    private boolean sharedPotions = false;
    private boolean sharedJumping = false;
    private boolean manhuntMode = false;
    private boolean damageLogEnabled = true; // Combat log - can be toggled immediately
    private boolean swarmIntelligence = true;
    private boolean staticDischarge = true;

    // Pending settings to be applied on next run
    private SettingsSnapshot pendingSnapshot = null;

    private Settings() {}

    public static Settings getInstance() {
        return instance;
    }

    // ==================== DIFFICULTY ====================

    public RunDifficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(RunDifficulty difficulty) {
        this.difficulty = difficulty == null ? RunDifficulty.NORMAL : difficulty;
    }

    public net.minecraft.world.Difficulty getVanillaDifficulty() {
        return difficulty.toVanilla();
    }

    /**
     * Cycles to the next difficulty level. Order: EASY -> NORMAL -> HARD -> EASY (no Peaceful)
     */
    public RunDifficulty getNextDifficulty() {
        return difficulty.next();
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

    // ==================== MANHUNT MODE ====================

    /**
     * Whether Manhunt mode is enabled. When true, a Runner/Hunter selector is shown before the run.
     * Runners share Soul Link (health, hunger); Hunters use vanilla mechanics and get tracking
     * compasses.
     */
    public boolean isManhuntMode() {
        return manhuntMode;
    }

    /**
     * Whether Manhunt will be enabled for the next run. If the player confirmed changes in /chaos
     * during an active run, those are pending and this returns the pending Manhunt value; otherwise
     * the current setting. Use this when deciding to open the Runner/Hunter selector before
     * startRun, because applyPendingSettings runs inside startRun.
     */
    public boolean isManhuntModeForNextRun() {
        return pendingSnapshot != null ? pendingSnapshot.manhuntMode() : manhuntMode;
    }

    public void setManhuntMode(boolean manhuntMode) {
        this.manhuntMode = manhuntMode;
    }

    // ==================== DAMAGE LOG ====================

    public boolean isDamageLogEnabled() {
        return damageLogEnabled;
    }

    public void setDamageLogEnabled(boolean damageLogEnabled) {
        this.damageLogEnabled = damageLogEnabled;
    }

    // ==================== SWARM INTELLIGENCE ====================

    public boolean isSwarmIntelligenceEnabled() {
        return swarmIntelligence;
    }

    public void setSwarmIntelligenceEnabled(boolean swarmIntelligence) {
        this.swarmIntelligence = swarmIntelligence;
    }

    // ==================== STATIC DISCHARGE ====================

    public boolean isStaticDischargeEnabled() {
        return staticDischarge;
    }

    public void setStaticDischargeEnabled(boolean staticDischarge) {
        this.staticDischarge = staticDischarge;
    }

    // ==================== UTILITY ====================

    /**
     * Returns the pending snapshot if one exists (changes confirmed in /chaos during an active
     * run). Used by the Chaos GUI to pre-fill with pending values so the player sees what is
     * already queued instead of the in-memory (current-run) values.
     */
    public SettingsSnapshot getPendingSnapshotOrNull() {
        return pendingSnapshot;
    }

    /**
     * Creates a copy of the current settings for temporary editing in the GUI.
     */
    public SettingsSnapshot createSnapshot() {
        return new SettingsSnapshot(difficulty, halfHeartMode, sharedPotions, sharedJumping,
                manhuntMode);
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
            // Already queued this exact snapshot (e.g. re-confirm without changing) – no-op
            if (pendingSnapshot != null && snapshot.equals(pendingSnapshot)) {
                return;
            }
            // User reverted to the current applied state – clear pending
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
        this.manhuntMode = snapshot.manhuntMode();

        SoulLink.LOGGER.info(
                "Settings applied: Difficulty={}, HalfHeart={}, SharedPotions={}, SharedJumping={}, Manhunt={}",
                difficulty, halfHeartMode, sharedPotions, sharedJumping, manhuntMode);
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
    public record SettingsSnapshot(RunDifficulty difficulty, boolean halfHeartMode,
            boolean sharedPotions, boolean sharedJumping, boolean manhuntMode) {
    }
}
