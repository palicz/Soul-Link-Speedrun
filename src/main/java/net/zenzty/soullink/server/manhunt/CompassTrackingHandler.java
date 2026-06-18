package net.zenzty.soullink.server.manhunt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
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

    private static final Map<UUID, UUID> HUNTER_TARGETS = new HashMap<>();
    private static final Map<UUID, Map<ResourceKey<Level>, GlobalPos>> LAST_KNOWN_POSITIONS = new HashMap<>();
    /** Hunter UUID -> server tick until which the timer must not overwrite the action bar. */
    private static final Map<UUID, Integer> ACTION_BAR_SUPPRESS_UNTIL_TICK = new HashMap<>();

    /**
     * Registers the compass use event. Call this once during mod initialization.
     */
    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()) {
                return InteractionResult.PASS;
            }

            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(Items.COMPASS)) {
                return InteractionResult.PASS;
            }

            ManhuntManager manhunt = ManhuntManager.getInstance();
            if (!manhunt.isHunter(serverPlayer)) {
                return InteractionResult.PASS;
            }

            MinecraftServer server = serverPlayer.level().getServer();
            if (server != null) {
                cycleTarget(serverPlayer, server);
            }

            return InteractionResult.SUCCESS;
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

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (manhunt.isSpeedrunner(player)) {
                updateLastKnownPosition(player);
            }
        }

        for (ServerPlayer hunter : server.getPlayerList().getPlayers()) {
            if (manhunt.isHunter(hunter)) {
                updateCompassForHunter(hunter, server);
            }
        }
    }

    private static void updateLastKnownPosition(ServerPlayer runner) {
        UUID runnerId = runner.getUUID();
        ResourceKey<Level> dimension = runner.level().dimension();
        GlobalPos currentPos = GlobalPos.of(dimension, runner.blockPosition());

        LAST_KNOWN_POSITIONS.computeIfAbsent(runnerId, k -> new HashMap<>()).put(dimension, currentPos);
    }

    private static void cycleTarget(ServerPlayer hunter, MinecraftServer server) {
        ManhuntManager manhunt = ManhuntManager.getInstance();
        List<ServerPlayer> runners = new ArrayList<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (manhunt.isSpeedrunner(player)) {
                runners.add(player);
            }
        }

        if (runners.isEmpty()) {
            hunter.sendOverlayMessage(Component.literal("No runners to track!").withStyle(ChatFormatting.RED));
            markCompassMessageShown(hunter.getUUID(), server);
            return;
        }

        UUID hunterId = hunter.getUUID();
        UUID currentTarget = HUNTER_TARGETS.get(hunterId);

        int currentIndex = -1;
        if (currentTarget != null) {
            for (int i = 0; i < runners.size(); i++) {
                if (runners.get(i).getUUID().equals(currentTarget)) {
                    currentIndex = i;
                    break;
                }
            }
        }

        int nextIndex = (currentIndex + 1) % runners.size();
        ServerPlayer newTarget = runners.get(nextIndex);
        HUNTER_TARGETS.put(hunterId, newTarget.getUUID());

        ResourceKey<Level> hunterDimension = hunter.level().dimension();
        ResourceKey<Level> targetDimension = newTarget.level().dimension();

        if (hunterDimension.equals(targetDimension)) {
            hunter.sendOverlayMessage(Component.literal("Now tracking: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(newTarget.getName().getString())
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        } else {
            hunter.sendOverlayMessage(Component.literal("Target in another dimension - showing last location")
                    .withStyle(ChatFormatting.YELLOW));
        }
        markCompassMessageShown(hunter.getUUID(), server);

        updateCompassForHunter(hunter, server);

        SoulLink.LOGGER.debug(
                "Hunter {} now tracking runner {}",
                hunter.getName().getString(),
                newTarget.getName().getString());
    }

    private static void updateCompassForHunter(ServerPlayer hunter, MinecraftServer server) {
        UUID targetId = HUNTER_TARGETS.get(hunter.getUUID());
        if (targetId == null) {
            return;
        }

        ServerPlayer runner = server.getPlayerList().getPlayer(targetId);
        GlobalPos targetPos = null;

        if (runner != null) {
            ResourceKey<Level> hunterDimension = hunter.level().dimension();
            ResourceKey<Level> runnerDimension = runner.level().dimension();

            if (hunterDimension.equals(runnerDimension)) {
                targetPos = GlobalPos.of(runnerDimension, runner.blockPosition());
            } else {
                Map<ResourceKey<Level>, GlobalPos> runnerPositions = LAST_KNOWN_POSITIONS.get(targetId);
                if (runnerPositions != null) {
                    targetPos = runnerPositions.get(hunterDimension);
                }
            }
        }

        for (int i = 0; i < hunter.getInventory().getContainerSize(); i++) {
            ItemStack stack = hunter.getInventory().getItem(i);
            if (stack.is(Items.COMPASS)) {
                if (targetPos != null) {
                    LodestoneTracker tracker = new LodestoneTracker(Optional.of(targetPos), false);
                    stack.set(DataComponents.LODESTONE_TRACKER, tracker);
                } else {
                    stack.remove(DataComponents.LODESTONE_TRACKER);
                }
            }
        }
    }

    /**
     * Gives a tracking compass to a hunter.
     */
    public static void giveTrackingCompass(ServerPlayer hunter) {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(
                DataComponents.CUSTOM_NAME,
                Component.literal("Runner Tracker")
                        .setStyle(Style.EMPTY.applyFormat(ChatFormatting.RED).withItalic(false)));
        compass.set(
                DataComponents.LORE,
                new ItemLore(List.of(Component.literal("Right Click to swap target")
                        .setStyle(Style.EMPTY.applyFormat(ChatFormatting.GRAY).withItalic(false)))));
        hunter.getInventory().add(compass);
        SoulLink.LOGGER.info(
                "Gave tracking compass to hunter {}", hunter.getName().getString());
    }

    /**
     * Marks that a compass tracking message was shown on the action bar. The timer will not
     * overwrite it for 3 seconds.
     */
    private static void markCompassMessageShown(UUID hunterId, MinecraftServer server) {
        ACTION_BAR_SUPPRESS_UNTIL_TICK.put(hunterId, server.getTickCount() + COMPASS_MESSAGE_TICKS);
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
        Integer until = ACTION_BAR_SUPPRESS_UNTIL_TICK.get(playerId);
        if (until == null) return false;
        if (currentTick >= until) {
            ACTION_BAR_SUPPRESS_UNTIL_TICK.remove(playerId);
            return false;
        }
        return true;
    }

    /**
     * Resets all tracking state. Called when a new run starts or run ends.
     */
    public static void reset() {
        tickCounter = 0;
        HUNTER_TARGETS.clear();
        LAST_KNOWN_POSITIONS.clear();
        ACTION_BAR_SUPPRESS_UNTIL_TICK.clear();
    }
}
