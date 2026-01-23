package net.zenzty.soullink.server.settings;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
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
        public static void open(ServerPlayerEntity player) {
                Settings settings = Settings.getInstance();
                boolean currentDamageLog = settings.isDamageLogEnabled();

                // Create inventory with all slots
                InfoSettingsInventory inventory = new InfoSettingsInventory(currentDamageLog);

                // Open the screen
                player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                                (syncId, playerInventory, playerEntity) -> {
                                        return new InfoSettingsScreenHandler(syncId, inventory, player);
                                }, Text.literal("Soul Link Settings")
                                                .formatted(Formatting.DARK_GRAY)));
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
         * Virtual inventory that tracks pending settings changes.
         */
        public static class InfoSettingsInventory extends SimpleInventory {

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
                        filler.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
                        for (int i = 0; i < INVENTORY_SIZE; i++) {
                                setStack(i, filler.copy());
                        }

                        // Add combat log setting
                        setStack(COMBAT_LOG_SLOT, createCombatLogItem());

                        // Add bug report button
                        setStack(BUG_REPORT_SLOT, createBugReportItem());

                        // Add commands list button
                        setStack(COMMANDS_SLOT, createCommandsItem());

                        // Add close button
                        setStack(CLOSE_SLOT, createCloseItem());
                }

                private ItemStack createCombatLogItem() {
                        ItemStack item = new ItemStack(
                                        pendingDamageLog ? Items.WRITABLE_BOOK : Items.BOOK);
                        item.set(DataComponentTypes.CUSTOM_NAME, createItemName("Combat Log",
                                        Formatting.RED, Formatting.BOLD));

                        LoreComponent lore = new LoreComponent(List.of(
                                        Text.literal("Status: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                                        .append(pendingDamageLog ? Text
                                                                        .literal("ENABLED")
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .withFormatting(Formatting.GREEN))
                                                                        : Text.literal("DISABLED")
                                                                                        .setStyle(Style.EMPTY
                                                                                                        .withItalic(false)
                                                                                                        .withFormatting(Formatting.RED))),
                                        Text.empty(),
                                        Text.literal("Shows damage notifications in chat")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY)),
                                        Text.literal("when players take damage.")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY)),
                                        Text.empty(),
                                        Text.literal("Click to toggle").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.DARK_GRAY))));
                        item.set(DataComponentTypes.LORE, lore);

                        return item;
                }

                private ItemStack createBugReportItem() {
                        ItemStack item = new ItemStack(Items.KNOWLEDGE_BOOK);
                        item.set(DataComponentTypes.CUSTOM_NAME, createItemName("Bug Report",
                                        Formatting.AQUA, Formatting.BOLD));

                        LoreComponent lore = new LoreComponent(List.of(Text
                                        .literal("Found a bug? Let us know!")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .withFormatting(Formatting.DARK_GRAY)),
                                        Text.literal("Join our Discord to report it.")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY)),
                                        Text.empty(),
                                        Text.literal("Click to get the invite link in chat.")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY))));
                        item.set(DataComponentTypes.LORE, lore);

                        return item;
                }

                private ItemStack createCommandsItem() {
                        ItemStack item = new ItemStack(Items.COMMAND_BLOCK);
                        item.set(DataComponentTypes.CUSTOM_NAME, createItemName("Available Commands",
                                        Formatting.GOLD, Formatting.BOLD));

                        List<Text> loreLines = new ArrayList<>();
                        loreLines.add(Text.literal("Available Commands:").setStyle(Style.EMPTY
                                        .withItalic(false).withFormatting(Formatting.WHITE)));
                        loreLines.add(Text.empty());
                        loreLines.add(Text.literal("  /start").setStyle(Style.EMPTY
                                        .withItalic(false).withFormatting(Formatting.GREEN))
                                        .append(Text.literal(" - Start a new run").setStyle(
                                                        Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.GRAY))));
                        loreLines.add(Text.literal("  /chaos").setStyle(Style.EMPTY
                                        .withItalic(false).withFormatting(Formatting.GREEN))
                                        .append(Text.literal(" - Open chaos settings").setStyle(
                                                        Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.GRAY))));
                        loreLines.add(Text.literal("  /settings").setStyle(Style.EMPTY
                                        .withItalic(false).withFormatting(Formatting.GREEN))
                                        .append(Text.literal(" - Open info settings").setStyle(
                                                        Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.GRAY))));
                        loreLines.add(Text.literal("  /runinfo").setStyle(Style.EMPTY
                                        .withItalic(false).withFormatting(Formatting.GREEN))
                                        .append(Text.literal(" - Display run info").setStyle(
                                                        Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.GRAY))));
                        loreLines.add(Text.literal("  /reset").setStyle(Style.EMPTY
                                        .withItalic(false).withFormatting(Formatting.GREEN))
                                        .append(Text.literal(" - Reset current run").setStyle(
                                                        Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.GRAY))));
                        loreLines.add(Text.literal("  /stoprun").setStyle(Style.EMPTY
                                        .withItalic(false).withFormatting(Formatting.RED))
                                        .append(Text.literal(" - Stop run (Admin)").setStyle(
                                                        Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.GRAY))));

                        LoreComponent lore = new LoreComponent(loreLines);
                        item.set(DataComponentTypes.LORE, lore);

                        return item;
                }

                private ItemStack createCloseItem() {
                        ItemStack item = new ItemStack(Items.EMERALD);
                        item.set(DataComponentTypes.CUSTOM_NAME, createItemName("Save settings",
                                        Formatting.GREEN, Formatting.BOLD));

                        LoreComponent lore = new LoreComponent(List.of(
                                        Text.literal("Click to save and close this menu.")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY))));
                        item.set(DataComponentTypes.LORE, lore);

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
         * Virtual screen handler for the info settings GUI.
         */
        public static class InfoSettingsScreenHandler extends GenericContainerScreenHandler {

                private final InfoSettingsInventory settingsInventory;
                private final ServerPlayerEntity player;
                private boolean confirmed = false;

                public InfoSettingsScreenHandler(int syncId, InfoSettingsInventory inventory,
                                ServerPlayerEntity player) {
                        super(ScreenHandlerType.GENERIC_9X3, syncId, player.getInventory(),
                                        inventory, 3);
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
                public void onSlotClick(int slotIndex, int button, SlotActionType actionType,
                                net.minecraft.entity.player.PlayerEntity clickingPlayer) {
                        if (slotIndex < INVENTORY_SIZE && slotIndex >= 0) {
                                handleSettingsClick(slotIndex);
                                setCursorStack(net.minecraft.item.ItemStack.EMPTY);

                                if (clickingPlayer instanceof ServerPlayerEntity serverPlayer
                                                && serverPlayer.interactionManager
                                                                .getGameMode() == GameMode.SPECTATOR) {
                                        ScreenHandlerAccessor accessor =
                                                        (ScreenHandlerAccessor) this;
                                        accessor.invokeUpdateToClient();
                                } else {
                                        sendContentUpdates();
                                }

                                return;
                        }

                        super.onSlotClick(slotIndex, button, actionType, clickingPlayer);
                }

                @Override
                public ItemStack quickMove(net.minecraft.entity.player.PlayerEntity playerEntity,
                                int slot) {
                        return ItemStack.EMPTY;
                }

                @Override
                public boolean canInsertIntoSlot(ItemStack stack,
                                net.minecraft.screen.slot.Slot slot) {
                        if (slot.inventory == settingsInventory) {
                                return false;
                        }
                        return super.canInsertIntoSlot(stack, slot);
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
                                        player.sendMessage(RunManager.formatMessage(
                                                        "Report bugs and get support in our Discord:"),
                                                        false);
                                        player.sendMessage(Text.literal(discordUrl)
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_AQUA,
                                                                                        Formatting.UNDERLINE)
                                                                        .withClickEvent(new ClickEvent.OpenUrl(
                                                                                        URI.create(discordUrl)))),
                                                        false);
                                        playClickSound();
                                }
                                case COMMANDS_SLOT -> {
                                        // Send commands list to chat
                                        player.sendMessage(RunManager.formatMessage(
                                                        "Available Commands:"), false);
                                        player.sendMessage(Text.literal("/start")
                                                        .formatted(Formatting.GREEN)
                                                        .append(Text.literal(" - Start a new run")
                                                                        .formatted(Formatting.GRAY)),
                                                        false);
                                        player.sendMessage(Text.literal("/chaos")
                                                        .formatted(Formatting.GREEN)
                                                        .append(Text.literal(" - Open chaos settings")
                                                                        .formatted(Formatting.GRAY)),
                                                        false);
                                        player.sendMessage(Text.literal("/settings")
                                                        .formatted(Formatting.GREEN)
                                                        .append(Text.literal(" - Open info settings")
                                                                        .formatted(Formatting.GRAY)),
                                                        false);
                                        player.sendMessage(Text.literal("/runinfo")
                                                        .formatted(Formatting.GREEN)
                                                        .append(Text.literal(" - Display run info")
                                                                        .formatted(Formatting.GRAY)),
                                                        false);
                                        player.sendMessage(Text.literal("/reset")
                                                        .formatted(Formatting.GREEN)
                                                        .append(Text.literal(" - Reset current run")
                                                                        .formatted(Formatting.GRAY)),
                                                        false);
                                        player.sendMessage(Text.literal("/stoprun")
                                                        .formatted(Formatting.RED)
                                                        .append(Text.literal(" - Stop run (Admin)")
                                                                        .formatted(Formatting.GRAY)),
                                                        false);
                                        playClickSound();
                                }
                                case CLOSE_SLOT -> {
                                        if (settingsInventory.hasChanges()) {
                                                // Apply the changes immediately (damage log can be toggled anytime)
                                                Settings.getInstance()
                                                                .setDamageLogEnabled(settingsInventory
                                                                                .isPendingDamageLog());
                                                confirmed = true;

                                                player.sendMessage(RunManager.formatMessage(
                                                                "Combat log " + (settingsInventory
                                                                                .isPendingDamageLog()
                                                                                                ? "enabled"
                                                                                                : "disabled")
                                                                                + "."),
                                                                false);

                                                player.closeHandledScreen();
                                                playConfirmSound();
                                        } else {
                                                player.closeHandledScreen();
                                        }
                                }
                        }
                }

                private void playClickSound() {
                        player.getEntityWorld().playSound(null, player.getX(), player.getY(),
                                        player.getZ(),
                                        net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(),
                                        net.minecraft.sound.SoundCategory.MASTER, 0.5f, 1.0f);
                }

                private void playConfirmSound() {
                        player.getEntityWorld().playSound(null, player.getX(), player.getY(),
                                        player.getZ(),
                                        net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP,
                                        net.minecraft.sound.SoundCategory.MASTER, 0.5f, 1.0f);
                }

                @Override
                public void onClosed(net.minecraft.entity.player.PlayerEntity closingPlayer) {
                        super.onClosed(closingPlayer);

                        if (!confirmed && settingsInventory.hasChanges()) {
                                player.sendMessage(
                                                RunManager.formatMessage(
                                                                "Settings changes discarded."),
                                                false);
                        }
                }

                @Override
                public boolean canUse(net.minecraft.entity.player.PlayerEntity playerEntity) {
                        return true;
                }
        }
}
