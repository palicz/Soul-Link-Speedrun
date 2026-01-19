package net.zenzty.soullink.server.manhunt;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
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
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import net.zenzty.soullink.mixin.ui.ScreenHandlerAccessor;
import net.zenzty.soullink.server.run.RunManager;

/**
 * GUI for selecting Runners and Hunters before starting a Manhunt run. Shows player heads that can
 * be clicked to toggle roles.
 */
public class SpeedrunnerSelectorGui {

    private static final int INVENTORY_SIZE = 54;
    private static final int CONFIRM_SLOT = 49; // Bottom center

    /**
     * Opens the role selector GUI for a player.
     */
    public static void open(ServerPlayerEntity player) {
        MinecraftServer server = RunManager.getInstance().getServer();
        if (server == null)
            return;

        // Reset roles when opening selector
        ManhuntManager.getInstance().resetRoles();

        // Default all players to Runners
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ManhuntManager.getInstance().setRunner(p.getUuid());
        }

        SelectorInventory inventory = new SelectorInventory(server);

        player.openHandledScreen(
                new SimpleNamedScreenHandlerFactory((syncId, playerInventory, playerEntity) -> {
                    return new SelectorScreenHandler(syncId, inventory, player);
                }, Text.literal("Select Runners & Hunters").formatted(Formatting.DARK_GRAY)));
    }

    /**
     * Creates a non-italic text for item names.
     */
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
            // Clear existing items
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                setStack(i, createFillerItem());
            }

            playerOrder.clear();

            // Add player heads
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            int slot = 10; // Start at row 2
            int col = 0;

            for (ServerPlayerEntity player : players) {
                if (slot >= 44)
                    break; // Don't overflow

                // Skip slots that would be in the edges
                if (col >= 7) {
                    col = 0;
                    slot = ((slot / 9) + 1) * 9 + 1; // Next row, second column
                }

                if (slot >= 44)
                    break;

                playerOrder.add(player.getUuid());
                setStack(slot, createPlayerHead(player));
                slot++;
                col++;
            }

            // Add confirm button
            setStack(CONFIRM_SLOT, createConfirmItem());
        }

        private ItemStack createFillerItem() {
            ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            filler.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
            return filler;
        }

        private ItemStack createPlayerHead(ServerPlayerEntity player) {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);

            // Set the skin using ProfileComponent
            ProfileComponent profile = ProfileComponent.ofStatic(player.getGameProfile());
            head.set(DataComponentTypes.PROFILE, profile);

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

        public List<UUID> getPlayerOrder() {
            return playerOrder;
        }

        public UUID getPlayerAtSlot(int slot) {
            int baseSlot = 10;
            int col = 0;
            int currentSlot = baseSlot;

            for (UUID uuid : playerOrder) {
                if (col >= 7) {
                    col = 0;
                    currentSlot = ((currentSlot / 9) + 1) * 9 + 1;
                }

                if (currentSlot == slot) {
                    return uuid;
                }

                currentSlot++;
                col++;
            }

            return null;
        }
    }

    /**
     * Virtual slot that prevents item interactions.
     */
    private static class VirtualSlot extends Slot {
        public VirtualSlot(net.minecraft.inventory.Inventory inventory, int index, int x, int y) {
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
     * Screen handler for the role selector GUI.
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

            // Add Virtual Slots (Container)
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                int x = 8 + (i % 9) * 18;
                int y = 18 + (i / 9) * 18;
                this.addSlot(new VirtualSlot(inventory, i, x, y));
            }

            // Add Player Inventory Slots
            // Generic 9x6 offset: 36 (rows-4)*18
            int headerOffset = 36;
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    this.addSlot(new Slot(player.getInventory(), col + row * 9 + 9, 8 + col * 18,
                            103 + row * 18 + headerOffset));
                }
            }

            // Add Hotbar Slots
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

                // Force sync for spectators
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

            // Check if clicking a player head
            UUID clickedPlayer = selectorInventory.getPlayerAtSlot(slotIndex);
            if (clickedPlayer != null) {
                ManhuntManager.getInstance().toggleRole(clickedPlayer);
                selectorInventory.populateItems();
                playClickSound();
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

            // Close GUI
            player.closeHandledScreen();
            playConfirmSound();

            // Start the run - MUST use server.execute() to ensure proper thread context
            // GUI click handlers run in packet processing context which causes deadlock if
            // we call startRun directly (it tries to load chunks from the wrong context)
            RunManager runManager = RunManager.getInstance();
            if (runManager != null) {
                MinecraftServer server = runManager.getServer();
                if (server != null) {
                    server.execute(() -> {
                        // Broadcast role assignments
                        broadcastRoleAssignments();

                        // Start the actual run - teams will be assigned after spawn is found
                        // in RunManager.transitionToRunning()
                        runManager.startRun();
                    });
                }
            }
        }

        private void broadcastRoleAssignments() {
            MinecraftServer server = RunManager.getInstance().getServer();
            if (server == null)
                return;

            ManhuntManager manager = ManhuntManager.getInstance();

            // Build runner names
            List<String> runnerNames = new ArrayList<>();
            for (UUID uuid : manager.getRunners()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null) {
                    runnerNames.add(p.getName().getString());
                }
            }

            // Build hunter names
            List<String> hunterNames = new ArrayList<>();
            for (UUID uuid : manager.getHunters()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null) {
                    hunterNames.add(p.getName().getString());
                }
            }

            // Broadcast
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
            player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(),
                    net.minecraft.sound.SoundCategory.MASTER, 0.5f, 1.0f);
        }

        private void playConfirmSound() {
            player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP,
                    net.minecraft.sound.SoundCategory.MASTER, 0.5f, 1.0f);
        }

        private void playErrorSound() {
            player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO,
                    net.minecraft.sound.SoundCategory.MASTER, 0.5f, 1.0f);
        }

        @Override
        public boolean canUse(PlayerEntity playerEntity) {
            return true;
        }
    }
}
