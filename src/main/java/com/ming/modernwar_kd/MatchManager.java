package com.ming.modernwar_kd;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MatchManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean matchActive = false;
    private static long matchStartTime = 0;

    // team assignments: UUID -> "a" or "b"
    private static final Map<UUID, String> TEAM_ASSIGNMENTS = new ConcurrentHashMap<>();

    // snapshot of each player's stats at match start
    private static final Map<UUID, PlayerSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    public record PlayerSnapshot(
            String playerName,
            int kills,
            int assists,
            int deaths,
            int heads,
            int wins,
            int losses
    ) {}

    // ---- lifecycle ----

    public static boolean isMatchActive() {
        return matchActive;
    }

    /**
     * Start a new match. Snapshots all currently online players.
     * Default: everyone is on team "a" unless assigned.
     */
    public static void startMatch(MinecraftServer server) {
        if (matchActive) {
            LOGGER.warn("ModernWar_KD: 对局已在进行中，正在重新开始。");
        }

        matchActive = true;
        matchStartTime = System.currentTimeMillis();
        TEAM_ASSIGNMENTS.clear();
        SNAPSHOTS.clear();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            String name = player.getScoreboardName();
            PlayerStats stats = PlayerStatsManager.getStats(uuid);
            SNAPSHOTS.put(uuid, new PlayerSnapshot(
                    name,
                    stats.getKills(), stats.getAssists(), stats.getDeaths(),
                    stats.getHeads(), stats.getWins(), stats.getLosses()
            ));
            TEAM_ASSIGNMENTS.putIfAbsent(uuid, "a");
        }

        LOGGER.info("ModernWar_KD: 对局已开始。已快照 {} 名玩家。", SNAPSHOTS.size());
    }

    /**
     * Assign a player to a team.
     */
    public static boolean assignTeam(UUID playerUUID, String team) {
        if (!matchActive) return false;
        String t = team.toLowerCase();
        if (!t.equals("a") && !t.equals("b")) return false;
        TEAM_ASSIGNMENTS.put(playerUUID, t);
        return true;
    }

    /**
     * End the match. Updates wins/losses for all snapshotted players,
     * uploads their cumulative stats to API, then clears match state.
     */
    public static int endMatch(String winningTeam, MinecraftServer server) {
        if (!matchActive) return 0;

        String winner = winningTeam.toLowerCase();
        long duration = System.currentTimeMillis() - matchStartTime;
        int uploaded = 0;

        for (Map.Entry<UUID, PlayerSnapshot> entry : SNAPSHOTS.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerSnapshot snap = entry.getValue();
            PlayerStats current = PlayerStatsManager.getStats(uuid);

            // compute match deltas
            int matchKills = current.getKills() - snap.kills();
            int matchDeaths = current.getDeaths() - snap.deaths();
            int matchAssists = current.getAssists() - snap.assists();
            int matchHeads = current.getHeads() - snap.heads();

            // assign win/loss based on team
            String team = TEAM_ASSIGNMENTS.getOrDefault(uuid, "a");
            if (team.equals(winner)) {
                current.addWins(1);
            } else {
                current.addLosses(1);
            }

            // log match result
            LOGGER.info("ModernWar_KD: [对局] {} ({} 队) - 击杀:{}/死亡:{}/助攻:{}/爆头:{} {}",
                    snap.playerName(), team, matchKills, matchDeaths, matchAssists, matchHeads,
                    team.equals(winner) ? "胜" : "负");

            // upload to API
            KDApiClient.uploadPlayer(snap.playerName(), current);
            uploaded++;
        }

        matchActive = false;
        TEAM_ASSIGNMENTS.clear();
        SNAPSHOTS.clear();

        LOGGER.info("ModernWar_KD: 对局已结束。胜方: {} 队。时长: {}ms。已上传: {} 名玩家。",
                winner, duration, uploaded);
        return uploaded;
    }

    // ---- queries ----

    public static String getTeam(UUID playerUUID) {
        return TEAM_ASSIGNMENTS.getOrDefault(playerUUID, "none");
    }

    public static PlayerSnapshot getSnapshot(UUID playerUUID) {
        return SNAPSHOTS.get(playerUUID);
    }

    public static Set<UUID> getMatchPlayers() {
        return Collections.unmodifiableSet(SNAPSHOTS.keySet());
    }

    public static long getMatchDuration() {
        if (!matchActive) return 0;
        return System.currentTimeMillis() - matchStartTime;
    }

    public static int addPlayerToMatch(ServerPlayer player, String team) {
        if (!matchActive) return -1;
        String t = team.toLowerCase();
        if (!t.equals("a") && !t.equals("b")) return -1;

        UUID uuid = player.getUUID();
        if (SNAPSHOTS.containsKey(uuid)) return 0; // already in match

        PlayerStats stats = PlayerStatsManager.getStats(uuid);
        SNAPSHOTS.put(uuid, new PlayerSnapshot(
                player.getScoreboardName(),
                stats.getKills(), stats.getAssists(), stats.getDeaths(),
                stats.getHeads(), stats.getWins(), stats.getLosses()
        ));
        TEAM_ASSIGNMENTS.put(uuid, t);
        return 1;
    }
}
