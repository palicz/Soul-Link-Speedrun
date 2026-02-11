package net.zenzty.soullink.server.inventory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;

/**
 * Handles synced inventory mode: when enabled, all players in the run share the same inventory
 * (main, hotbar, armor, offhand). Any change by one player is propagated to everyone to prevent
 * duplication. Only players that participate in Soul Link (runners in Manhunt, everyone otherwise)
 * are synced.
 */
public class SharedInventoryHandler {

    /**
     * Player inventory size: 36 main (9 hotbar + 27 main) + 4 armor + 1 offhand = 41.
     */
    public static final int PLAYER_INVENTORY_SIZE = 41;

    private static final List<ItemStack> master = new ArrayList<>(PLAYER_INVENTORY_SIZE);
    private static volatile boolean isSyncing = false;

    static {
        for (int i = 0; i < PLAYER_INVENTORY_SIZE; i++) {
            master.add(ItemStack.EMPTY);
        }
    }

    /**
     * Returns whether the given player should participate in shared inventory (same as Soul Link
     * participation: runners in Manhunt, everyone in run otherwise).
     */
    private static boolean shouldParticipate(ServerPlayerEntity player) {
        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            return false;
        }
        if (runManager == null || !runManager.isRunActive()) {
            return false;
        }
        if (!Settings.getInstance().isSyncedInventory()) {
            return false;
        }
        if (!runManager.isPlayerInRun(player)) {
            return false;
        }
        if (Settings.getInstance().isManhuntMode()
                && ManhuntManager.getInstance().isHunter(player)) {
            return false;
        }
        return true;
    }

    /**
     * Resets the shared inventory (clears master). Call when starting a new run.
     */
    public static void reset() {
        synchronized (master) {
            for (int i = 0; i < PLAYER_INVENTORY_SIZE; i++) {
                master.set(i, ItemStack.EMPTY);
            }
        }
        isSyncing = false;
        SoulLink.LOGGER.info("Shared inventory reset.");
    }

    /**
     * Returns true if we are currently applying a sync (to avoid re-entering from setStack mixin).
     */
    public static boolean isSyncing() {
        return isSyncing;
    }

    /**
     * Copies the given player's inventory into the master state. Call after the player's inventory
     * has been modified (e.g. after setStack).
     */
    public static void copyFromPlayer(ServerPlayerEntity player) {
        if (player == null || player.isRemoved()) {
            return;
        }
        var inv = player.getInventory();
        if (inv == null) {
            return;
        }
        int size = Math.min(inv.size(), PLAYER_INVENTORY_SIZE);
        synchronized (master) {
            for (int i = 0; i < size; i++) {
                try {
                    ItemStack stack = inv.getStack(i);
                    master.set(i,
                            stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
                } catch (Exception e) {
                    SoulLink.LOGGER.warn("Error copying inventory slot {} from player {}: {}", i,
                            player.getName().getString(), e.getMessage());
                    master.set(i, ItemStack.EMPTY);
                }
            }
        }
    }

    /**
     * Applies the master inventory state to the given player. Does not trigger the setStack mixin
     * when isSyncing is true.
     */
    public static void applyToPlayer(ServerPlayerEntity player) {
        if (player == null || player.isRemoved()) {
            return;
        }
        var inv = player.getInventory();
        if (inv == null) {
            return;
        }
        int size = Math.min(inv.size(), PLAYER_INVENTORY_SIZE);
        List<ItemStack> snapshot;
        synchronized (master) {
            snapshot = new ArrayList<>(master);
        }
        for (int i = 0; i < size; i++) {
            try {
                ItemStack current = inv.getStack(i);
                ItemStack target = snapshot.get(i);
                if (target == null) {
                    target = ItemStack.EMPTY;
                } else if (!target.isEmpty()) {
                    target = target.copy();
                }
                if (ItemStack.areEqual(current, target)) {
                    continue;
                }
                inv.setStack(i, target);
            } catch (Exception e) {
                SoulLink.LOGGER.warn("Error applying inventory slot {} to player {}: {}", i,
                        player.getName().getString(), e.getMessage());
            }
        }
    }

    /**
     * Called when a player's inventory slot was just changed. Copies that player's full inventory
     * to master and applies it to all other participating players. Prevents duplication by making
     * one source of truth (the changer's state) and overwriting everyone else.
     */
    public static void syncFromPlayerToAll(ServerPlayerEntity sourcePlayer) {
        if (isSyncing) {
            return;
        }
        // Validate player is still valid and connected
        if (sourcePlayer == null || sourcePlayer.isRemoved() || !sourcePlayer.isAlive()) {
            return;
        }
        if (!shouldParticipate(sourcePlayer)) {
            return;
        }

        RunManager runManager = RunManager.getInstance();
        if (runManager == null) {
            return;
        }
        MinecraftServer server = runManager.getServer();
        if (server == null) {
            return;
        }

        isSyncing = true;
        try {
            copyFromPlayer(sourcePlayer);

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player == sourcePlayer) {
                    continue;
                }
                // Validate each target player before syncing
                if (player == null || player.isRemoved() || !player.isAlive()) {
                    continue;
                }
                if (!shouldParticipate(player)) {
                    continue;
                }
                applyToPlayer(player);
            }

            SoulLink.LOGGER.debug("Synced inventory from {} to all participants",
                    sourcePlayer.getName().getString());
        } catch (Exception e) {
            SoulLink.LOGGER.error("Error syncing inventory from {}: {}",
                    sourcePlayer != null ? sourcePlayer.getName().getString() : "null", e);
        } finally {
            isSyncing = false;
        }
    }

    /**
     * Syncs the current master inventory to the given player (e.g. late joiner). Call after
     * teleporting the player into the run.
     */
    public static void syncPlayerToShared(ServerPlayerEntity player) {
        if (!Settings.getInstance().isSyncedInventory()) {
            return;
        }
        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }
        if (Settings.getInstance().isManhuntMode()
                && ManhuntManager.getInstance().isHunter(player)) {
            return;
        }
        if (!runManager.isPlayerInRun(player)) {
            return;
        }

        isSyncing = true;
        try {
            applyToPlayer(player);
            SoulLink.LOGGER.debug("Synced shared inventory to late joiner {}",
                    player.getName().getString());
        } finally {
            isSyncing = false;
        }
    }
}
