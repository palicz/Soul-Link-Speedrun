package net.zenzty.soullink.common;

/**
 * Shared constants for the SoulLink mod.
 */
public final class SoulLinkConstants {
    public static final String MOD_ID = "soullink";
    public static final String MOD_NAME = "Soul Link Speedrun";
    public static final double SWARM_INTELLIGENCE_RANGE = 32.0;
    public static final int SWARM_INTELLIGENCE_COOLDOWN_TICKS = 40;
    public static final double STATIC_DISCHARGE_LIGHTNING_RADIUS = 6;
    public static final int STATIC_DISCHARGE_LIGHTNING_BOLTS = 10;
    public static final int STATIC_DISCHARGE_LIGHTNING_DELAY_MIN_TICKS = 5;
    public static final int STATIC_DISCHARGE_LIGHTNING_DELAY_MAX_TICKS = 10;
    public static final float STATIC_DISCHARGE_SLOWNESS_RADIUS = 3.5f;
    public static final int STATIC_DISCHARGE_SLOWNESS_CLOUD_TICKS = 20;
    public static final int STATIC_DISCHARGE_SLOWNESS_EFFECT_TICKS = 60;
    public static final int STATIC_DISCHARGE_SLOWNESS_COOLDOWN_TICKS = 20;

    private SoulLinkConstants() {
        // Prevent instantiation
    }
}
