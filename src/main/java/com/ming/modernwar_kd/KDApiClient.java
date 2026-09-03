package com.ming.modernwar_kd;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class KDApiClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public record KDPayload(
            String game_id,
            String season,
            double kd,
            int kills,
            int deaths,
            int heads,
            int wins,
            int losses,
            String rank_label
    ) {}

    /**
     * POST player stats to the API asynchronously.
     */
    public static CompletableFuture<Boolean> uploadPlayer(String playerName, PlayerStats stats) {
        return uploadPlayer(playerName, Config.season, Config.rankLabel, stats);
    }

    public static CompletableFuture<Boolean> uploadPlayer(String playerName, String season,
                                                          String rankLabel, PlayerStats stats) {
        String url = Config.apiUrl;
        if (url == null || url.isBlank()) {
            LOGGER.error("ModernWar_KD: 尚未配置 API URL。");
            return CompletableFuture.completedFuture(false);
        }

        KDPayload payload = new KDPayload(
                playerName,
                season,
                stats.getApiKD(),
                stats.getKills(),
                stats.getDeaths(),
                stats.getHeads(),
                stats.getWins(),
                stats.getLosses(),
                rankLabel
        );

        String json = GSON.toJson(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int code = response.statusCode();
                    if (code >= 200 && code < 300) {
                        LOGGER.info("ModernWar_KD: 已上传 {} 的战绩 -> {} (HTTP {})",
                                playerName, url, code);
                        return true;
                    } else {
                        LOGGER.warn("ModernWar_KD: 上传失败 {} -> HTTP {}: {}",
                                playerName, code, response.body());
                        return false;
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.error("ModernWar_KD: 上传 {} 出错: {}", playerName, ex.getMessage());
                    return false;
                });
    }

    /**
     * Batch upload all player stats.
     */
    public static CompletableFuture<Integer> uploadAll() {
        var allStats = PlayerStatsManager.getAllStats();
        if (allStats.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        // We need player names; use the stats manager's UUID-keyed map.
        // The caller should provide a name resolver. For simplicity, we
        // store playerName alongside stats in a separate call path.
        // This is a placeholder — actual name resolution happens in KDCommand.
        return CompletableFuture.completedFuture(0);
    }
}
