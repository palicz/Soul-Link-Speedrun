package net.zenzty.soullink.server.settings;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.zenzty.soullink.mixin.ui.ScreenHandlerAccessor;
import net.zenzty.soullink.server.run.RunManager;

/**
 * Handles the info settings GUI for the Soul Link mod. Contains combat log toggle, bug report, and
 * available commands. Uses a virtual single chest (27 slots) to display options.
 */
public class SettingsInfoGui {

    // Slot positions in the GUI (single chest: 9x3 = 27 slots)
    private static final int COMBAT_LOG_SLOT = 10;
    private static final int BUG_REPORT_SLOT = 13;
    private static final int COMMANDS_SLOT = 16;
    private static final int CLOSE_SLOT = 22; // Center of bottom row

    // Size of single chest
    private static final int INVENTORY_SIZE = 27;

    /**
     * Opens the info settings GUI for a player.
     */
    public static void open(ServerPlayer player) {
        Settings settings = Settings.getInstance();
        boolean currentDamageLog = settings.isDamageLogEnabled();

        // Create inventory with all slots
        InfoSettingsInventory inventory = new InfoSettingsInventory(currentDamageLog);

        // Open the screen
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, playerEntity) -> {
                    return new InfoSettingsScreenHandler(syncId, inventory, player);
                },
                Component.literal("Soul Link Settings").withStyle(ChatFormatting.DARK_GRAY)));
    }

    /**
     * Creates a non-italic text for item names.
     */
    private static Component createItemName(String text, ChatFormatting... formattings) {
        Style style = Style.EMPTY.withItalic(false);
        for (ChatFormatting formatting : formattings) {
            style = style.applyFormat(formatting);
        }
        return Component.literal(text).setStyle(style);
    }

    /**
     * Virtual inventory that tracks pending settings changes.
     */
    public static class InfoSettingsInventory extends SimpleContainer {

        private boolean pendingDamageLog;
        private final boolean originalDamageLog;

        public InfoSettingsInventory(boolean originalDamageLog) {
            super(INVENTORY_SIZE);
            this.originalDamageLog = originalDamageLog;
            this.pendingDamageLog = originalDamageLog;

            populateItems();
        }

        /**
         * Populates the inventory with setting items.
         */
        public void populateItems() {
            // Fill with gray stained glass panes as background
            ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                setItem(i, filler.copy());
            }

            // Add combat log setting
            setItem(COMBAT_LOG_SLOT, createCombatLogItem());

            // Add bug report button
            setItem(BUG_REPORT_SLOT, createBugReportItem());

            // Add commands list button
            setItem(COMMANDS_SLOT, createCommandsItem());

            // Add close button
            setItem(CLOSE_SLOT, createCloseItem());
        }

        private ItemStack createCombatLogItem() {
            ItemStack item = new ItemStack(pendingDamageLog ? Items.WRITABLE_BOOK : Items.BOOK);
            item.set(DataComponents.CUSTOM_NAME, createItemName("Combat Log", ChatFormatting.RED, ChatFormatting.BOLD));

            ItemLore lore = new ItemLore(List.of(
                    Component.literal("Status: ")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GRAY))
                            .append(
                                    pendingDamageLog
                                            ? Component.literal("ENABLED")
                                                    .setStyle(Style.EMPTY
                                                            .withItalic(false)
                                                            .applyFormat(ChatFormatting.GREEN))
                                            : Component.literal("DISABLED")
                                                    .setStyle(Style.EMPTY
                                                            .withItalic(false)
                                                            .applyFormat(ChatFormatting.RED))),
                    Component.empty(),
                    Component.literal("Shows damage notifications in chat")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.DARK_GRAY)),
                    Component.literal("when players take damage.")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.DARK_GRAY)),
                    Component.empty(),
                    Component.literal("Click to toggle")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.DARK_GRAY))));
            item.set(DataComponents.LORE, lore);

            return item;
        }

        private ItemStack createBugReportItem() {
            ItemStack item = new ItemStack(Items.KNOWLEDGE_BOOK);
            item.set(
                    DataComponents.CUSTOM_NAME, createItemName("Bug Report", ChatFormatting.AQUA, ChatFormatting.BOLD));

            ItemLore lore = new ItemLore(List.of(
                    Component.literal("Found a bug? Let us know!")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.DARK_GRAY)),
                    Component.literal("Join our Discord to report it.")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.DARK_GRAY)),
                    Component.empty(),
                    Component.literal("Click to get the invite link in chat.")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.DARK_GRAY))));
            item.set(DataComponents.LORE, lore);

            return item;
        }

        private ItemStack createCommandsItem() {
            ItemStack item = new ItemStack(Items.COMMAND_BLOCK);
            item.set(
                    DataComponents.CUSTOM_NAME,
                    createItemName("Available Commands", ChatFormatting.GOLD, ChatFormatting.BOLD));

            List<Component> loreLines = new ArrayList<>();
            loreLines.add(Component.literal("Available Commands:")
                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.WHITE)));
            loreLines.add(Component.empty());
            loreLines.add(Component.literal("  /start")
                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GREEN))
                    .append(Component.literal(" - Start a new run")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GRAY))));
            loreLines.add(Component.literal("  /chaos")
                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GREEN))
                    .append(Component.literal(" - Open chaos settings")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GRAY))));
            loreLines.add(Component.literal("  /settings")
                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GREEN))
                    .append(Component.literal(" - Open info settings")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GRAY))));
            loreLines.add(Component.literal("  /runinfo")
                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GREEN))
                    .append(Component.literal(" - Display run info")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GRAY))));
            loreLines.add(Component.literal("  /reset")
                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GREEN))
                    .append(Component.literal(" - Reset current run")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GRAY))));
            loreLines.add(Component.literal("  /stoprun")
                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.RED))
                    .append(Component.literal(" - Stop run (Admin)")
                            .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.GRAY))));

            ItemLore lore = new ItemLore(loreLines);
            item.set(DataComponents.LORE, lore);

            return item;
        }

        private ItemStack createCloseItem() {
            ItemStack item = new ItemStack(Items.EMERALD);
            item.set(
                    DataComponents.CUSTOM_NAME,
                    createItemName("Save settings", ChatFormatting.GREEN, ChatFormatting.BOLD));

            ItemLore lore = new ItemLore(List.of(Component.literal("Click to save and close this menu.")
                    .setStyle(Style.EMPTY.withItalic(false).applyFormat(ChatFormatting.DARK_GRAY))));
            item.set(DataComponents.LORE, lore);

            return item;
        }

        public boolean hasChanges() {
            return pendingDamageLog != originalDamageLog;
        }

        public boolean isPendingDamageLog() {
            return pendingDamageLog;
        }

        public void toggleDamageLog() {
            pendingDamageLog = !pendingDamageLog;
        }
    }

    /**
     * Virtual slot that prevents all item interactions.
     */
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
     * Virtual screen handler for the info settings GUI.
     */
    public static class InfoSettingsScreenHandler extends ChestMenu {

        private final InfoSettingsInventory settingsInventory;
        private final ServerPlayer player;
        private boolean confirmed = false;

        public InfoSettingsScreenHandler(int syncId, InfoSettingsInventory inventory, ServerPlayer player) {
            super(MenuType.GENERIC_9x3, syncId, player.getInventory(), inventory, 3);
            this.settingsInventory = inventory;
            this.player = player;

            // Replace inventory slots (0-53) with virtual slots
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                Slot oldSlot = this.slots.get(i);
                Slot newSlot = new VirtualSlot(inventory, i, oldSlot.x, oldSlot.y);
                this.slots.set(i, newSlot);
            }
        }

        @Override
        public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
            if (slotIndex < INVENTORY_SIZE && slotIndex >= 0) {
                handleSettingsClick(slotIndex);
                setCarried(net.minecraft.world.item.ItemStack.EMPTY);

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
        public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player playerEntity, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canTakeItemForPickAll(ItemStack stack, net.minecraft.world.inventory.Slot slot) {
            if (slot.container == settingsInventory) {
                return false;
            }
            return super.canTakeItemForPickAll(stack, slot);
        }

        private void handleSettingsClick(int slotIndex) {
            switch (slotIndex) {
                case COMBAT_LOG_SLOT -> {
                    settingsInventory.toggleDamageLog();
                    settingsInventory.populateItems();
                    playClickSound();
                }
                case BUG_REPORT_SLOT -> {
                    final String discordUrl = "https://discord.gg/7KkZP2r62H";
                    player.sendSystemMessage(RunManager.formatMessage("Report bugs and get support in our Discord:"));
                    player.sendSystemMessage(Component.literal(discordUrl)
                            .setStyle(Style.EMPTY
                                    .withItalic(false)
                                    .applyFormats(ChatFormatting.DARK_AQUA, ChatFormatting.UNDERLINE)
                                    .withClickEvent(new ClickEvent.OpenUrl(URI.create(discordUrl)))));
                    playClickSound();
                }
                case COMMANDS_SLOT -> {
                    // Send commands list to chat
                    player.sendSystemMessage(RunManager.formatMessage("Available Commands:"));
                    player.sendSystemMessage(Component.literal("/start")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(" - Start a new run").withStyle(ChatFormatting.GRAY)));
                    player.sendSystemMessage(Component.literal("/chaos")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(" - Open chaos settings").withStyle(ChatFormatting.GRAY)));
                    player.sendSystemMessage(Component.literal("/settings")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(" - Open info settings").withStyle(ChatFormatting.GRAY)));
                    player.sendSystemMessage(Component.literal("/runinfo")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(" - Display run info").withStyle(ChatFormatting.GRAY)));
                    player.sendSystemMessage(Component.literal("/reset")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(" - Reset current run").withStyle(ChatFormatting.GRAY)));
                    player.sendSystemMessage(Component.literal("/stoprun")
                            .withStyle(ChatFormatting.RED)
                            .append(Component.literal(" - Stop run (Admin)").withStyle(ChatFormatting.GRAY)));
                    playClickSound();
                }
                case CLOSE_SLOT -> {
                    if (settingsInventory.hasChanges()) {
                        // Apply the changes immediately (damage log can be
                        // toggled anytime)
                        Settings.getInstance().setDamageLogEnabled(settingsInventory.isPendingDamageLog());
                        confirmed = true;
                        MinecraftServer server = RunManager.getInstance().getServer();
                        if (server != null) {
                            SettingsPersistence.save(server);
                        }

                        player.sendSystemMessage(RunManager.formatMessage("Combat log "
                                + (settingsInventory.isPendingDamageLog() ? "enabled" : "disabled") + "."));

                        player.closeContainer();
                        playConfirmSound();
                    } else {
                        player.closeContainer();
                    }
                }
            }
        }

        private void playClickSound() {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK,
                    net.minecraft.sounds.SoundSource.MASTER,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    0.5f,
                    1.0f,
                    player.getRandom().nextLong()));
        }

        private void playConfirmSound() {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value()),
                    net.minecraft.sounds.SoundSource.MASTER,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    0.5f,
                    1.0f,
                    player.getRandom().nextLong()));
        }

        @Override
        public void removed(net.minecraft.world.entity.player.Player closingPlayer) {
            super.removed(closingPlayer);

            if (!confirmed && settingsInventory.hasChanges()) {
                player.sendSystemMessage(RunManager.formatMessage("Settings changes discarded."));
            }
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player playerEntity) {
            return true;
        }
    }
}
