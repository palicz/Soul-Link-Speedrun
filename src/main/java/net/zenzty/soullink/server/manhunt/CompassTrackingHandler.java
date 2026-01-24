package net.zenzty.soullink.server.manhunt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import net.zenzty.soullink.SoulLink;

/**
 * Handles compass tracking for hunters in Manhunt mode. Hunters right-click their compass to cycle
 * between runners. If the tracked runner is in a different dimension, the compass points to their
 * last known location in the hunter's dimension.
 */
public class CompassTrackingHandler {

    private static final int UPDATE_INTERVAL_TICKS = 20;
    /** Ticks to suppress the timer on the action bar after a compass tracking message (3 seconds). */
    private static final int COMPASS_MESSAGE_TICKS = 60;
    private static int tickCounter = 0;

    private static final Map<UUID, UUID> hunterTargets = new HashMap<>();
    private static final Map<UUID, Map<RegistryKey<World>, GlobalPos>> lastKnownPositions =
            new HashMap<>();
    /** Hunter UUID -> server tick until which the timer must not overwrite the action bar. */
    private static final Map<UUID, Integer> actionBarSuppressUntilTick = new HashMap<>();

    /**
     * Registers the compass use event. Call this once during mod initialization.
     */
    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) {
                return ActionResult.PASS;
            }

            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }

            ItemStack stack = player.getStackInHand(hand);
            if (!stack.isOf(Items.COMPASS)) {
                return ActionResult.PASS;
            }

            ManhuntManager manhunt = ManhuntManager.getInstance();
            if (!manhunt.isHunter(serverPlayer)) {
                return ActionResult.PASS;
            }

            MinecraftServer server = serverPlayer.getEntityWorld().getServer();
            if (server != null) {
                cycleTarget(serverPlayer, server);
            }

            return ActionResult.SUCCESS;
        });

        SoulLink.LOGGER.info("Compass tracking handler registered");
    }

    /**
     * Called every server tick to update runner positions and hunter compasses. Only run when
     * Manhunt is active and a run is in progress.
     */
    public static void tick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter < UPDATE_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        ManhuntManager manhunt = ManhuntManager.getInstance();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (manhunt.isSpeedrunner(player)) {
                updateLastKnownPosition(player);
            }
        }

        for (ServerPlayerEntity hunter : server.getPlayerManager().getPlayerList()) {
            if (manhunt.isHunter(hunter)) {
                updateCompassForHunter(hunter, server);
            }
        }
    }

    private static void updateLastKnownPosition(ServerPlayerEntity runner) {
        UUID runnerId = runner.getUuid();
        RegistryKey<World> dimension = runner.getEntityWorld().getRegistryKey();
        GlobalPos currentPos = GlobalPos.create(dimension, runner.getBlockPos());

        lastKnownPositions.computeIfAbsent(runnerId, k -> new HashMap<>())
                .put(dimension, currentPos);
    }

    private static void cycleTarget(ServerPlayerEntity hunter, MinecraftServer server) {
        ManhuntManager manhunt = ManhuntManager.getInstance();
        List<ServerPlayerEntity> runners = new ArrayList<>();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (manhunt.isSpeedrunner(player)) {
                runners.add(player);
            }
        }

        if (runners.isEmpty()) {
            hunter.sendMessage(Text.literal("No runners to track!").formatted(Formatting.RED), true);
            markCompassMessageShown(hunter.getUuid(), server);
            return;
        }

        UUID hunterId = hunter.getUuid();
        UUID currentTarget = hunterTargets.get(hunterId);

        int currentIndex = -1;
        if (currentTarget != null) {
            for (int i = 0; i < runners.size(); i++) {
                if (runners.get(i).getUuid().equals(currentTarget)) {
                    currentIndex = i;
                    break;
                }
            }
        }

        int nextIndex = (currentIndex + 1) % runners.size();
        ServerPlayerEntity newTarget = runners.get(nextIndex);
        hunterTargets.put(hunterId, newTarget.getUuid());

        RegistryKey<World> hunterDimension = hunter.getEntityWorld().getRegistryKey();
        RegistryKey<World> targetDimension = newTarget.getEntityWorld().getRegistryKey();

        if (hunterDimension.equals(targetDimension)) {
            hunter.sendMessage(Text.literal("Now tracking: ").formatted(Formatting.GRAY)
                    .append(Text.literal(newTarget.getName().getString())
                            .formatted(Formatting.RED, Formatting.BOLD)),
                    true);
        } else {
            hunter.sendMessage(Text.literal("Target in another dimension - showing last location")
                    .formatted(Formatting.YELLOW), true);
        }
        markCompassMessageShown(hunter.getUuid(), server);

        updateCompassForHunter(hunter, server);

        SoulLink.LOGGER.debug("Hunter {} now tracking runner {}", hunter.getName().getString(),
                newTarget.getName().getString());
    }

    private static void updateCompassForHunter(ServerPlayerEntity hunter, MinecraftServer server) {
        UUID targetId = hunterTargets.get(hunter.getUuid());
        if (targetId == null) {
            return;
        }

        ServerPlayerEntity runner = server.getPlayerManager().getPlayer(targetId);
        GlobalPos targetPos = null;

        if (runner != null) {
            RegistryKey<World> hunterDimension = hunter.getEntityWorld().getRegistryKey();
            RegistryKey<World> runnerDimension = runner.getEntityWorld().getRegistryKey();

            if (hunterDimension.equals(runnerDimension)) {
                targetPos = GlobalPos.create(runnerDimension, runner.getBlockPos());
            } else {
                Map<RegistryKey<World>, GlobalPos> runnerPositions =
                        lastKnownPositions.get(targetId);
                if (runnerPositions != null) {
                    targetPos = runnerPositions.get(hunterDimension);
                }
            }
        }

        for (int i = 0; i < hunter.getInventory().size(); i++) {
            ItemStack stack = hunter.getInventory().getStack(i);
            if (stack.isOf(Items.COMPASS)) {
                if (targetPos != null) {
                    LodestoneTrackerComponent tracker =
                            new LodestoneTrackerComponent(Optional.of(targetPos), false);
                    stack.set(DataComponentTypes.LODESTONE_TRACKER, tracker);
                } else {
                    stack.remove(DataComponentTypes.LODESTONE_TRACKER);
                }
            }
        }
    }

    /**
     * Gives a tracking compass to a hunter.
     */
    public static void giveTrackingCompass(ServerPlayerEntity hunter) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Runner Tracker")
                .setStyle(Style.EMPTY.withFormatting(Formatting.RED).withItalic(false)));
        compass.set(DataComponentTypes.LORE,
                new LoreComponent(List.of(Text.literal("Right Click to swap target")
                        .setStyle(Style.EMPTY.withFormatting(Formatting.GRAY).withItalic(false)))));
        hunter.getInventory().insertStack(compass);
        SoulLink.LOGGER.info("Gave tracking compass to hunter {}", hunter.getName().getString());
    }

    /**
     * Marks that a compass tracking message was shown on the action bar. The timer will not
     * overwrite it for 3 seconds.
     */
    private static void markCompassMessageShown(UUID hunterId, MinecraftServer server) {
        actionBarSuppressUntilTick.put(hunterId, server.getTicks() + COMPASS_MESSAGE_TICKS);
    }

    /**
     * Returns whether the timer should not be sent to this player on the action bar. Used in
     * Manhunt so the "Now tracking: X" (and similar) compass messages stay visible for a few
     * seconds instead of being overwritten on the next tick.
     *
     * @param playerId the player UUID (only hunters are ever recorded)
     * @param currentTick the current server tick
     * @return true to suppress the timer action bar for this player
     */
    public static boolean shouldSuppressTimerActionBar(UUID playerId, int currentTick) {
        Integer until = actionBarSuppressUntilTick.get(playerId);
        if (until == null) return false;
        if (currentTick >= until) {
            actionBarSuppressUntilTick.remove(playerId);
            return false;
        }
        return true;
    }

    /**
     * Resets all tracking state. Called when a new run starts or run ends.
     */
    public static void reset() {
        tickCounter = 0;
        hunterTargets.clear();
        lastKnownPositions.clear();
        actionBarSuppressUntilTick.clear();
    }
}
