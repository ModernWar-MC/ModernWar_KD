package com.ming.modernwar_kd;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MatchManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean matchActive = false;
    private static long matchStartTime = 0;
    private static String matchId = "";
    private static String matchOperator = "";

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
        startMatch(server, Config.operatorName);
    }

    /**
     * Start a new match. Snapshots all currently online players and balances
     * teams evenly by online player count (first half -> A, rest -> B; odd
     * count gives A the extra player). Players holding the match-view
     * permission (LuckPerms) receive the match-info broadcast.
     */
    public static void startMatch(MinecraftServer server, String operatorName) {
        if (matchActive) {
            LOGGER.warn("ModernWar_KD: 对局已在进行中，正在重新开始。");
        }

        matchActive = true;
        matchStartTime = System.currentTimeMillis();
        matchId = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmm").format(new Date(matchStartTime));
        matchOperator = operatorName == null || operatorName.isBlank() ? Config.operatorName : operatorName;
        TEAM_ASSIGNMENTS.clear();
        SNAPSHOTS.clear();

        // Balance teams evenly by online player count:
        // sort by name for a deterministic split, first half -> A, second half -> B.
        // Odd player count: A gets the extra player.
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        players.sort(Comparator.comparing(ServerPlayer::getScoreboardName));
        int teamASize = (players.size() + 1) / 2;

        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            UUID uuid = player.getUUID();
            String name = player.getScoreboardName();
            PlayerStats stats = PlayerStatsManager.getStats(uuid);
            SNAPSHOTS.put(uuid, new PlayerSnapshot(
                    name,
                    stats.getKills(), stats.getAssists(), stats.getDeaths(),
                    stats.getHeads(), stats.getWins(), stats.getLosses()
            ));
            TEAM_ASSIGNMENTS.put(uuid, i < teamASize ? "a" : "b");
        }

        long aCount = TEAM_ASSIGNMENTS.values().stream().filter("a"::equals).count();
        long bCount = TEAM_ASSIGNMENTS.values().stream().filter("b"::equals).count();
        LOGGER.info("ModernWar_KD: 对局已开始。已快照 {} 名玩家，A 队 {} 人 / B 队 {} 人。",
                SNAPSHOTS.size(), aCount, bCount);

        announceMatchStart(server);
    }

    /**
     * Broadcast the match-info block to every player that holds the
     * LuckPerms view permission:
     *
     * 【地图名称】
     * 队伍A：
     *   player1
     * 队伍B：
     *   player2
     * 游戏开始 开始时间码：<yyyyMMdd'T'HHmm>_<操作者ID>_<地图名称>
     * 游戏开始
     */
    private static void announceMatchStart(MinecraftServer server) {
        String mapName = Config.mapName;
        String code = matchId + "_" + matchOperator + "_" + mapName;

        List<String> teamA = new ArrayList<>();
        List<String> teamB = new ArrayList<>();
        for (Map.Entry<UUID, PlayerSnapshot> entry : SNAPSHOTS.entrySet()) {
            if ("b".equals(TEAM_ASSIGNMENTS.getOrDefault(entry.getKey(), "a"))) {
                teamB.add(entry.getValue().playerName());
            } else {
                teamA.add(entry.getValue().playerName());
            }
        }
        teamA.sort(String::compareTo);
        teamB.sort(String::compareTo);

        StringBuilder sb = new StringBuilder();
        sb.append("\u00a76【").append(mapName).append("】");
        sb.append("\n\u00a7e队伍A：");
        if (teamA.isEmpty()) sb.append("\n\u00a77  (无)");
        for (String name : teamA) sb.append("\n\u00a7b  ").append(name);
        sb.append("\n\u00a7e队伍B：");
        if (teamB.isEmpty()) sb.append("\n\u00a77  (无)");
        for (String name : teamB) sb.append("\n\u00a7b  ").append(name);
        sb.append("\n\u00a7e游戏开始 开始时间码：\u00a7f").append(code);
        sb.append("\n\u00a7b游戏开始");

        LOGGER.info("ModernWar_KD: 对局公告（仅权限组可见）:\n{}", sb);

        Component message = Component.literal(sb.toString());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (LuckPermsHelper.canViewMatchInfo(player)) {
                player.sendSystemMessage(message);
                LOGGER.debug("ModernWar_KD: 已向 {} 发送对局公告。", player.getScoreboardName());
            }
        }
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
        String matchMode = "对战";

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
            boolean won = team.equals(winner);
            if (won) {
                current.addWins(1);
            } else {
                current.addLosses(1);
            }

            // log match result
            LOGGER.info("ModernWar_KD: [对局] {} ({} 队) - 击杀:{}/死亡:{}/助攻:{}/爆头:{} {}",
                    snap.playerName(), team, matchKills, matchDeaths, matchAssists, matchHeads,
                    won ? "胜" : "负");

            // send match summary to the player if still online
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal(
                        "\u00a76\u00a7l========== 对局信息 " + matchId + " =========="));
                player.sendSystemMessage(Component.literal(
                        String.format("\u00a77模式: \u00a7e%s \u00a77| 队伍: \u00a7b%s 队",
                                matchMode, team.toUpperCase())));
                player.sendSystemMessage(Component.literal(
                        String.format("\u00a7e击杀: \u00a7c%d \u00a77/ \u00a7e死亡: \u00a7c%d \u00a77/ \u00a7e助攻: \u00a7a%d \u00a77/ \u00a7e爆头: \u00a7e%d",
                                matchKills, matchDeaths, matchAssists, matchHeads)));
                player.sendSystemMessage(Component.literal(
                        won ? "\u00a7a本局获胜 (胜)" : "\u00a7c本局失利 (负)"));
            }

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
