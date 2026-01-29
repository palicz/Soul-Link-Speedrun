package net.zenzty.soullink.server.manhunt;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import net.zenzty.soullink.mixin.ui.ScreenHandlerAccessor;
import net.zenzty.soullink.server.run.RunManager;

/**
 * GUI for selecting Runners and Hunters before starting a Manhunt run. Shows player heads that can
 * be clicked to toggle roles. Accessible to spectators via SpectatorInteractionMixin.
 */
public class SpeedrunnerSelectorGui {

    private static final int INVENTORY_SIZE = 54;
    private static final int CONFIRM_SLOT = 49; // Bottom center

    // Player head slots: top row and side columns (0, 8) are filler; content fills rows 1–5,
    // cols 1–7 with no gaps. Confirm at 49; heads use the rest in row-major order.
    private static final int[] HEAD_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43, 46, 47, 48, 50, 51, 52};

    /**
     * Opens the role selector GUI for a player.
     */
    public static void open(ServerPlayerEntity player) {
        MinecraftServer server = RunManager.getInstance().getServer();
        if (server == null)
            return;

        // Reset roles when opening selector and default all to Runners
        ManhuntManager.getInstance().resetRoles();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ManhuntManager.getInstance().setRunner(p.getUuid());
        }

        SelectorInventory inventory = new SelectorInventory(server);

        player.openHandledScreen(
                new SimpleNamedScreenHandlerFactory((syncId, playerInventory, playerEntity) -> {
                    return new SelectorScreenHandler(syncId, inventory, player);
                }, Text.literal("Select Runners & Hunters").formatted(Formatting.DARK_GRAY)));
    }

    /** Builds styled text for GUI items (non-italic, with given formatings). */
    private static Text createItemName(String text, Formatting... formattings) {
        Style style = Style.EMPTY.withItalic(false);
        for (Formatting formatting : formattings) {
            style = style.withFormatting(formatting);
        }
        return Text.literal(text).setStyle(style);
    }

    /**
     * Inventory containing player heads and confirm button.
     */
    public static class SelectorInventory extends SimpleInventory {

        private final MinecraftServer server;
        private final List<UUID> playerOrder = new ArrayList<>();

        public SelectorInventory(MinecraftServer server) {
            super(INVENTORY_SIZE);
            this.server = server;
            populateItems();
        }

        public void populateItems() {
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                setStack(i, createFillerItem());
            }

            playerOrder.clear();

            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            for (int i = 0; i < players.size() && i < HEAD_SLOTS.length; i++) {
                int slot = HEAD_SLOTS[i];
                ServerPlayerEntity p = players.get(i);
                playerOrder.add(p.getUuid());
                setStack(slot, createPlayerHead(p));
            }

            setStack(CONFIRM_SLOT, createConfirmItem());
        }

        private ItemStack createFillerItem() {
            ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            filler.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
            return filler;
        }

        private ItemStack createPlayerHead(ServerPlayerEntity player) {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponentTypes.PROFILE, new ProfileComponent(player.getGameProfile()));

            boolean isRunner = ManhuntManager.getInstance().isSpeedrunner(player.getUuid());
            String role = isRunner ? "RUNNER" : "HUNTER";
            Formatting roleColor = isRunner ? Formatting.GREEN : Formatting.RED;

            head.set(DataComponentTypes.CUSTOM_NAME, createItemName(player.getName().getString(),
                    Formatting.WHITE, Formatting.BOLD));

            LoreComponent lore = new LoreComponent(List.of(
                    Text.literal("Role: ")
                            .setStyle(Style.EMPTY.withItalic(false).withFormatting(Formatting.GRAY))
                            .append(Text.literal(role).setStyle(
                                    Style.EMPTY.withItalic(false).withFormatting(roleColor))),
                    Text.empty(), isRunner
                            ? Text.literal("Shares health with other Runners")
                                    .setStyle(Style.EMPTY.withItalic(false)
                                            .withFormatting(Formatting.DARK_GRAY))
                            : Text.literal("Vanilla mechanics, hunts Runners")
                                    .setStyle(Style.EMPTY.withItalic(false)
                                            .withFormatting(Formatting.DARK_GRAY)),
                    Text.empty(), Text.literal("Click to toggle role").setStyle(
                            Style.EMPTY.withItalic(false).withFormatting(Formatting.YELLOW))));
            head.set(DataComponentTypes.LORE, lore);

            return head;
        }

        private ItemStack createConfirmItem() {
            ItemStack item = new ItemStack(Items.EMERALD);
            ManhuntManager manager = ManhuntManager.getInstance();
            int runnerCount = manager.getRunners().size();
            int hunterCount = manager.getHunters().size();
            boolean canStart = runnerCount > 0 && hunterCount > 0;

            if (canStart) {
                item.set(DataComponentTypes.CUSTOM_NAME,
                        createItemName("✓ Start Run", Formatting.GREEN, Formatting.BOLD));
            } else {
                item = new ItemStack(Items.BARRIER);
                if (runnerCount == 0 && hunterCount == 0) {
                    item.set(DataComponentTypes.CUSTOM_NAME, createItemName(
                            "✗ Need Runners & Hunters", Formatting.RED, Formatting.BOLD));
                } else if (runnerCount == 0) {
                    item.set(DataComponentTypes.CUSTOM_NAME, createItemName(
                            "✗ Need at least 1 Runner", Formatting.RED, Formatting.BOLD));
                } else {
                    item.set(DataComponentTypes.CUSTOM_NAME, createItemName(
                            "✗ Need at least 1 Hunter", Formatting.RED, Formatting.BOLD));
                }
            }

            LoreComponent lore = new LoreComponent(List.of(
                    Text.literal("Runners: ")
                            .setStyle(Style.EMPTY.withItalic(false).withFormatting(Formatting.GRAY))
                            .append(Text.literal(String.valueOf(runnerCount))
                                    .setStyle(Style.EMPTY.withItalic(false)
                                            .withFormatting(Formatting.GREEN))),
                    Text.literal("Hunters: ")
                            .setStyle(Style.EMPTY.withItalic(false).withFormatting(Formatting.GRAY))
                            .append(Text.literal(String.valueOf(hunterCount)).setStyle(
                                    Style.EMPTY.withItalic(false).withFormatting(Formatting.RED))),
                    Text.empty(),
                    canStart ? Text.literal("Click to start the run!").setStyle(
                            Style.EMPTY.withItalic(false).withFormatting(Formatting.YELLOW))
                            : Text.literal("Need at least 1 Runner and 1 Hunter").setStyle(
                                    Style.EMPTY.withItalic(false).withFormatting(Formatting.RED))));
            item.set(DataComponentTypes.LORE, lore);

            return item;
        }

        public UUID getPlayerAtSlot(int slot) {
            for (int i = 0; i < HEAD_SLOTS.length && i < playerOrder.size(); i++) {
                if (HEAD_SLOTS[i] == slot)
                    return playerOrder.get(i);
            }
            return null;
        }
    }

    private static class VirtualSlot extends Slot {
        public VirtualSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canTakeItems(PlayerEntity playerEntity) {
            return false;
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return false;
        }

        @Override
        public boolean canBeHighlighted() {
            return true;
        }
    }

    /**
     * Screen handler for the role selector GUI. Spectators can interact via
     * SpectatorInteractionMixin.
     */
    public static class SelectorScreenHandler extends ScreenHandler {

        private final SelectorInventory selectorInventory;
        private final ServerPlayerEntity player;

        public SelectorScreenHandler(int syncId, SelectorInventory inventory,
                ServerPlayerEntity player) {
            super(ScreenHandlerType.GENERIC_9X6, syncId);
            this.selectorInventory = inventory;
            this.player = player;
            checkSize(inventory, INVENTORY_SIZE);
            inventory.onOpen(player);

            for (int i = 0; i < INVENTORY_SIZE; i++) {
                int x = 8 + (i % 9) * 18;
                int y = 18 + (i / 9) * 18;
                this.addSlot(new VirtualSlot(inventory, i, x, y));
            }

            int headerOffset = 36;
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    this.addSlot(new Slot(player.getInventory(), col + row * 9 + 9, 8 + col * 18,
                            103 + row * 18 + headerOffset));
                }
            }
            for (int col = 0; col < 9; ++col) {
                this.addSlot(
                        new Slot(player.getInventory(), col, 8 + col * 18, 161 + headerOffset));
            }
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType,
                PlayerEntity clickingPlayer) {
            if (slotIndex < INVENTORY_SIZE && slotIndex >= 0) {
                handleSelectorClick(slotIndex);

                setCursorStack(ItemStack.EMPTY);

                if (clickingPlayer instanceof ServerPlayerEntity serverPlayer
                        && serverPlayer.interactionManager.getGameMode() == GameMode.SPECTATOR) {
                    ScreenHandlerAccessor accessor = (ScreenHandlerAccessor) this;
                    accessor.invokeUpdateToClient();
                } else {
                    sendContentUpdates();
                }
                return;
            }
            super.onSlotClick(slotIndex, button, actionType, clickingPlayer);
        }

        @Override
        public ItemStack quickMove(PlayerEntity playerEntity, int slot) {
            return ItemStack.EMPTY;
        }

        private void handleSelectorClick(int slotIndex) {
            if (slotIndex == CONFIRM_SLOT) {
                handleConfirm();
                return;
            }
            UUID clickedPlayer = selectorInventory.getPlayerAtSlot(slotIndex);
            if (clickedPlayer != null) {
                MinecraftServer server = RunManager.getInstance().getServer();
                if (server != null) {
                    server.execute(() -> {
                        ManhuntManager.getInstance().toggleRole(clickedPlayer);
                        selectorInventory.populateItems();
                        playClickSound();
                    });
                }
            }
        }

        private void handleConfirm() {
            ManhuntManager manager = ManhuntManager.getInstance();

            if (!manager.hasRunners()) {
                player.sendMessage(RunManager.formatMessage("Need at least one Runner to start!"),
                        false);
                playErrorSound();
                return;
            }

            if (!manager.hasHunters()) {
                player.sendMessage(RunManager.formatMessage("Need at least one Hunter to start!"),
                        false);
                playErrorSound();
                return;
            }

            // Run everything on the server main thread to avoid crashes: slot clicks can run
            // on the network thread, while closeHandledScreen, startRun (world/chunk work) and
            // broadcast must run on the main thread.
            RunManager runManager = RunManager.getInstance();
            if (runManager == null)
                return;
            MinecraftServer server = runManager.getServer();
            if (server == null)
                return;

            server.execute(() -> {
                if (player.isRemoved())
                    return;
                player.closeHandledScreen();
                playConfirmSound();
                broadcastRoleAssignments(server);
                runManager.startRun();
            });
        }

        /**
         * Broadcasts Runner and Hunter role assignments to all players.
         *
         * @param server the server (must be non-null)
         */
        private void broadcastRoleAssignments(MinecraftServer server) {
            ManhuntManager manager = ManhuntManager.getInstance();

            List<String> runnerNames = new ArrayList<>();
            for (UUID uuid : manager.getRunners()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null) {
                    runnerNames.add(p.getName().getString());
                }
            }

            List<String> hunterNames = new ArrayList<>();
            for (UUID uuid : manager.getHunters()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null) {
                    hunterNames.add(p.getName().getString());
                }
            }

            if (!runnerNames.isEmpty()) {
                Text runnerMsg = Text.empty().append(RunManager.getPrefix())
                        .append(Text.literal("Runners: ").formatted(Formatting.GRAY))
                        .append(Text.literal(String.join(", ", runnerNames))
                                .formatted(Formatting.GREEN));
                server.getPlayerManager().broadcast(runnerMsg, false);
            }

            if (!hunterNames.isEmpty()) {
                Text hunterMsg = Text.empty().append(RunManager.getPrefix())
                        .append(Text.literal("Hunters: ").formatted(Formatting.GRAY)).append(Text
                                .literal(String.join(", ", hunterNames)).formatted(Formatting.RED));
                server.getPlayerManager().broadcast(hunterMsg, false);
            }
        }

        private void playClickSound() {
            player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.MASTER, 0.5f, 1.0f);
        }

        private void playConfirmSound() {
            player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.5f, 1.0f);
        }

        private void playErrorSound() {
            player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.5f, 1.0f);
        }

        @Override
        public boolean canUse(PlayerEntity playerEntity) {
            return true;
        }
    }
}
