package com.ming.modernwar_kd;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStatsManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, PlayerStats> STATS = new ConcurrentHashMap<>();

    public static PlayerStats getStats(UUID playerUUID) {
        return STATS.computeIfAbsent(playerUUID, k -> new PlayerStats());
    }

    public static void addKill(UUID killerUUID) {
        getStats(killerUUID).addKill();
    }

    public static void addAssist(UUID assisterUUID) {
        getStats(assisterUUID).addAssist();
    }

    public static void addDeath(UUID deadUUID) {
        getStats(deadUUID).addDeath();
    }

    public static Map<UUID, PlayerStats> getAllStats() {
        return Collections.unmodifiableMap(STATS);
    }

    public static List<Map.Entry<UUID, PlayerStats>> getTopKD(int limit) {
        return STATS.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().getKD(), a.getValue().getKD()))
                .limit(limit)
                .toList();
    }

    public static void save(MinecraftServer server) {
        Path dataFile = server.getWorldPath(LevelResource.ROOT).resolve("modernwar_kd_stats.json");
        try {
            Files.createDirectories(dataFile.getParent());
            try (Writer writer = Files.newBufferedWriter(dataFile)) {
                GSON.toJson(STATS, writer);
            }
            LOGGER.info("ModernWar_KD: Saved stats for {} players.", STATS.size());
        } catch (IOException e) {
            LOGGER.error("ModernWar_KD: Failed to save stats", e);
        }
    }

    public static void load(MinecraftServer server) {
        Path dataFile = server.getWorldPath(LevelResource.ROOT).resolve("modernwar_kd_stats.json");
        STATS.clear();
        if (Files.exists(dataFile)) {
            try (Reader reader = Files.newBufferedReader(dataFile)) {
                Type type = new TypeToken<Map<UUID, PlayerStats>>() {}.getType();
                Map<UUID, PlayerStats> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    STATS.putAll(loaded);
                }
                LOGGER.info("ModernWar_KD: Loaded stats for {} players.", STATS.size());
            } catch (IOException e) {
                LOGGER.error("ModernWar_KD: Failed to load stats", e);
            }
        }
    }
}
