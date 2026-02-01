package net.zenzty.soullink.server.event;

import java.util.UUID;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.zenzty.soullink.common.SoulLinkConstants;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;

/**
 * When a player attacks an Enderman (and the feature is enabled), the Enderman teleports away, is
 * given TNT by the server, teleports back near the player, places and lights the TNT, then
 * teleports away again.
 */
public final class EndermanTntRevengeHandler {

    private EndermanTntRevengeHandler() {}

    /**
     * Called when an Enderman was damaged by a player. Schedules: give TNT + teleport back, then
     * place lit TNT at player's current position + teleport away.
     */
    public static void onEndermanDamagedByPlayer(EndermanEntity enderman,
            ServerPlayerEntity attacker, ServerWorld world) {
        if (!Settings.getInstance().isTeleportSwapEnabled()) {
            return;
        }
        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            return;
        }
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }
        if (!runManager.isTemporaryWorld(world.getRegistryKey())) {
            return;
        }

        // Immediately teleport the Enderman away when attacked
        teleportAway(enderman, world, new Vec3d(enderman.getX(), enderman.getY(), enderman.getZ()));

        int endermanId = enderman.getId();
        var attackerUuid = attacker.getUuid();

        // Phase 1: after delay, give Enderman TNT and teleport it back near the player (current
        // pos)
        EventRegistry.scheduleDelayed(SoulLinkConstants.ENDERMAN_TNT_REVENGE_GIVE_TNT_TICKS,
                () -> phaseGiveTntAndTeleportBack(world, endermanId, attackerUuid));

        // Phase 2: after longer delay, place lit TNT at player's current position and teleport away
        EventRegistry.scheduleDelayed(SoulLinkConstants.ENDERMAN_TNT_REVENGE_PLACE_TNT_TICKS,
                () -> phasePlaceTntAndTeleportAway(world, endermanId, attackerUuid));
    }

    /** Teleports the Enderman to a random position away from the given position. */
    private static void teleportAway(EndermanEntity enderman, ServerWorld world, Vec3d from) {
        double range = SoulLinkConstants.ENDERMAN_TNT_REVENGE_TELEPORT_AWAY_RANGE;
        double awayX = from.x + (world.getRandom().nextDouble() - 0.5) * 2 * range;
        double awayZ = from.z + (world.getRandom().nextDouble() - 0.5) * 2 * range;
        int awayY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(awayX), (int) Math.floor(awayZ));
        enderman.teleport(world, awayX, awayY, awayZ, java.util.Set.of(), enderman.getYaw(),
                enderman.getPitch(), true);
    }

    private static void phaseGiveTntAndTeleportBack(ServerWorld world, int endermanId,
            UUID attackerUuid) {
        Entity entity = world.getEntityById(endermanId);
        if (!(entity instanceof EndermanEntity enderman) || !enderman.isAlive()) {
            return;
        }
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(attackerUuid);
        if (player == null || !player.getEntityWorld().equals(world)) {
            return;
        }
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());

        // Server "gives" the Enderman TNT (carried block)
        enderman.setCarriedBlock(Blocks.TNT.getDefaultState());

        // Teleport Enderman back near the player (small random offset)
        double range = SoulLinkConstants.ENDERMAN_TNT_REVENGE_TELEPORT_BACK_RANGE;
        double x = playerPos.x + (world.getRandom().nextDouble() - 0.5) * 2 * range;
        double z = playerPos.z + (world.getRandom().nextDouble() - 0.5) * 2 * range;
        double y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(x), (int) Math.floor(z));
        enderman.teleport(world, x, y, z, java.util.Set.of(), enderman.getYaw(),
                enderman.getPitch(), true);
    }

    private static void phasePlaceTntAndTeleportAway(ServerWorld world, int endermanId,
            UUID attackerUuid) {
        Entity entity = world.getEntityById(endermanId);
        if (!(entity instanceof EndermanEntity enderman) || !enderman.isAlive()) {
            return;
        }
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(attackerUuid);
        if (player == null || !player.getEntityWorld().equals(world)) {
            return;
        }

        // Place and light TNT at the player's current position (short fuse)
        BlockPos pos = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
        TntEntity tntEntity =
                EntityType.TNT.create(world, null, pos, SpawnReason.EVENT, false, false);
        if (tntEntity != null) {
            tntEntity.setFuse(SoulLinkConstants.ENDERMAN_TNT_REVENGE_FUSE_TICKS);
            world.spawnEntity(tntEntity);
        }

        // Clear carried block (Enderman "put down" the TNT)
        enderman.setCarriedBlock(Blocks.AIR.getDefaultState());

        // Teleport Enderman away again
        teleportAway(enderman, world, new Vec3d(enderman.getX(), enderman.getY(), enderman.getZ()));
    }
}
