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
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
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

    private static final int UPDATE_INTERVAL_TICKS = 20; // Update compass position every second
    private static int tickCounter = 0;

    // Which runner each hunter is currently tracking (hunter UUID -> runner UUID)
    private static final Map<UUID, UUID> hunterTargets = new HashMap<>();

    // Last known position of each runner in each dimension
    // runner UUID -> (dimension -> position)
    private static final Map<UUID, Map<RegistryKey<World>, GlobalPos>> lastKnownPositions =
            new HashMap<>();

    /**
     * Registers the compass use event. Call this once during mod initialization.
     */
    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            // Only handle on server side
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

            // Cycle to the next runner
            MinecraftServer server = serverPlayer.getEntityWorld().getServer();
            if (server != null) {
                cycleTarget(serverPlayer, server);
            }

            return ActionResult.SUCCESS;
        });

        SoulLink.LOGGER.info("Compass tracking handler registered");
    }

    /**
     * Called every server tick to update runner positions and hunter compasses.
     */
    public static void tick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter < UPDATE_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        ManhuntManager manhunt = ManhuntManager.getInstance();

        // Update last known positions for all runners
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (manhunt.isSpeedrunner(player)) {
                updateLastKnownPosition(player);
            }
        }

        // Update compass for all hunters
        for (ServerPlayerEntity hunter : server.getPlayerManager().getPlayerList()) {
            if (manhunt.isHunter(hunter)) {
                updateCompassForHunter(hunter, server);
            }
        }
    }

    /**
     * Records the runner's current position as their last known position in their current
     * dimension.
     */
    private static void updateLastKnownPosition(ServerPlayerEntity runner) {
        UUID runnerId = runner.getUuid();
        RegistryKey<World> dimension = runner.getEntityWorld().getRegistryKey();
        GlobalPos currentPos = GlobalPos.create(dimension, runner.getBlockPos());

        lastKnownPositions.computeIfAbsent(runnerId, k -> new HashMap<>()).put(dimension,
                currentPos);
    }

    /**
     * Cycles the hunter's target to the next available runner.
     */
    private static void cycleTarget(ServerPlayerEntity hunter, MinecraftServer server) {
        ManhuntManager manhunt = ManhuntManager.getInstance();
        List<ServerPlayerEntity> runners = new ArrayList<>();

        // Get all online runners
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (manhunt.isSpeedrunner(player)) {
                runners.add(player);
            }
        }

        if (runners.isEmpty()) {
            hunter.sendMessage(Text.literal("No runners to track!").formatted(Formatting.RED),
                    true);
            return;
        }

        UUID hunterId = hunter.getUuid();
        UUID currentTarget = hunterTargets.get(hunterId);

        // Find current index and cycle to next
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

        // Show feedback to hunter
        hunter.sendMessage(Text.literal("Now tracking: ").formatted(Formatting.GRAY)
                .append(Text.literal(newTarget.getName().getString()).formatted(Formatting.RED,
                        Formatting.BOLD)),
                true);

        // Immediately update the compass
        updateCompassForHunter(hunter, server);

        SoulLink.LOGGER.debug("Hunter {} now tracking runner {}", hunter.getName().getString(),
                newTarget.getName().getString());
    }

    /**
     * Updates all compasses in the hunter's inventory to point to their tracked runner.
     */
    private static void updateCompassForHunter(ServerPlayerEntity hunter, MinecraftServer server) {
        UUID targetId = hunterTargets.get(hunter.getUuid());
        if (targetId == null) {
            return;
        }

        // Try to get the runner's current position
        ServerPlayerEntity runner = server.getPlayerManager().getPlayer(targetId);
        GlobalPos targetPos = null;
        boolean isDifferentDimension = false;

        if (runner != null) {
            RegistryKey<World> hunterDimension = hunter.getEntityWorld().getRegistryKey();
            RegistryKey<World> runnerDimension = runner.getEntityWorld().getRegistryKey();

            if (hunterDimension.equals(runnerDimension)) {
                // Same dimension - point to runner's current position
                targetPos = GlobalPos.create(runnerDimension, runner.getBlockPos());
            } else {
                // Different dimension - use last known position in hunter's dimension
                isDifferentDimension = true;
                Map<RegistryKey<World>, GlobalPos> runnerPositions =
                        lastKnownPositions.get(targetId);
                if (runnerPositions != null) {
                    targetPos = runnerPositions.get(hunterDimension);
                }
            }
        }

        // Update all compasses in inventory
        for (int i = 0; i < hunter.getInventory().size(); i++) {
            ItemStack stack = hunter.getInventory().getStack(i);
            if (stack.isOf(Items.COMPASS)) {
                if (targetPos != null) {
                    LodestoneTrackerComponent tracker =
                            new LodestoneTrackerComponent(Optional.of(targetPos), false);
                    stack.set(DataComponentTypes.LODESTONE_TRACKER, tracker);
                } else {
                    // No valid position - compass spins
                    stack.remove(DataComponentTypes.LODESTONE_TRACKER);
                }
            }
        }

        // Show dimension warning in action bar if tracking cross-dimension
        if (isDifferentDimension && runner != null) {
            hunter.sendMessage(Text.literal("Target in another dimension - showing last location")
                    .formatted(Formatting.YELLOW), true);
        }
    }

    /**
     * Gives a tracking compass to a hunter.
     */
    public static void giveTrackingCompass(ServerPlayerEntity hunter) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("Runner Tracker").formatted(Formatting.RED, Formatting.BOLD));
        hunter.getInventory().insertStack(compass);
        SoulLink.LOGGER.info("Gave tracking compass to hunter {}", hunter.getName().getString());
    }

    /**
     * Resets all tracking state. Called when a new run starts.
     */
    public static void reset() {
        tickCounter = 0;
        hunterTargets.clear();
        lastKnownPositions.clear();
    }
}
