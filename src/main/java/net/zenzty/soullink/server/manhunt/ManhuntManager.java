package net.zenzty.soullink.server.manhunt;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.zenzty.soullink.SoulLink;

/**
 * Manages player roles in Manhunt mode. Runners share health/stats (Soul Link mechanic). Hunters
 * operate with vanilla mechanics.
 */
public class ManhuntManager {

    private static volatile ManhuntManager instance;

    // Player roles
    private final Set<UUID> runners = new HashSet<>();
    private final Set<UUID> hunters = new HashSet<>();

    // Team names for scoreboard
    public static final String RUNNERS_TEAM = "soullink_runners";
    public static final String HUNTERS_TEAM = "soullink_hunters";

    private ManhuntManager() {}

    public static synchronized ManhuntManager getInstance() {
        if (instance == null) {
            instance = new ManhuntManager();
        }
        return instance;
    }

    /**
     * Resets all player roles. Called on server start and when a new run begins.
     */
    public void resetRoles() {
        runners.clear();
        hunters.clear();
        SoulLink.LOGGER.info("Manhunt roles reset");
    }

    /**
     * Sets a player as a Runner (shares health/stats).
     */
    public void setRunner(UUID playerId) {
        hunters.remove(playerId);
        runners.add(playerId);
        SoulLink.LOGGER.debug("Player {} set as Runner", playerId);
    }

    /**
     * Sets a player as a Hunter (vanilla mechanics).
     */
    public void setHunter(UUID playerId) {
        runners.remove(playerId);
        hunters.add(playerId);
        SoulLink.LOGGER.debug("Player {} set as Hunter", playerId);
    }

    /**
     * Toggles player role and returns the new role (true = runner, false = hunter).
     */
    public boolean toggleRole(UUID playerId) {
        if (runners.contains(playerId)) {
            setHunter(playerId);
            return false;
        } else {
            setRunner(playerId);
            return true;
        }
    }

    /**
     * Checks if a player is a Runner (participates in Soul Link).
     */
    public boolean isSpeedrunner(ServerPlayerEntity player) {
        return player != null && runners.contains(player.getUuid());
    }

    /**
     * Checks if a player is a Runner by UUID.
     */
    public boolean isSpeedrunner(UUID playerId) {
        return playerId != null && runners.contains(playerId);
    }

    /**
     * Checks if a player is a Hunter (vanilla mechanics).
     */
    public boolean isHunter(ServerPlayerEntity player) {
        return player != null && hunters.contains(player.getUuid());
    }

    /**
     * Checks if a player is a Hunter by UUID.
     */
    public boolean isHunter(UUID playerId) {
        return playerId != null && hunters.contains(playerId);
    }

    /**
     * Gets all Runner UUIDs.
     */
    public Set<UUID> getRunners() {
        return new HashSet<>(runners);
    }

    /**
     * Gets all Hunter UUIDs.
     */
    public Set<UUID> getHunters() {
        return new HashSet<>(hunters);
    }

    /**
     * Checks if any runners have been assigned.
     */
    public boolean hasRunners() {
        return !runners.isEmpty();
    }

    /**
     * Creates scoreboard teams for Runners and Hunters. Teams provide colored name tags and
     * prefixes.
     */
    public void createTeams(MinecraftServer server) {
        if (server == null)
            return;

        Scoreboard scoreboard = server.getScoreboard();

        // Create or get Runners team
        Team runnersTeam = scoreboard.getTeam(RUNNERS_TEAM);
        if (runnersTeam == null) {
            runnersTeam = scoreboard.addTeam(RUNNERS_TEAM);
        }
        runnersTeam.setDisplayName(Text.literal("Runners"));
        runnersTeam.setColor(Formatting.WHITE);
        runnersTeam.setPrefix(Text.empty()
                .append(Text.literal("Runner").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY)));

        // Create or get Hunters team
        Team huntersTeam = scoreboard.getTeam(HUNTERS_TEAM);
        if (huntersTeam == null) {
            huntersTeam = scoreboard.addTeam(HUNTERS_TEAM);
        }
        huntersTeam.setDisplayName(Text.literal("Hunters"));
        huntersTeam.setColor(Formatting.WHITE);
        huntersTeam.setPrefix(Text.empty()
                .append(Text.literal("Hunter").formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY)));

        SoulLink.LOGGER.info("Manhunt teams created/updated");
    }

    /**
     * Assigns all players to their respective scoreboard teams.
     */
    public void assignPlayersToTeams(MinecraftServer server) {
        if (server == null)
            return;

        Scoreboard scoreboard = server.getScoreboard();
        Team runnersTeam = scoreboard.getTeam(RUNNERS_TEAM);
        Team huntersTeam = scoreboard.getTeam(HUNTERS_TEAM);

        if (runnersTeam == null || huntersTeam == null) {
            createTeams(server);
            runnersTeam = scoreboard.getTeam(RUNNERS_TEAM);
            huntersTeam = scoreboard.getTeam(HUNTERS_TEAM);
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String playerName = player.getGameProfile().name();

            // Remove from any existing team first
            Team currentTeam = scoreboard.getScoreHolderTeam(playerName);
            if (currentTeam != null) {
                scoreboard.removeScoreHolderFromTeam(playerName, currentTeam);
            }

            // Add to appropriate team
            if (runners.contains(player.getUuid())) {
                scoreboard.addScoreHolderToTeam(playerName, runnersTeam);
            } else if (hunters.contains(player.getUuid())) {
                scoreboard.addScoreHolderToTeam(playerName, huntersTeam);
            }
        }

        SoulLink.LOGGER.info("Players assigned to teams: {} runners, {} hunters", runners.size(),
                hunters.size());
    }

    /**
     * Cleans up teams when run ends.
     */
    public void cleanupTeams(MinecraftServer server) {
        if (server == null)
            return;

        Scoreboard scoreboard = server.getScoreboard();

        // Remove all players from teams
        Team runnersTeam = scoreboard.getTeam(RUNNERS_TEAM);
        Team huntersTeam = scoreboard.getTeam(HUNTERS_TEAM);

        if (runnersTeam != null) {
            for (String member : new HashSet<>(runnersTeam.getPlayerList())) {
                scoreboard.removeScoreHolderFromTeam(member, runnersTeam);
            }
        }

        if (huntersTeam != null) {
            for (String member : new HashSet<>(huntersTeam.getPlayerList())) {
                scoreboard.removeScoreHolderFromTeam(member, huntersTeam);
            }
        }
    }
}
