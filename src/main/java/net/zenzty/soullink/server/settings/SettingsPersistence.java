package net.zenzty.soullink.server.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.Difficulty;
import net.zenzty.soullink.SoulLink;

/**
 * Handles loading and saving Soul Link settings to a JSON file in the world save directory.
 * Settings persist across server restarts. The file format is extensible: missing keys on load keep
 * current defaults; new settings can be added by extending the data class and load/save logic.
 */
public final class SettingsPersistence {

    private static final String FILENAME = "soullink_settings.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SettingsPersistence() {}

    /**
     * Loads settings from the world save. If the file is missing or invalid, settings keep their
     * in-memory defaults. Call after RunManager.init on SERVER_STARTED.
     */
    public static void load(MinecraftServer server) {
        Path path = getSettingsPath(server);
        if (!Files.isRegularFile(path)) {
            SoulLink.LOGGER.debug("No settings file at {}, using defaults", path);
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Object parsed = parseSettingsData(json);
            if (parsed instanceof SettingsData data) {
                applyToSettings(data);
                SoulLink.LOGGER.info("Loaded Soul Link settings from {}", path);
            }
        } catch (IOException e) {
            SoulLink.LOGGER.warn("Could not read settings file {}: {}", path, e.getMessage());
        } catch (Exception e) {
            SoulLink.LOGGER.warn("Could not parse settings file {}: {}", path, e.getMessage());
        }
    }

    /**
     * Saves current settings to the world save. Call when settings are changed (e.g. from /settings
     * or /chaos) or on SERVER_STOPPING as a safety net.
     */
    public static void save(MinecraftServer server) {
        Path path = getSettingsPath(server);
        try {
            SettingsData data = fromSettings();
            String json = GSON.toJson(data);
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
            SoulLink.LOGGER.debug("Saved Soul Link settings to {}", path);
        } catch (IOException e) {
            SoulLink.LOGGER.warn("Could not write settings file {}: {}", path, e.getMessage());
        }
    }

    private static Path getSettingsPath(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(FILENAME);
    }

    /**
     * Parses JSON into SettingsData. Returns Object so the result is not interpreted as @NonNull;
     * Gson.fromJson can return null (e.g. for JSON "null") and Gson has no null annotations. Caller
     * must use instanceof to check before using as SettingsData.
     */
    private static Object parseSettingsData(String json) {
        return GSON.fromJson(json, SettingsData.class);
    }

    private static void applyToSettings(SettingsData data) {
        Settings s = Settings.getInstance();
        if (data.damageLogEnabled != null) {
            s.setDamageLogEnabled(data.damageLogEnabled);
        }
        if (data.difficulty != null && !data.difficulty.isBlank()) {
            try {
                // Try to parse as RunDifficulty first (new format)
                RunDifficulty rd = RunDifficulty.valueOf(data.difficulty.toUpperCase());
                s.setDifficulty(rd);
            } catch (IllegalArgumentException e) {
                // Fallback: try to parse as vanilla Difficulty and convert (old format compatibility)
                try {
                    Difficulty d = Difficulty.valueOf(data.difficulty.toUpperCase());
                    RunDifficulty rd = RunDifficulty.fromVanilla(d);
                    s.setDifficulty(rd);
                } catch (IllegalArgumentException ignored) {
                    // keep default
                }
            }
        }
        if (data.halfHeartMode != null) {
            s.setHalfHeartMode(data.halfHeartMode);
        }
        if (data.sharedPotions != null) {
            s.setSharedPotions(data.sharedPotions);
        }
        if (data.sharedJumping != null) {
            s.setSharedJumping(data.sharedJumping);
        }
        if (data.manhuntMode != null) {
            s.setManhuntMode(data.manhuntMode);
        }
    }

    private static SettingsData fromSettings() {
        Settings s = Settings.getInstance();
        SettingsData data = new SettingsData();
        data.damageLogEnabled = s.isDamageLogEnabled();
        // Use pending chaos snapshot if one exists (user confirmed /chaos changes during a run;
        // those apply next run), otherwise use current applied values.
        Settings.SettingsSnapshot chaos = s.getPendingSnapshotOrNull();
        if (chaos == null) {
            chaos = s.createSnapshot();
        }
        data.difficulty = chaos.difficulty().name();
        data.halfHeartMode = chaos.halfHeartMode();
        data.sharedPotions = chaos.sharedPotions();
        data.sharedJumping = chaos.sharedJumping();
        data.manhuntMode = chaos.manhuntMode();
        return data;
    }

    /**
     * DTO for JSON. Use boxed types so we can omit null on save and detect missing keys on load.
     * When adding a new setting: add the field here, in applyToSettings, and in fromSettings.
     * Fields are read and written by Gson via reflection, so they appear unused to the compiler.
     */
    private static class SettingsData {
        Boolean damageLogEnabled;
        String difficulty;
        Boolean halfHeartMode;
        Boolean sharedPotions;
        Boolean sharedJumping;
        Boolean manhuntMode;
    }
}
