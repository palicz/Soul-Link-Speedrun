package net.zenzty.soullink.server.settings;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
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
        private static final int CONFIRM_SLOT = 49; // Bottom center

        // Size of double chest
        private static final int INVENTORY_SIZE = 54;

        /**
         * Opens the settings GUI for a player. Note: Spectators can view but cannot interact due to
         * Minecraft client limitations.
         */
        public static void open(ServerPlayerEntity player) {
                Settings settings = Settings.getInstance();
                // Pre-fill from pending if it exists (so the GUI shows queued changes, not the
                // in-memory values for the current run). Otherwise use current settings and world
                // difficulty.
                Settings.SettingsSnapshot originalSnapshot;
                Settings.SettingsSnapshot pending = settings.getPendingSnapshotOrNull();
                if (pending != null) {
                        originalSnapshot = pending;
                } else {
                        Difficulty worldDifficulty = player.getServerWorld().getDifficulty();
                        if (worldDifficulty == Difficulty.PEACEFUL) {
                                worldDifficulty = Difficulty.EASY;
                        }
                        originalSnapshot = new Settings.SettingsSnapshot(worldDifficulty,
                                        settings.isHalfHeartMode(), settings.isSharedPotions(),
                                        settings.isSharedJumping(), settings.isManhuntMode());
                }

                // Create inventory with all slots
                SettingsInventory inventory = new SettingsInventory(originalSnapshot);

                // Open the screen
                player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                                (syncId, playerInventory, playerEntity) -> {
                                        return new SettingsScreenHandler(syncId, inventory, player);
                                }, Text.literal("Soul Link Chaos Modes")
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
         * Virtual inventory that tracks pending settings changes. This is a server-side only
         * inventory that doesn't represent any real container in the world.
         */
        public static class SettingsInventory extends SimpleInventory {

                private Difficulty pendingDifficulty;
                private boolean pendingHalfHeart;
                private boolean pendingSharedPotions;
                private boolean pendingSharedJumping;
                private boolean pendingManhunt;
                private final Settings.SettingsSnapshot original;

                public SettingsInventory(Settings.SettingsSnapshot original) {
                        super(INVENTORY_SIZE);
                        this.original = original;
                        this.pendingDifficulty = original.difficulty();
                        this.pendingHalfHeart = original.halfHeartMode();
                        this.pendingSharedPotions = original.sharedPotions();
                        this.pendingSharedJumping = original.sharedJumping();
                        this.pendingManhunt = original.manhuntMode();

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

                        // Add difficulty setting
                        setStack(DIFFICULTY_SLOT, createDifficultyItem());

                        // Add half heart setting
                        setStack(HALF_HEART_SLOT, createHalfHeartItem());

                        // Add shared potions setting
                        setStack(SHARED_POTIONS_SLOT, createSharedPotionsItem());

                        // Add shared jumping setting
                        setStack(SHARED_JUMPING_SLOT, createSharedJumpingItem());

                        // Add manhunt mode setting
                        setStack(MANHUNT_SLOT, createManhuntItem());

                        // Add confirm button
                        setStack(CONFIRM_SLOT, createConfirmItem());
                }

                private ItemStack createDifficultyItem() {
                        ItemStack item = new ItemStack(switch (pendingDifficulty) {
                                case EASY -> Items.WOODEN_SWORD;
                                case NORMAL -> Items.IRON_SWORD;
                                case HARD -> Items.DIAMOND_SWORD;
                                default -> Items.WOODEN_SWORD;
                        });
                        item.set(DataComponentTypes.CUSTOM_NAME, createItemName("Difficulty",
                                        Formatting.RED, Formatting.BOLD));

                        String difficultyName = switch (pendingDifficulty) {
                                case PEACEFUL -> "Peaceful";
                                case EASY -> "Easy";
                                case NORMAL -> "Normal";
                                case HARD -> "Hard";
                        };

                        Formatting difficultyColor = switch (pendingDifficulty) {
                                case EASY -> Formatting.GREEN;
                                case NORMAL -> Formatting.YELLOW;
                                case HARD -> Formatting.RED;
                                default -> Formatting.WHITE;
                        };

                        LoreComponent difficultyLore = new LoreComponent(List.of(Text
                                        .literal("Change to: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                        .append(Text.literal(difficultyName).setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(difficultyColor))),
                                        Text.empty(),
                                        Text.literal("Current: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                                        .append(Text.literal(getDifficultyName(
                                                                        original.difficulty()))
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .withFormatting(Formatting.WHITE))),
                                        Text.empty(),
                                        Text.literal("Click to cycle difficulty")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY))));
                        item.set(DataComponentTypes.LORE, difficultyLore);

                        return item;
                }

                private ItemStack createHalfHeartItem() {
                        ItemStack item = new ItemStack(
                                        pendingHalfHeart ? Items.GOLDEN_APPLE : Items.APPLE);
                        item.set(DataComponentTypes.CUSTOM_NAME, createItemName("Half Hearted Mode",
                                        Formatting.LIGHT_PURPLE, Formatting.BOLD));

                        LoreComponent halfHeartLore = new LoreComponent(List.of(
                                        Text.literal("Status: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                                        .append(pendingHalfHeart ? Text
                                                                        .literal("ENABLED")
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .withFormatting(Formatting.GREEN))
                                                                        : Text.literal("DISABLED")
                                                                                        .setStyle(Style.EMPTY
                                                                                                        .withItalic(false)
                                                                                                        .withFormatting(Formatting.RED))),
                                        Text.empty(),
                                        Text.literal("Players have only 1 health point!")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY)),
                                        Text.empty(),
                                        Text.literal("Click to toggle").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.DARK_GRAY))));
                        item.set(DataComponentTypes.LORE, halfHeartLore);

                        return item;
                }

                private ItemStack createSharedPotionsItem() {
                        ItemStack item = new ItemStack(pendingSharedPotions ? Items.DRAGON_BREATH
                                        : Items.GLASS_BOTTLE);

                        item.set(DataComponentTypes.CUSTOM_NAME, createItemName(
                                        "Shared Potions Mode", Formatting.BLUE, Formatting.BOLD));

                        LoreComponent sharedPotionsLore = new LoreComponent(List.of(
                                        Text.literal("Status: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                                        .append(pendingSharedPotions ? Text
                                                                        .literal("ENABLED")
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .withFormatting(Formatting.GREEN))
                                                                        : Text.literal("DISABLED")
                                                                                        .setStyle(Style.EMPTY
                                                                                                        .withItalic(false)
                                                                                                        .withFormatting(Formatting.RED))),
                                        Text.empty(),
                                        Text.literal("Potion effects are shared between")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY)),
                                        Text.literal("all players.").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.DARK_GRAY)),
                                        Text.empty(),
                                        Text.literal("Click to toggle").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.DARK_GRAY))));
                        item.set(DataComponentTypes.LORE, sharedPotionsLore);

                        return item;
                }

                private ItemStack createSharedJumpingItem() {
                        ItemStack item = new ItemStack(
                                        pendingSharedJumping ? Items.RABBIT_FOOT : Items.FEATHER);
                        item.set(DataComponentTypes.CUSTOM_NAME, createItemName(
                                        "Shared Jumping Mode", Formatting.YELLOW, Formatting.BOLD));
                        LoreComponent sharedJumpingLore = new LoreComponent(List.of(
                                        Text.literal("Status: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                                        .append(pendingSharedJumping ? Text
                                                                        .literal("ENABLED")
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .withFormatting(Formatting.GREEN))
                                                                        : Text.literal("DISABLED")
                                                                                        .setStyle(Style.EMPTY
                                                                                                        .withItalic(false)
                                                                                                        .withFormatting(Formatting.RED))),
                                        Text.empty(),
                                        Text.literal("If one player jumps, all players jump.")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY)),
                                        Text.empty(),
                                        Text.literal("Click to toggle").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.DARK_GRAY))));
                        item.set(DataComponentTypes.LORE, sharedJumpingLore);

                        return item;
                }

                private ItemStack createManhuntItem() {
                        ItemStack item = new ItemStack(
                                        pendingManhunt ? Items.COMPASS : Items.ENDER_EYE);
                        item.set(DataComponentTypes.CUSTOM_NAME, createItemName("Manhunt Mode",
                                        Formatting.DARK_PURPLE, Formatting.BOLD));
                        LoreComponent manhuntLore = new LoreComponent(List.of(
                                        Text.literal("Status: ").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                                        .append(pendingManhunt ? Text
                                                                        .literal("ENABLED")
                                                                        .setStyle(Style.EMPTY
                                                                                        .withItalic(false)
                                                                                        .withFormatting(Formatting.GREEN))
                                                                        : Text.literal("DISABLED")
                                                                                        .setStyle(Style.EMPTY
                                                                                                        .withItalic(false)
                                                                                                        .withFormatting(Formatting.RED))),
                                        Text.empty(),
                                        Text.literal("Runners share health; Hunters hunt.")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY)),
                                        Text.literal("30s head start, hunter respawns.")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.DARK_GRAY)),
                                        Text.empty(),
                                        Text.literal("Click to toggle").setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(Formatting.DARK_GRAY))));
                        item.set(DataComponentTypes.LORE, manhuntLore);

                        return item;
                }

                private ItemStack createConfirmItem() {
                        ItemStack item = new ItemStack(Items.EMERALD);
                        item.set(DataComponentTypes.CUSTOM_NAME, createItemName("✓ Confirm",
                                        Formatting.GREEN, Formatting.BOLD));

                        List<Text> loreLines = new ArrayList<>();

                        // Current Settings header
                        loreLines.add(Text.literal("Current Settings:").setStyle(Style.EMPTY
                                        .withItalic(false).withFormatting(Formatting.WHITE)));

                        // Difficulty
                        String diffName = getDifficultyName(pendingDifficulty);
                        Formatting diffColor = switch (pendingDifficulty) {
                                case EASY -> Formatting.GREEN;
                                case NORMAL -> Formatting.YELLOW;
                                case HARD -> Formatting.RED;
                                default -> Formatting.WHITE;
                        };
                        loreLines.add(Text.literal("  • Difficulty: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                        .append(Text.literal(diffName).setStyle(Style.EMPTY
                                                        .withItalic(false)
                                                        .withFormatting(diffColor))));

                        // Half-Heart Mode
                        loreLines.add(Text.literal("  • Half-Heart Mode: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                        .append(pendingHalfHeart ? Text.literal("Enabled")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.GREEN))
                                                        : Text.literal("Disabled").setStyle(
                                                                        Style.EMPTY.withItalic(
                                                                                        false)
                                                                                        .withFormatting(Formatting.RED))));

                        // Shared Effects
                        loreLines.add(Text.literal("  • Shared Effects: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                        .append(pendingSharedPotions ? Text.literal("Enabled")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.GREEN))
                                                        : Text.literal("Disabled").setStyle(
                                                                        Style.EMPTY.withItalic(
                                                                                        false)
                                                                                        .withFormatting(Formatting.RED))));

                        // Shared Jump
                        loreLines.add(Text.literal("  • Shared Jump: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                        .append(pendingSharedJumping ? Text.literal("Enabled")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.GREEN))
                                                        : Text.literal("Disabled").setStyle(
                                                                        Style.EMPTY.withItalic(
                                                                                        false)
                                                                                        .withFormatting(Formatting.RED))));

                        // Manhunt Mode
                        loreLines.add(Text.literal("  • Manhunt Mode: ")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .withFormatting(Formatting.GRAY))
                                        .append(pendingManhunt ? Text.literal("Enabled")
                                                        .setStyle(Style.EMPTY.withItalic(false)
                                                                        .withFormatting(Formatting.GREEN))
                                                        : Text.literal("Disabled").setStyle(
                                                                        Style.EMPTY.withItalic(
                                                                                        false)
                                                                                        .withFormatting(Formatting.RED))));

                        loreLines.add(Text.empty());
                        loreLines.add(Text.literal("⚠ Settings apply next run!")
                                        .setStyle(Style.EMPTY.withItalic(false)
                                                        .withFormatting(Formatting.YELLOW)));
                        loreLines.add(Text.empty());
                        loreLines.add(Text.literal("Click to save and close.").setStyle(Style.EMPTY
                                        .withItalic(false).withFormatting(Formatting.DARK_GRAY)));

                        LoreComponent lore = new LoreComponent(loreLines);
                        item.set(DataComponentTypes.LORE, lore);

                        return item;
                }

                public boolean hasChanges() {
                        return pendingDifficulty != original.difficulty()
                                        || pendingHalfHeart != original.halfHeartMode()
                                        || pendingSharedPotions != original.sharedPotions()
                                        || pendingSharedJumping != original.sharedJumping()
                                        || pendingManhunt != original.manhuntMode();
                }

                public Settings.SettingsSnapshot getPendingSnapshot() {
                        return new Settings.SettingsSnapshot(pendingDifficulty, pendingHalfHeart,
                                        pendingSharedPotions, pendingSharedJumping, pendingManhunt);
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
        }

        /**
         * Virtual slot that prevents all item interactions - items cannot be taken, inserted, or
         * moved.
         */
        private static class VirtualSlot extends Slot {
                public VirtualSlot(Inventory inventory, int index, int x, int y) {
                        super(inventory, index, x, y);
                }

                @Override
                public boolean canTakeItems(PlayerEntity playerEntity) {
                        // Prevent items from being taken from these slots
                        return false;
                }

                @Override
                public boolean canInsert(ItemStack stack) {
                        // Prevent items from being inserted into these slots
                        return false;
                }

                @Override
                public boolean canBeHighlighted() {
                        // Allow highlighting for visual feedback
                        return true;
                }
        }

        /**
         * Virtual screen handler for the settings GUI. Intercepts all slot clicks and handles them
         * server-side. Items in virtual slots cannot be moved, taken, or inserted - this is a
         * display-only GUI controlled entirely by the server.
         */
        public static class SettingsScreenHandler extends GenericContainerScreenHandler {

                private final SettingsInventory settingsInventory;
                private final ServerPlayerEntity player;
                // Tracks if changes were confirmed vs cancelled
                private boolean confirmed = false;

                public SettingsScreenHandler(int syncId, SettingsInventory inventory,
                                ServerPlayerEntity player) {
                        super(ScreenHandlerType.GENERIC_9X6, syncId, player.getInventory(),
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
                public void onSlotClick(int slotIndex, int button, SlotActionType actionType,
                                net.minecraft.entity.player.PlayerEntity clickingPlayer) {
                        // Handle settings GUI slots (works for all game modes including spectator)
                        if (slotIndex < INVENTORY_SIZE && slotIndex >= 0) {
                                // Handle the settings change (this is a virtual click, not a real
                                // item interaction)
                                handleSettingsClick(slotIndex);

                                // Clear cursor on server side - virtual GUI doesn't allow item
                                // movement
                                setCursorStack(net.minecraft.item.ItemStack.EMPTY);

                                // For spectators, we need to force a complete state sync to avoid
                                // protocol errors
                                // The standard sendContentUpdates doesn't work properly for
                                // spectators because
                                // Minecraft's client ignores standard sync packets in spectator
                                // mode.
                                // Using updateToClient forces a complete resync with fresh revision
                                // numbers.
                                if (clickingPlayer instanceof ServerPlayerEntity serverPlayer
                                                && serverPlayer.interactionManager
                                                                .getGameMode() == GameMode.SPECTATOR) {
                                        // Force complete state sync - this sends all slots + cursor
                                        // with fresh revision
                                        ScreenHandlerAccessor accessor =
                                                        (ScreenHandlerAccessor) this;
                                        accessor.invokeUpdateToClient();
                                } else {
                                        // For non-spectators, normal content updates work fine
                                        sendContentUpdates();
                                }

                                // Don't call parent - this is a virtual GUI, we handle everything
                                // ourselves
                                return;
                        }

                        // Delegate player inventory slot clicks (54+) to super for normal behavior
                        super.onSlotClick(slotIndex, button, actionType, clickingPlayer);
                }

                @Override
                public ItemStack quickMove(net.minecraft.entity.player.PlayerEntity playerEntity,
                                int slot) {
                        // Virtual GUI - disable all shift-click transfers
                        return ItemStack.EMPTY;
                }

                @Override
                public boolean canInsertIntoSlot(ItemStack stack,
                                net.minecraft.screen.slot.Slot slot) {
                        // Virtual GUI - prevent any item insertion into virtual slots
                        if (slot.inventory == settingsInventory) {
                                return false;
                        }
                        // Allow normal player inventory interactions
                        return super.canInsertIntoSlot(stack, slot);
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
                                                broadcastChanges();

                                                player.closeHandledScreen();
                                                playConfirmSound();
                                        } else {
                                                player.sendMessage(RunManager.formatMessage(
                                                                "No changes to save."), false);
                                                player.closeHandledScreen();
                                        }
                                }
                        }
                }

                /**
                 * Broadcasts the settings changes to all players in chat.
                 */
                private void broadcastChanges() {
                        RunManager runManager = RunManager.getInstance();
                        if (runManager == null)
                                return;

                        MinecraftServer server = runManager.getServer();
                        if (server == null)
                                return;

                        Settings.SettingsSnapshot orig = settingsInventory.getOriginal();
                        String playerName = player.getName().getString();

                        // Header message
                        Text headerMsg = Text.empty().append(RunManager.getPrefix())
                                        .append(Text.literal(playerName)
                                                        .formatted(Formatting.WHITE))
                                        .append(Text.literal(" changed settings:")
                                                        .formatted(Formatting.GRAY));
                        server.getPlayerManager().broadcast(headerMsg, false);

                        // List each change
                        if (settingsInventory.getPendingDifficulty() != orig.difficulty()) {
                                String oldDiff = getDifficultyName(orig.difficulty());
                                String newDiff = getDifficultyName(
                                                settingsInventory.getPendingDifficulty());
                                Text changeMsg = Text.empty().append(RunManager.getPrefix())
                                                .append(Text.literal("  • Difficulty: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .withFormatting(Formatting.GRAY)))
                                                .append(Text.literal(oldDiff).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.RED)))
                                                .append(Text.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.DARK_GRAY)))
                                                .append(Text.literal(newDiff).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.GREEN)));
                                server.getPlayerManager().broadcast(changeMsg, false);
                        }

                        if (settingsInventory.isPendingHalfHeart() != orig.halfHeartMode()) {
                                String oldVal = orig.halfHeartMode() ? "ON" : "OFF";
                                String newVal = settingsInventory.isPendingHalfHeart() ? "ON"
                                                : "OFF";
                                Text changeMsg = Text.empty().append(RunManager.getPrefix())
                                                .append(Text.literal("  • Half Heart Mode: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .withFormatting(Formatting.GRAY)))
                                                .append(Text.literal(oldVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.RED)))
                                                .append(Text.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.DARK_GRAY)))
                                                .append(Text.literal(newVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.GREEN)));
                                server.getPlayerManager().broadcast(changeMsg, false);
                        }

                        if (settingsInventory.isPendingSharedPotions() != orig.sharedPotions()) {
                                String oldVal = orig.sharedPotions() ? "ON" : "OFF";
                                String newVal = settingsInventory.isPendingSharedPotions() ? "ON"
                                                : "OFF";
                                Text changeMsg = Text.empty().append(RunManager.getPrefix())
                                                .append(Text.literal("  • Shared Potions: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .withFormatting(Formatting.GRAY)))
                                                .append(Text.literal(oldVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.RED)))
                                                .append(Text.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.DARK_GRAY)))
                                                .append(Text.literal(newVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.GREEN)));
                                server.getPlayerManager().broadcast(changeMsg, false);
                        }

                        if (settingsInventory.isPendingSharedJumping() != orig.sharedJumping()) {
                                String oldVal = orig.sharedJumping() ? "ON" : "OFF";
                                String newVal = settingsInventory.isPendingSharedJumping() ? "ON"
                                                : "OFF";
                                Text changeMsg = Text.empty().append(RunManager.getPrefix())
                                                .append(Text.literal("  • Shared Jumping: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .withFormatting(Formatting.GRAY)))
                                                .append(Text.literal(oldVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.RED)))
                                                .append(Text.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.DARK_GRAY)))
                                                .append(Text.literal(newVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.GREEN)));
                                server.getPlayerManager().broadcast(changeMsg, false);
                        }

                        if (settingsInventory.isPendingManhunt() != orig.manhuntMode()) {
                                String oldVal = orig.manhuntMode() ? "ON" : "OFF";
                                String newVal = settingsInventory.isPendingManhunt() ? "ON" : "OFF";
                                Text changeMsg = Text.empty().append(RunManager.getPrefix())
                                                .append(Text.literal("  • Manhunt Mode: ")
                                                                .setStyle(Style.EMPTY
                                                                                .withItalic(false)
                                                                                .withFormatting(Formatting.GRAY)))
                                                .append(Text.literal(oldVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.RED)))
                                                .append(Text.literal(" → ").setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.DARK_GRAY)))
                                                .append(Text.literal(newVal).setStyle(Style.EMPTY
                                                                .withItalic(false)
                                                                .withFormatting(Formatting.GREEN)));
                                server.getPlayerManager().broadcast(changeMsg, false);
                        }

                        // Footer message
                        Text footerMsg = Text.empty().append(RunManager.getPrefix())
                                        .append(Text.literal("Changes will apply on next run.")
                                                        .formatted(Formatting.YELLOW));
                        server.getPlayerManager().broadcast(footerMsg, false);
                }

                private String getDifficultyName(Difficulty difficulty) {
                        return SettingsGui.getDifficultyName(difficulty);
                }

                private void playClickSound() {
                        player.networkHandler.sendPacket(
                                        new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
                                                        net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK,
                                                        net.minecraft.sound.SoundCategory.MASTER,
                                                        player.getX(), player.getY(), player.getZ(),
                                                        0.5f, 1.0f, player.getRandom().nextLong()));
                }

                private void playConfirmSound() {
                        player.networkHandler.sendPacket(
                                        new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
                                                        net.minecraft.registry.Registries.SOUND_EVENT
                                                                        .getEntry(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP),
                                                        net.minecraft.sound.SoundCategory.MASTER,
                                                        player.getX(), player.getY(), player.getZ(),
                                                        0.5f, 1.0f, player.getRandom().nextLong()));
                }


                @Override
                public void onClosed(net.minecraft.entity.player.PlayerEntity closingPlayer) {
                        super.onClosed(closingPlayer);

                        // If closed without confirming and there were changes, notify player
                        if (!confirmed && settingsInventory.hasChanges()) {
                                player.sendMessage(
                                                RunManager.formatMessage(
                                                                "Chaos mode changes discarded."),
                                                false);
                        }
                }

                @Override
                public boolean canUse(net.minecraft.entity.player.PlayerEntity playerEntity) {
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
