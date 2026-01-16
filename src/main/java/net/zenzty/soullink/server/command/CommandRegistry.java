package net.zenzty.soullink.server.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.SettingsGui;

/**
 * Registers all mod commands: /start, /stoprun, /runinfo, /settings, /reset
 */
public class CommandRegistry {

        /**
         * Registers all commands for the SoulLink mod.
         */
        public static void register() {
                CommandRegistrationCallback.EVENT
                                .register((dispatcher, registryAccess, environment) -> {
                                        // /start - Start a new run
                                        dispatcher.register(CommandManager.literal("start")
                                                        .executes(CommandRegistry::handleStart));

                                        // /stoprun - Admin command to stop current run (requires
                                        // gamemaster permission)
                                        dispatcher.register(CommandManager.literal("stoprun")
                                                        .requires(CommandManager
                                                                        .requirePermissionLevel(
                                                                                        CommandManager.GAMEMASTERS_CHECK))
                                                        .executes(CommandRegistry::handleStopRun));

                                        // /runinfo - Display current run info
                                        dispatcher.register(CommandManager.literal("runinfo")
                                                        .executes(CommandRegistry::handleRunInfo));

                                        // /settings - Open the settings GUI
                                        dispatcher.register(CommandManager.literal("settings")
                                                        .executes(CommandRegistry::handleSettings));

                                        // /reset - Manually reset the current run
                                        dispatcher.register(CommandManager.literal("reset")
                                                        .executes(CommandRegistry::handleReset));
                                });
        }

        private static int handleStart(CommandContext<ServerCommandSource> context) {
                RunManager runManager;
                try {
                        runManager = RunManager.getInstance();
                } catch (IllegalStateException e) {
                        context.getSource().sendError(
                                        RunManager.formatMessage("Run manager not initialized."));
                        return 0;
                }

                if (runManager == null) {
                        context.getSource().sendError(
                                        RunManager.formatMessage("Run manager not initialized."));
                        return 0;
                }

                // Open role selector GUI
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player != null) {
                        net.zenzty.soullink.server.manhunt.SpeedrunnerSelectorGui.open(player);
                } else {
                        context.getSource().sendError(RunManager
                                        .formatMessage("Only players can use this command."));
                        return 0;
                }

                return Command.SINGLE_SUCCESS;
        }

        private static int handleStopRun(CommandContext<ServerCommandSource> context) {
                RunManager runManager;
                try {
                        runManager = RunManager.getInstance();
                } catch (IllegalStateException e) {
                        context.getSource().sendError(
                                        RunManager.formatMessage("Run manager not initialized."));
                        return 0;
                }

                if (runManager == null || !runManager.isRunActive()) {
                        context.getSource().sendError(RunManager.formatMessage("No active run."));
                        return 0;
                }

                runManager.triggerGameOver();

                context.getSource().sendFeedback(() -> RunManager.formatMessage("Run stopped."),
                                true);

                return Command.SINGLE_SUCCESS;
        }

        private static int handleRunInfo(CommandContext<ServerCommandSource> context) {
                RunManager runManager;
                try {
                        runManager = RunManager.getInstance();
                } catch (IllegalStateException e) {
                        context.getSource().sendError(
                                        RunManager.formatMessage("Run manager not initialized."));
                        return 0;
                }

                if (runManager == null) {
                        context.getSource().sendError(
                                        RunManager.formatMessage("Run manager not initialized."));
                        return 0;
                }

                Text info = Text.empty().append(RunManager.getPrefix())
                                .append(Text.literal("State: ").formatted(Formatting.GRAY))
                                .append(Text.literal(runManager.getGameState().name())
                                                .formatted(Formatting.WHITE))
                                .append(Text.literal(" | Health: ").formatted(Formatting.GRAY))
                                .append(Text.literal(String.format("%.1f",
                                                SharedStatsHandler.getSharedHealth()))
                                                .formatted(Formatting.WHITE))
                                .append(Text.literal(" | Hunger: ").formatted(Formatting.GRAY))
                                .append(Text.literal(String
                                                .valueOf(SharedStatsHandler.getSharedHunger()))
                                                .formatted(Formatting.WHITE));

                context.getSource().sendFeedback(() -> info, false);

                return Command.SINGLE_SUCCESS;
        }

        private static int handleSettings(CommandContext<ServerCommandSource> context) {
                if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                        SettingsGui.open(player);
                        return Command.SINGLE_SUCCESS;
                }
                context.getSource().sendError(
                                RunManager.formatMessage("Only players can use this command."));
                return 0;
        }

        private static int handleReset(CommandContext<ServerCommandSource> context) {
                RunManager runManager;
                try {
                        runManager = RunManager.getInstance();
                } catch (IllegalStateException e) {
                        context.getSource().sendError(
                                        RunManager.formatMessage("Run manager not initialized."));
                        return 0;
                }

                if (runManager == null || !runManager.isRunActive()) {
                        context.getSource().sendError(
                                        RunManager.formatMessage("No active run to reset."));
                        return 0;
                }

                // Perform the reset first
                runManager.triggerGameOver();

                // Broadcast reset message to all players after successful reset
                runManager.getServer().getPlayerManager()
                                .broadcast(RunManager.formatMessage("Run has been reset."), false);
                return Command.SINGLE_SUCCESS;
        }
}
