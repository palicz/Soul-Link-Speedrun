package net.zenzty.soullink.server.settings;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.zenzty.soullink.mixin.ui.ScreenHandlerAccessor;
import net.zenzty.soullink.server.run.RunManager;

/**
 * Handles the virtual settings GUI for the Soul Link mod. Uses a virtual double chest (54 slots) to
 * display settings options. This is a server-side only GUI that doesn't represent any real
 * container in the world.
 */
public class SettingsGui {

        // Slot positions: one column empty between columns, one row empty between rows.
        // Row 1: 10, 12, 14, 16 | Row 2: empty | Row 3: 28 (Manhunt) | Row 5: 49 (confirm)
        private static final int DIFFICULTY_SLOT = 10;
        private static final int HALF_HEART_SLOT = 12;
        private static final int SHARED_POTIONS_SLOT = 14;
        private static final int SHARED_JUMPING_SLOT = 16;
        private static final int MANHUNT_SLOT = 28; // Row 3, col 1 (one row below empty row 2)
        private static final int SYNCED_INVENTORY_SLOT = 30; // Row 3, next to Manhunt
        private static final int CONFIRM_SLOT = 49; // Bottom center

        // Size of double chest
        private static final int INVENTORY_SIZE = 54;

        /**
         * Opens the settings GUI for a player. Note: Spectators can view but cannot interact due to
         * Minecraft client limitations.
         */
        public static void open(ServerPlayer player) {
                Settings settings = Settings.getInstance();
                // Pre-fill from pending if it exists (so the GUI shows queued changes, not the
                // in-memory values for the current run). Otherwise use current settings and world
                // difficulty.
                Settings.SettingsSnapshot originalSnapshot;
                Settings.SettingsSnapshot pending = settings.getPendingSnapshotOrNull();
                if (pending != null) {
                        originalSnapshot = pending;
                } else {
                        Difficulty worldDifficulty = player.level().getDifficulty();
                        if (worldDifficulty == Difficulty.PEACEFUL) {
                                worldDifficulty = Difficulty.EASY;
                        }
                        originalSnapshot = new Settings.SettingsSnapshot(worldDifficulty,
                                        settings.isHalfHeartMode(), settings.isSharedPotions(),
                                        settings.isSharedJumping(), settings.isManhuntMode(),
                                        settings.isSyncedInventory());
                }

                // Create inventory with all slots
                SettingsInventory inventory = new SettingsInventory(originalSnapshot);

                // Open the screen
                player.openMenu(new SimpleMenuProvider(
                                (syncId, playerInventory, playerEntity) -> {
                                        return new SettingsScreenHandler(syncId, inventory, player);
                                }, Component.literal("Soul Link Chaos Modes")
                                                .withStyle(ChatFormatting.DARK_GRAY)));
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
         * Virtual inventory that tracks pending settings changes. This is a server-side only
         * inventory that doesn't represent any real container in the world.
         */
        public static class SettingsInventory extends SimpleContainer {

                private Difficulty pendingDifficulty;
                private boolean pendingHalfHeart;
                private boolean pendingSharedPotions;
                private boolean pendingSharedJumping;
                private boolean pendingManhunt;
                private boolean pendingSyncedInventory;
                private final Settings.SettingsSnapshot original;

                public SettingsInventory(Settings.SettingsSnapshot original) {
                        super(INVENTORY_SIZE);
                        this.original = original;
                        this.pendingDifficulty = original.difficulty();
                        this.pendingHalfHeart = original.halfHeartMode();
                        this.pendingSharedPotions = original.sharedPotions();
                        this.pendingSharedJumping = original.sharedJumping();
                        this.pendingManhunt = original.manhuntMode();
                        this.pendingSyncedInventory = original.syncedInventory();

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

                        // Add difficulty setting
                        setItem(DIFFICULTY_SLOT, createDifficultyItem());

                        // Add half heart setting
                        setItem(HALF_HEART_SLOT, createHalfHeartItem());

                        // Add shared potions setting
                        setItem(SHARED_POTIONS_SLOT, createSharedPotionsItem());

                        // Add shared jumping setting
                        setItem(SHARED_JUMPING_SLOT, createSharedJumpingItem());

                        // Add manhunt mode setting
                        setItem(MANHUNT_SLOT, createManhuntItem());

                        // Add synced inventory setting
                        setItem(SYNCED_INVENTORY_SLOT, createSyncedInventoryItem());

                        // Add confirm button
                        setItem(CONFIRM_SLOT, createConfirmItem());
                }

                private ItemStack createDifficultyItem() {
                        ItemStack item = new ItemStack(switch (pendingDifficulty) {
                                case EASY -> Items.WOODEN_SWORD;
                                case NORMAL -> Items.IRON_SWORD;
                                case HARD -> Items.DIAMOND_SWORD;
                                default -> Items.WOODEN_SWORD;
                        });
                        item.set(DataComponents.CUSTOM_NAME, createItemName("Difficulty",
                                        ChatFormatting.RED, ChatFormatting.BOLD));

                        String difficultyName = switch (pendingDifficulty) {
                                case PEACEFUL -> "Peaceful";
                                case EASY -> "Easy";
                                case NORMAL -> "Normal";
                                case HARD -> "Hard";
                        };

                        ChatFormatting difficultyColor = switch (pendingDifficulty) {
                                case EASY -> ChatFormatting.GREEN;
                                case NORMAL -> ChatFormatting.YELLOW;
                                case HARD -> ChatFormatting.RED;
                                default -> ChatFormatting.WHITE;
                        };

                        ItemLore difficultyLore = new ItemLore(List.of(Component
                                        .literal("Change to: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                        .append(Component.literal(difficultyName).setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(difficultyColor))),
                                        Component.empty(),
                                        Component.literal("Current: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                                        .append(Component.literal(getDifficultyName(
                                                                        original.difficulty()))
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .applyFormat(ChatFormatting.WHITE))),
                                        Component.empty(),
                                        Component.literal("Click to cycle difficulty")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.DARK_GRAY))));
                        item.set(DataComponents.LORE, difficultyLore);

                        return item;
                }

                private ItemStack createHalfHeartItem() {
                        ItemStack item = new ItemStack(
                                        pendingHalfHeart ? Items.GOLDEN_APPLE : Items.APPLE);
                        item.set(DataComponents.CUSTOM_NAME, createItemName("Half Hearted Mode",
                                        ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));

                        ItemLore halfHeartLore = new ItemLore(List.of(
                                        Component.literal("Status: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                                        .append(pendingHalfHeart ? Component
                                                                        .literal("ENABLED")
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .applyFormat(ChatFormatting.GREEN))
                                                                        : Component.literal("DISABLED")
                                                                                        .setStyle(Style.EMPTY
                                                                                                        .withItalic(false)
                                                                                                        .applyFormat(ChatFormatting.RED))),
                                        Component.empty(),
                                        Component.literal("Players have only 1 health point!")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.DARK_GRAY)),
                                        Component.empty(),
                                        Component.literal("Click to toggle").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.DARK_GRAY))));
                        item.set(DataComponents.LORE, halfHeartLore);

                        return item;
                }

                private ItemStack createSharedPotionsItem() {
                        ItemStack item = new ItemStack(pendingSharedPotions ? Items.DRAGON_BREATH
                                        : Items.GLASS_BOTTLE);

                        item.set(DataComponents.CUSTOM_NAME, createItemName(
                                        "Shared Potions Mode", ChatFormatting.BLUE, ChatFormatting.BOLD));

                        ItemLore sharedPotionsLore = new ItemLore(List.of(
                                        Component.literal("Status: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                                        .append(pendingSharedPotions ? Component
                                                                        .literal("ENABLED")
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .applyFormat(ChatFormatting.GREEN))
                                                                        : Component.literal("DISABLED")
                                                                                        .setStyle(Style.EMPTY
                                                                                                        .withItalic(false)
                                                                                                        .applyFormat(ChatFormatting.RED))),
                                        Component.empty(),
                                        Component.literal("Potion effects are shared between")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.DARK_GRAY)),
                                        Component.literal("all players.").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.DARK_GRAY)),
                                        Component.empty(),
                                        Component.literal("Click to toggle").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.DARK_GRAY))));
                        item.set(DataComponents.LORE, sharedPotionsLore);

                        return item;
                }

                private ItemStack createSharedJumpingItem() {
                        ItemStack item = new ItemStack(
                                        pendingSharedJumping ? Items.RABBIT_FOOT : Items.FEATHER);
                        item.set(DataComponents.CUSTOM_NAME, createItemName(
                                        "Shared Jumping Mode", ChatFormatting.YELLOW, ChatFormatting.BOLD));
                        ItemLore sharedJumpingLore = new ItemLore(List.of(
                                        Component.literal("Status: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                                        .append(pendingSharedJumping ? Component
                                                                        .literal("ENABLED")
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .applyFormat(ChatFormatting.GREEN))
                                                                        : Component.literal("DISABLED")
                                                                                        .setStyle(Style.EMPTY
                                                                                                        .withItalic(false)
                                                                                                        .applyFormat(ChatFormatting.RED))),
                                        Component.empty(),
                                        Component.literal("If one player jumps, all players jump.")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.DARK_GRAY)),
                                        Component.empty(),
                                        Component.literal("Click to toggle").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.DARK_GRAY))));
                        item.set(DataComponents.LORE, sharedJumpingLore);

                        return item;
                }

                private ItemStack createManhuntItem() {
                        ItemStack item = new ItemStack(
                                        pendingManhunt ? Items.COMPASS : Items.ENDER_EYE);
                        item.set(DataComponents.CUSTOM_NAME, createItemName("Manhunt Mode",
                                        ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
                        ItemLore manhuntLore = new ItemLore(List.of(
                                        Component.literal("Status: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                                        .append(pendingManhunt ? Component
                                                                        .literal("ENABLED")
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .applyFormat(ChatFormatting.GREEN))
                                                                        : Component.literal("DISABLED")
                                                                                        .setStyle(Style.EMPTY
                                                                                                        .withItalic(false)
                                                                                                        .applyFormat(ChatFormatting.RED))),
                                        Component.empty(),
                                        Component.literal("Runners share health; Hunters hunt.")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.DARK_GRAY)),
                                        Component.literal("30s head start, hunter respawns.")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.DARK_GRAY)),
                                        Component.empty(),
                                        Component.literal("Click to toggle").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.DARK_GRAY))));
                        item.set(DataComponents.LORE, manhuntLore);

                        return item;
                }

                private ItemStack createSyncedInventoryItem() {
                        ItemStack item = new ItemStack(
                                        pendingSyncedInventory ? Items.COPPER_CHEST : Items.CHEST);
                        item.set(DataComponents.CUSTOM_NAME, createItemName("Synced Inventory",
                                        ChatFormatting.DARK_AQUA, ChatFormatting.BOLD));
                        ItemLore lore = new ItemLore(List.of(
                                        Component.literal("Status: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                                        .append(pendingSyncedInventory ? Component
                                                                        .literal("ENABLED")
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .applyFormat(ChatFormatting.GREEN))
                                                                        : Component.literal("DISABLED")
                                                                                        .setStyle(Style.EMPTY
                                                                                                        .withItalic(false)
                                                                                                        .applyFormat(ChatFormatting.RED))),
                                        Component.empty(),
                                        Component.literal("All players share the same inventory")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.DARK_GRAY)),
                                        Component.literal("(main, hotbar, armor, offhand).")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.DARK_GRAY)),
                                        Component.empty(),
                                        Component.literal("Click to toggle").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(ChatFormatting.DARK_GRAY))));
                        item.set(DataComponents.LORE, lore);

                        return item;
                }

                private ItemStack createConfirmItem() {
                        ItemStack item = new ItemStack(Items.EMERALD);
                        item.set(DataComponents.CUSTOM_NAME, createItemName("✓ Confirm",
                                        ChatFormatting.GREEN, ChatFormatting.BOLD));

                        List<Component> loreLines = new ArrayList<>();

                        // Current Settings header
                        loreLines.add(Component.literal("Current Settings:").setStyle(Style.EMPTY
                                        .withItalic(false).applyFormat(ChatFormatting.WHITE)));

                        // Difficulty
                        String diffName = getDifficultyName(pendingDifficulty);
                        ChatFormatting diffColor = switch (pendingDifficulty) {
                                case EASY -> ChatFormatting.GREEN;
                                case NORMAL -> ChatFormatting.YELLOW;
                                case HARD -> ChatFormatting.RED;
                                default -> ChatFormatting.WHITE;
                        };
                        loreLines.add(Component.literal("  • Difficulty: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                        .append(Component.literal(diffName).setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .applyFormat(diffColor))));

                        // Half-Heart Mode
                        loreLines.add(Component.literal("  • Half-Heart Mode: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                        .append(pendingHalfHeart ? Component.literal("Enabled")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.GREEN))
                                                        : Component.literal("Disabled").setStyle(
                                                                        Style.EMPTY.withItalic(
                                                                                        false)
                                                                                        .applyFormat(ChatFormatting.RED))));

                        // Shared Effects
                        loreLines.add(Component.literal("  • Shared Effects: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                        .append(pendingSharedPotions ? Component.literal("Enabled")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.GREEN))
                                                        : Component.literal("Disabled").setStyle(
                                                                        Style.EMPTY.withItalic(
                                                                                        false)
                                                                                        .applyFormat(ChatFormatting.RED))));

                        // Shared Jump
                        loreLines.add(Component.literal("  • Shared Jump: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                        .append(pendingSharedJumping ? Component.literal("Enabled")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.GREEN))
                                                        : Component.literal("Disabled").setStyle(
                                                                        Style.EMPTY.withItalic(
                                                                                        false)
                                                                                        .applyFormat(ChatFormatting.RED))));

                        // Manhunt Mode
                        loreLines.add(Component.literal("  • Manhunt Mode: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                        .append(pendingManhunt ? Component.literal("Enabled")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.GREEN))
                                                        : Component.literal("Disabled").setStyle(
                                                                        Style.EMPTY.withItalic(
                                                                                        false)
                                                                                        .applyFormat(ChatFormatting.RED))));

                        // Synced Inventory
                        loreLines.add(Component.literal("  • Synced Inventory: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .applyFormat(ChatFormatting.GRAY))
                                        .append(pendingSyncedInventory ? Component.literal("Enabled")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .applyFormat(ChatFormatting.GREEN))
                                                        : Component.literal("Disabled").setStyle(
                                                                        Style.EMPTY.withItalic(
                                                                                        false)
                                                                                        .applyFormat(ChatFormatting.RED))));

                        loreLines.add(Component.empty());
                        loreLines.add(Component.literal("⚠ Settings apply next run!")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .applyFormat(ChatFormatting.YELLOW)));
                        loreLines.add(Component.empty());
                        loreLines.add(Component.literal("Click to save and close.").setStyle(Style.EMPTY
                                        .withItalic(false).applyFormat(ChatFormatting.DARK_GRAY)));

                        ItemLore lore = new ItemLore(loreLines);
                        item.set(DataComponents.LORE, lore);

                        return item;
                }

                public boolean hasChanges() {
                        return pendingDifficulty != original.difficulty()
                                        || pendingHalfHeart != original.halfHeartMode()
                                        || pendingSharedPotions != original.sharedPotions()
                                        || pendingSharedJumping != original.sharedJumping()
                                        || pendingManhunt != original.manhuntMode()
                                        || pendingSyncedInventory != original.syncedInventory();
                }

                public Settings.SettingsSnapshot getPendingSnapshot() {
                        return new Settings.SettingsSnapshot(pendingDifficulty, pendingHalfHeart,
                                        pendingSharedPotions, pendingSharedJumping, pendingManhunt,
                                        pendingSyncedInventory);
                }

                public Settings.SettingsSnapshot getOriginal() {
                        return original;
                }

                public Difficulty getPendingDifficulty() {
                        return pendingDifficulty;
                }

                public boolean isPendingHalfHeart() {
                        return pendingHalfHeart;
                }

                public boolean isPendingSharedPotions() {
                        return pendingSharedPotions;
                }

                public boolean isPendingSharedJumping() {
                        return pendingSharedJumping;
                }

                public boolean isPendingManhunt() {
                        return pendingManhunt;
                }

                public boolean isPendingSyncedInventory() {
                        return pendingSyncedInventory;
                }

                // Setters for pending values
                public void cycleDifficulty() {
                        pendingDifficulty = switch (pendingDifficulty) {
                                case PEACEFUL, EASY -> Difficulty.NORMAL;
                                case NORMAL -> Difficulty.HARD;
                                case HARD -> Difficulty.EASY;
                        };
                }

                public void toggleHalfHeart() {
                        pendingHalfHeart = !pendingHalfHeart;
                }

                public void toggleSharedPotions() {
                        pendingSharedPotions = !pendingSharedPotions;
                }

                public void toggleSharedJumping() {
                        pendingSharedJumping = !pendingSharedJumping;
                }

                public void toggleManhunt() {
                        pendingManhunt = !pendingManhunt;
                }

                public void toggleSyncedInventory() {
                        pendingSyncedInventory = !pendingSyncedInventory;
                }
        }

        /**
         * Virtual slot that prevents all item interactions - items cannot be taken, inserted, or
         * moved.
         */
        private static class VirtualSlot extends Slot {
                public VirtualSlot(Container inventory, int index, int x, int y) {
                        super(inventory, index, x, y);
                }

                @Override
                public boolean mayPickup(Player playerEntity) {
                        // Prevent items from being taken from these slots
                        return false;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                        // Prevent items from being inserted into these slots
                        return false;
                }

                @Override
                public boolean isHighlightable() {
                        // Allow highlighting for visual feedback
                        return true;
                }
        }

        /**
         * Virtual screen handler for the settings GUI. Intercepts all slot clicks and handles them
         * server-side. Items in virtual slots cannot be moved, taken, or inserted - this is a
         * display-only GUI controlled entirely by the server.
         */
        public static class SettingsScreenHandler extends ChestMenu {

                private final SettingsInventory settingsInventory;
                private final ServerPlayer player;
                // Tracks if changes were confirmed vs cancelled
                private boolean confirmed = false;

                public SettingsScreenHandler(int syncId, SettingsInventory inventory,
                                ServerPlayer player) {
                        super(MenuType.GENERIC_9x6, syncId, player.getInventory(),
                                        inventory, 6);
                        this.settingsInventory = inventory;
                        this.player = player;

                        // Replace inventory slots (0-53) with virtual slots
                        // Player inventory slots (54+) should remain normal
                        for (int i = 0; i < INVENTORY_SIZE; i++) {
                                Slot oldSlot = this.slots.get(i);
                                Slot newSlot = new VirtualSlot(inventory, i, oldSlot.x, oldSlot.y);
                                this.slots.set(i, newSlot);
                        }
                }

                @Override
                public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
                        // Handle settings GUI slots (works for all game modes including spectator)
                        if (slotIndex < INVENTORY_SIZE && slotIndex >= 0) {
                                // Handle the settings change (this is a virtual click, not a real
                                // item interaction)
                                handleSettingsClick(slotIndex);

                                // Clear cursor on server side - virtual GUI doesn't allow item
                                // movement
                                setCarried(net.minecraft.world.item.ItemStack.EMPTY);

                                // For spectators, we need to force a complete state sync to avoid
                                // protocol errors
                                // The standard sendContentUpdates doesn't work properly for
                                // spectators because
                                // Minecraft's client ignores standard sync packets in spectator
                                // mode.
                                // Using updateToClient forces a complete resync with fresh revision
                                // numbers.
                                if (player instanceof ServerPlayer serverPlayer
                                        && serverPlayer.gameMode
                                        .getGameModeForPlayer() == GameType.SPECTATOR) {
                                        // Force complete state sync - this sends all slots + cursor
                                        // with fresh revision
                                        ScreenHandlerAccessor accessor =
                                                (ScreenHandlerAccessor) this;
                                        accessor.invokeUpdateToClient();
                                } else {
                                        // For non-spectators, normal content updates work fine
                                        super.broadcastChanges();
                                }

                                // Don't call parent - this is a virtual GUI, we handle everything
                                // ourselves
                                return;
                        }
                        super.clicked(slotIndex, buttonNum, containerInput, player);
                }

                @Override
                public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player playerEntity,
                                int slot) {
                        // Virtual GUI - disable all shift-click transfers
                        return ItemStack.EMPTY;
                }

                @Override
                public boolean canTakeItemForPickAll(ItemStack stack,
                                net.minecraft.world.inventory.Slot slot) {
                        // Virtual GUI - prevent any item insertion into virtual slots
                        if (slot.container == settingsInventory) {
                                return false;
                        }
                        // Allow normal player inventory interactions
                        return super.canTakeItemForPickAll(stack, slot);
                }

                private void handleSettingsClick(int slotIndex) {
                        switch (slotIndex) {
                                case DIFFICULTY_SLOT -> {
                                        settingsInventory.cycleDifficulty();
                                        settingsInventory.populateItems();
                                        playClickSound();
                                }
                                case HALF_HEART_SLOT -> {
                                        settingsInventory.toggleHalfHeart();
                                        settingsInventory.populateItems();
                                        playClickSound();
                                }
                                case SHARED_POTIONS_SLOT -> {
                                        settingsInventory.toggleSharedPotions();
                                        settingsInventory.populateItems();
                                        playClickSound();
                                }
                                case SHARED_JUMPING_SLOT -> {
                                        settingsInventory.toggleSharedJumping();
                                        settingsInventory.populateItems();
                                        playClickSound();
                                }
                                case MANHUNT_SLOT -> {
                                        settingsInventory.toggleManhunt();
                                        settingsInventory.populateItems();
                                        playClickSound();
                                }
                                case SYNCED_INVENTORY_SLOT -> {
                                        settingsInventory.toggleSyncedInventory();
                                        settingsInventory.populateItems();
                                        playClickSound();
                                }
                                case CONFIRM_SLOT -> {
                                        if (settingsInventory.hasChanges()) {
                                                // Apply the changes
                                                Settings.getInstance()
                                                                .applySnapshot(settingsInventory
                                                                                .getPendingSnapshot());
                                                confirmed = true;
                                                MinecraftServer server = RunManager.getInstance()
                                                                .getServer();
                                                if (server != null) {
                                                        SettingsPersistence.save(server);
                                                }

                                                // Broadcast changes to all players
                                                announceSettingsToChat();

                                                player.closeContainer();
                                                playConfirmSound();
                                        } else {
                                                player.sendSystemMessage(RunManager.formatMessage(
                                                                "No changes to save."));
                                                player.closeContainer();
                                        }
                                }
                        }
                }

                /**
                 * Broadcasts the settings changes to all players in chat.
                 */

                public void announceSettingsToChat() {
                        if (!settingsInventory.hasChanges()) return;
                        RunManager runManager = RunManager.getInstance();
                        if (runManager == null)
                                return;

                        MinecraftServer server = runManager.getServer();
                        if (server == null)
                                return;

                        Settings.SettingsSnapshot orig = settingsInventory.getOriginal();
                        String playerName = player.getName().getString();

                        // Header message
                        Component headerMsg = Component.empty().append(RunManager.getPrefix())
                                        .append(Component.literal(playerName)
                                                        .withStyle(ChatFormatting.WHITE))
                                        .append(Component.literal(" changed settings:")
                                                        .withStyle(ChatFormatting.GRAY));
                        server.getPlayerList().broadcastSystemMessage(headerMsg, false);

                        // List each change
                        if (settingsInventory.getPendingDifficulty() != orig.difficulty()) {
                                String oldDiff = getDifficultyName(orig.difficulty());
                                String newDiff = getDifficultyName(
                                                settingsInventory.getPendingDifficulty());
                                Component changeMsg = Component.empty().append(RunManager.getPrefix())
                                                .append(Component.literal("  • Difficulty: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .applyFormat(ChatFormatting.GRAY)))
                                                .append(Component.literal(oldDiff).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.RED)))
                                                .append(Component.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.DARK_GRAY)))
                                                .append(Component.literal(newDiff).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.GREEN)));
                                server.getPlayerList().broadcastSystemMessage(changeMsg, false);
                        }

                        if (settingsInventory.isPendingHalfHeart() != orig.halfHeartMode()) {
                                String oldVal = orig.halfHeartMode() ? "ON" : "OFF";
                                String newVal = settingsInventory.isPendingHalfHeart() ? "ON"
                                                : "OFF";
                                Component changeMsg = Component.empty().append(RunManager.getPrefix())
                                                .append(Component.literal("  • Half Heart Mode: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .applyFormat(ChatFormatting.GRAY)))
                                                .append(Component.literal(oldVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.RED)))
                                                .append(Component.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.DARK_GRAY)))
                                                .append(Component.literal(newVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.GREEN)));
                                server.getPlayerList().broadcastSystemMessage(changeMsg, false);
                        }

                        if (settingsInventory.isPendingSharedPotions() != orig.sharedPotions()) {
                                String oldVal = orig.sharedPotions() ? "ON" : "OFF";
                                String newVal = settingsInventory.isPendingSharedPotions() ? "ON"
                                                : "OFF";
                                Component changeMsg = Component.empty().append(RunManager.getPrefix())
                                                .append(Component.literal("  • Shared Potions: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .applyFormat(ChatFormatting.GRAY)))
                                                .append(Component.literal(oldVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.RED)))
                                                .append(Component.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.DARK_GRAY)))
                                                .append(Component.literal(newVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.GREEN)));
                                server.getPlayerList().broadcastSystemMessage(changeMsg, false);
                        }

                        if (settingsInventory.isPendingSharedJumping() != orig.sharedJumping()) {
                                String oldVal = orig.sharedJumping() ? "ON" : "OFF";
                                String newVal = settingsInventory.isPendingSharedJumping() ? "ON"
                                                : "OFF";
                                Component changeMsg = Component.empty().append(RunManager.getPrefix())
                                                .append(Component.literal("  • Shared Jumping: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .applyFormat(ChatFormatting.GRAY)))
                                                .append(Component.literal(oldVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.RED)))
                                                .append(Component.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.DARK_GRAY)))
                                                .append(Component.literal(newVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.GREEN)));
                                server.getPlayerList().broadcastSystemMessage(changeMsg, false);
                        }

                        if (settingsInventory.isPendingManhunt() != orig.manhuntMode()) {
                                String oldVal = orig.manhuntMode() ? "ON" : "OFF";
                                String newVal = settingsInventory.isPendingManhunt() ? "ON" : "OFF";
                                Component changeMsg = Component.empty().append(RunManager.getPrefix())
                                                .append(Component.literal("  • Manhunt Mode: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .applyFormat(ChatFormatting.GRAY)))
                                                .append(Component.literal(oldVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.RED)))
                                                .append(Component.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.DARK_GRAY)))
                                                .append(Component.literal(newVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.GREEN)));
                                server.getPlayerList().broadcastSystemMessage(changeMsg, false);
                        }

                        if (settingsInventory.isPendingSyncedInventory() != orig
                                        .syncedInventory()) {
                                String oldVal = orig.syncedInventory() ? "ON" : "OFF";
                                String newVal = settingsInventory.isPendingSyncedInventory() ? "ON"
                                                : "OFF";
                                Component changeMsg = Component.empty().append(RunManager.getPrefix())
                                                .append(Component.literal("  • Synced Inventory: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .applyFormat(ChatFormatting.GRAY)))
                                                .append(Component.literal(oldVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.RED)))
                                                .append(Component.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.DARK_GRAY)))
                                                .append(Component.literal(newVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .applyFormat(ChatFormatting.GREEN)));
                                server.getPlayerList().broadcastSystemMessage(changeMsg, false);
                        }

                        // Footer message
                        Component footerMsg = Component.empty().append(RunManager.getPrefix())
                                        .append(Component.literal("Changes will apply on next run.")
                                                        .withStyle(ChatFormatting.YELLOW));
                        server.getPlayerList().broadcastSystemMessage(footerMsg, false);
                }

                private String getDifficultyName(Difficulty difficulty) {
                        return SettingsGui.getDifficultyName(difficulty);
                }

                private void playClickSound() {
                        player.connection.send(
                                        new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                                                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK,
                                                        net.minecraft.sounds.SoundSource.MASTER,
                                                        player.getX(), player.getY(), player.getZ(),
                                                        0.5f, 1.0f, player.getRandom().nextLong()));
                }

                private void playConfirmSound() {
                        player.connection.send(
                                        new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                                                        net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT
                                                                        .wrapAsHolder(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP),
                                                        net.minecraft.sounds.SoundSource.MASTER,
                                                        player.getX(), player.getY(), player.getZ(),
                                                        0.5f, 1.0f, player.getRandom().nextLong()));
                }


                @Override
                public void removed(net.minecraft.world.entity.player.Player closingPlayer) {
                        super.removed(closingPlayer);

                        // If closed without confirming and there were changes, notify player
                        if (!confirmed && settingsInventory.hasChanges()) {
                                player.sendSystemMessage(
                                                RunManager.formatMessage(
                                                                "Chaos mode changes discarded."));
                        }
                }

                @Override
                public boolean stillValid(net.minecraft.world.entity.player.Player playerEntity) {
                        return true;
                }
        }

        private static String getDifficultyName(net.minecraft.world.Difficulty difficulty) {
                return switch (difficulty) {
                        case PEACEFUL -> "Peaceful";
                        case EASY -> "Easy";
                        case NORMAL -> "Normal";
                        case HARD -> "Hard";
                };
        }
}
