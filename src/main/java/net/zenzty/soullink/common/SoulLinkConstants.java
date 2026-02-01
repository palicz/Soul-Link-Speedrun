package net.zenzty.soullink.common;

/**
 * Shared constants for the SoulLink mod.
 */
public final class SoulLinkConstants {
    public static final String MOD_ID = "soullink";
    public static final String MOD_NAME = "Soul Link Speedrun";
    public static final double SWARM_INTELLIGENCE_RANGE = 32.0;
    public static final int SWARM_INTELLIGENCE_COOLDOWN_TICKS = 200;
    public static final double STATIC_DISCHARGE_LIGHTNING_RADIUS = 6;
    public static final int STATIC_DISCHARGE_LIGHTNING_BOLTS = 8;
    public static final int STATIC_DISCHARGE_LIGHTNING_DELAY_MIN_TICKS = 5;
    public static final int STATIC_DISCHARGE_LIGHTNING_DELAY_MAX_TICKS = 8;
    public static final float STATIC_DISCHARGE_SLOWNESS_RADIUS = 3.5f;
    public static final int STATIC_DISCHARGE_SLOWNESS_CLOUD_TICKS = 20;
    public static final int STATIC_DISCHARGE_SLOWNESS_EFFECT_TICKS = 60;
    public static final int STATIC_DISCHARGE_SLOWNESS_COOLDOWN_TICKS = 20;
    public static final int SKELETON_ARROW_DELAY_TICK = 10;
    public static final double WITHER_SKELETON_CHAIN_RANGE = 16.0;
    public static final int WITHER_SKELETON_SPEED_BOOST_DURATION_TICKS = 200; // 10 seconds
    public static final int WITHER_SKELETON_SPEED_BOOST_AMPLIFIER = 1;
    public static final int EXTRA_HOSTILE_SPAWN_INTERVAL_TICKS = 100; // every 5 seconds
    public static final int EXTRA_HOSTILE_SPAWN_PER_PLAYER = 4;
    public static final double EXTRA_HOSTILE_SPAWN_MIN_RADIUS = 16.0;
    public static final double EXTRA_HOSTILE_SPAWN_RADIUS = 32.0;
    public static final int EXTRA_HOSTILE_SPAWN_MAX_NEARBY = 32;
    public static final double EXTRA_HOSTILE_DESPAWN_RADIUS = 64.0;
    public static final double ENDERMAN_TELEPORT_SWAP_RANGE = 16.0;
    public static final double ENDERMAN_TELEPORT_SWAP_SEARCH_RANGE = 32.0;
    public static final int ENDERMAN_TELEPORT_SWAP_COOLDOWN_TICKS = 100; // 5 seconds

    // Enderman TNT revenge: when a player attacks an Enderman, it teleports away, gets TNT,
    // teleports back, places lit TNT, then teleports away again.
    public static final int ENDERMAN_TNT_REVENGE_GIVE_TNT_TICKS = 40; // 2 sec after damage
    public static final int ENDERMAN_TNT_REVENGE_PLACE_TNT_TICKS = 60; // 3 sec (place + light)
    public static final double ENDERMAN_TNT_REVENGE_TELEPORT_BACK_RANGE = 4.0; // near player
    public static final double ENDERMAN_TNT_REVENGE_TELEPORT_AWAY_RANGE = 32; // away
    public static final int ENDERMAN_TNT_REVENGE_FUSE_TICKS = 20; // 1 second (default TNT is 80)

    private SoulLinkConstants() {
        // Prevent instantiation
    }
}
