package net.zenzty.soullink.util;

/**
 * Accessor to prevent recursive swarm alerts when setting zombie targets.
 */
public interface SwarmAlertAccessor {
    void soullink$setSkipSwarmAlert(boolean skip);

    boolean soullink$shouldSkipSwarmAlert();
}
