package net.zenzty.soullink.server.manhunt;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
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
    private static final int[] HEAD_SLOTS = {
        10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43,
        46, 47, 48, 50, 51, 52
    };

    /**
     * Opens the role selector GUI for a player.
     */
    public static void open(ServerPlayer player) {
        MinecraftServer server = RunManager.getInstance().getServer();
        if (server == null) return;

        // Reset roles when opening selector and default all to Runners
        ManhuntManager.getInstance().resetRoles();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ManhuntManager.getInstance().setRunner(p.getUUID());
        }

        SelectorInventory inventory = new SelectorInventory(server);

        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, playerEntity) -> {
                    return new SelectorScreenHandler(syncId, inventory, player);
                },
                Component.literal("Select Runners & Hunters").withStyle(ChatFormatting.DARK_GRAY)));
    }

    /** Builds styled text for GUI items (non-italic, with given formatings). */
    private static Component createItemName(String text, ChatFormatting... formattings) {
        Style style = Style.EMPTY.withItalic(false);
        for (ChatFormatting formatting : formattings) {
            style = style.applyFormat(formatting);
        }
        return Component.literal(text).setStyle(style);
    }

    /**
     * Inventory containing player heads and confirm button.
     */
    public static class SelectorInventory extends SimpleContainer {

        private final MinecraftServer server;
        private final List<UUID> playerOrder = new ArrayList<>();

        public SelectorInventory(MinecraftServer server) {
            super(INVENTORY_SIZE);
            this.server = server;
            populateItems();
        }

        public void populateItems() {
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                setItem(i, createFillerItem());
            }

            playerOrder.clear();

            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            for (int i = 0; i < players.size() && i < HEAD_SLOTS.length; i++) {
                int slot = HEAD_SLOTS[i];
                ServerPlayer p = players.get(i);
                playerOrder.add(p.getUUID());
                setItem(slot, createPlayerHead(p));
            }

            setItem(CONFIRM_SLOT, createConfirmItem());
        }

        private ItemStack createFillerItem() {
            ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
            return filler;
        }

        private ItemStack createPlayerHead(ServerPlayer player) {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(player.getGameProfile()));

            boolean isRunner = ManhuntManager.getInstance().isSpeedrunner(player.getUUID());
            String role = isRunner ? "RUNNER" : "HUNTER";
            ChatFormatting roleColor = isRunner ? ChatFormatting.GREEN : ChatFormatting.RED;

            head.set(
                    DataComponents.CUSTOM_NAME,
                    createItemName(player.getName().getString(), ChatFormatting.WHITE, ChatFormatting.BOLD));

            ItemLore lore = new ItemLore(List.of(
                    Component.literal("Role: ")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GRAY))
                            .append(Component.literal(role)
                                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(roleColor))),
                    Component.empty(),
                    isRunner
                            ? Component.literal("Shares health with other Runners")
                                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.DARK_GRAY))
                            : Component.literal("Vanilla mechanics, hunts Runners")
                                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.DARK_GRAY)),
                    Component.empty(),
                    Component.literal("Click to toggle role")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.YELLOW))));
            head.set(DataComponents.LORE, lore);

            return head;
        }

        private ItemStack createConfirmItem() {
            ItemStack item = new ItemStack(Items.EMERALD);
            ManhuntManager manager = ManhuntManager.getInstance();
            int runnerCount = manager.getRunners().size();
            int hunterCount = manager.getHunters().size();
            boolean canStart = runnerCount > 0 && hunterCount > 0;

            if (canStart) {
                item.set(
                        DataComponents.CUSTOM_NAME,
                        createItemName("✓ Start Run", ChatFormatting.GREEN, ChatFormatting.BOLD));
            } else {
                item = new ItemStack(Items.BARRIER);
                if (runnerCount == 0 && hunterCount == 0) {
                    item.set(
                            DataComponents.CUSTOM_NAME,
                            createItemName("✗ Need Runners & Hunters", ChatFormatting.RED, ChatFormatting.BOLD));
                } else if (runnerCount == 0) {
                    item.set(
                            DataComponents.CUSTOM_NAME,
                            createItemName("✗ Need at least 1 Runner", ChatFormatting.RED, ChatFormatting.BOLD));
                } else {
                    item.set(
                            DataComponents.CUSTOM_NAME,
                            createItemName("✗ Need at least 1 Hunter", ChatFormatting.RED, ChatFormatting.BOLD));
                }
            }

            ItemLore lore = new ItemLore(List.of(
                    Component.literal("Runners: ")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GRAY))
                            .append(Component.literal(String.valueOf(runnerCount))
                                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GREEN))),
                    Component.literal("Hunters: ")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GRAY))
                            .append(Component.literal(String.valueOf(hunterCount))
                                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.RED))),
                    Component.empty(),
                    canStart
                            ? Component.literal("Click to start the run!")
                                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.YELLOW))
                            : Component.literal("Need at least 1 Runner and 1 Hunter")
                                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.RED))));
            item.set(DataComponents.LORE, lore);

            return item;
        }

        public UUID getPlayerAtSlot(int slot) {
            for (int i = 0; i < HEAD_SLOTS.length && i < playerOrder.size(); i++) {
                if (HEAD_SLOTS[i] == slot) return playerOrder.get(i);
            }
            return null;
        }
    }

    private static class VirtualSlot extends Slot {
        VirtualSlot(Container inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean mayPickup(Player playerEntity) {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean isHighlightable() {
            return true;
        }
    }

    /**
     * Screen handler for the role selector GUI. Spectators can interact via
     * SpectatorInteractionMixin.
     */
    public static class SelectorScreenHandler extends AbstractContainerMenu {

        private final SelectorInventory selectorInventory;
        private final ServerPlayer player;

        public SelectorScreenHandler(int syncId, SelectorInventory inventory, ServerPlayer player) {
            super(MenuType.GENERIC_9x6, syncId);
            this.selectorInventory = inventory;
            this.player = player;
            checkContainerSize(inventory, INVENTORY_SIZE);
            inventory.startOpen(player);

            for (int i = 0; i < INVENTORY_SIZE; i++) {
                int x = 8 + (i % 9) * 18;
                int y = 18 + (i / 9) * 18;
                this.addSlot(new VirtualSlot(inventory, i, x, y));
            }

            int headerOffset = 36;
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    this.addSlot(new Slot(
                            player.getInventory(), col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + headerOffset));
                }
            }
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(player.getInventory(), col, 8 + col * 18, 161 + headerOffset));
            }
        }

        @Override
        public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
            if (slotIndex < INVENTORY_SIZE && slotIndex >= 0) {
                handleSelectorClick(slotIndex);

                setCarried(ItemStack.EMPTY);

                if (player instanceof ServerPlayer serverPlayer
                        && serverPlayer.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
                    ScreenHandlerAccessor accessor = (ScreenHandlerAccessor) this;
                    accessor.invokeUpdateToClient();
                } else {
                    broadcastChanges();
                }
                return;
            }
            super.clicked(slotIndex, buttonNum, containerInput, player);
        }

        @Override
        public ItemStack quickMoveStack(Player playerEntity, int slot) {
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
                player.sendSystemMessage(RunManager.formatMessage("Need at least one Runner to start!"));
                playErrorSound();
                return;
            }

            if (!manager.hasHunters()) {
                player.sendSystemMessage(RunManager.formatMessage("Need at least one Hunter to start!"));
                playErrorSound();
                return;
            }

            // Run everything on the server main thread to avoid crashes: slot clicks can run
            // on the network thread, while closeHandledScreen, startRun (world/chunk work) and
            // broadcast must run on the main thread.
            RunManager runManager = RunManager.getInstance();
            if (runManager == null) return;
            MinecraftServer server = runManager.getServer();
            if (server == null) return;

            server.execute(() -> {
                if (player.isRemoved()) return;
                player.closeContainer();
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
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p != null) {
                    runnerNames.add(p.getName().getString());
                }
            }

            List<String> hunterNames = new ArrayList<>();
            for (UUID uuid : manager.getHunters()) {
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p != null) {
                    hunterNames.add(p.getName().getString());
                }
            }

            if (!runnerNames.isEmpty()) {
                Component runnerMsg = Component.empty()
                        .append(RunManager.getPrefix())
                        .append(Component.literal("Runners: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.join(", ", runnerNames))
                                .withStyle(ChatFormatting.GREEN));
                server.getPlayerList().broadcastSystemMessage(runnerMsg, false);
            }

            if (!hunterNames.isEmpty()) {
                Component hunterMsg = Component.empty()
                        .append(RunManager.getPrefix())
                        .append(Component.literal("Hunters: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.join(", ", hunterNames))
                                .withStyle(ChatFormatting.RED));
                server.getPlayerList().broadcastSystemMessage(hunterMsg, false);
            }
        }

        private void playClickSound() {
            player.level()
                    .playSound(
                            null,
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            SoundEvents.UI_BUTTON_CLICK.value(),
                            SoundSource.MASTER,
                            0.5f,
                            1.0f);
        }

        private void playConfirmSound() {
            player.level()
                    .playSound(
                            null,
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            SoundEvents.PLAYER_LEVELUP,
                            SoundSource.MASTER,
                            0.5f,
                            1.0f);
        }

        private void playErrorSound() {
            player.level()
                    .playSound(
                            null,
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            SoundEvents.VILLAGER_NO,
                            SoundSource.MASTER,
                            0.5f,
                            1.0f);
        }

        @Override
        public boolean stillValid(Player playerEntity) {
            return true;
        }
    }
}
