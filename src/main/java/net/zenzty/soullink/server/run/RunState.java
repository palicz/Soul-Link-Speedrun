package net.zenzty.soullink.server.run;

/**
 * Represents the state of the current run lifecycle.
 */
public enum RunState {
    /** No active run. */
    IDLE,

    /** World created, players in spectator, searching for spawn. */
    GENERATING_WORLD,

    /** Game in progress. */
    RUNNING,

    /** Game ended (victory or defeat). */
    GAMEOVER
}
