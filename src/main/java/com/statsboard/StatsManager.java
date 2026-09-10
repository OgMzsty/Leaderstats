package com.statsboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Holds per-player statistics in memory and persists them as
 * "statsboard.json" inside the world save folder, so stats survive
 * server restarts and travel with the world.
 */
public class StatsManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, PlayerStats> STATS = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();
    private static Path filePath;
    private static boolean dirty = false;

    public static void init(MinecraftServer server) {
        filePath = server.getSavePath(WorldSavePath.ROOT).resolve("statsboard.json");
        load();
    }

    public static void load() {
        STATS.clear();
        NAMES.clear();
        if (filePath == null || !Files.exists(filePath)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            StatsData data = GSON.fromJson(reader, StatsData.class);
            if (data != null && data.players != null) {
                for (StatsEntry entry : data.players) {
                    UUID uuid = UUID.fromString(entry.uuid);
                    PlayerStats stats = new PlayerStats();
                    stats.deaths = entry.deaths;
                    stats.advancements = entry.advancements;
                    stats.skinTextureValue = entry.skinTextureValue;
                    stats.skinTextureSignature = entry.skinTextureSignature;
                    STATS.put(uuid, stats);
                    NAMES.put(uuid, entry.name);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void save() {
        if (filePath == null) {
            return;
        }
        StatsData data = new StatsData();
        data.players = STATS.entrySet().stream().map(e -> {
            StatsEntry entry = new StatsEntry();
            entry.uuid = e.getKey().toString();
            entry.name = NAMES.getOrDefault(e.getKey(), "Unknown");
            entry.deaths = e.getValue().deaths;
            entry.advancements = e.getValue().advancements;
            entry.skinTextureValue = e.getValue().skinTextureValue;
            entry.skinTextureSignature = e.getValue().skinTextureSignature;
            return entry;
        }).collect(Collectors.toList());

        try {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        dirty = false;
    }

    public static void markDirty() {
        dirty = true;
    }

    public static boolean isDirty() {
        return dirty;
    }

    private static PlayerStats getOrCreate(UUID uuid) {
        return STATS.computeIfAbsent(uuid, u -> new PlayerStats());
    }

    public static void recordDeath(UUID uuid, String name) {
        NAMES.put(uuid, name);
        getOrCreate(uuid).deaths++;
        markDirty();
    }

    public static void recordAdvancement(UUID uuid, String name) {
        NAMES.put(uuid, name);
        getOrCreate(uuid).advancements++;
        markDirty();
    }

    /** Saves the player's Mojang textures property so their skin can be rendered even while offline. */
    public static void recordPlayerProfile(GameProfile profile) {
        if (profile == null || profile.getId() == null) {
            return;
        }

        UUID uuid = profile.getId();
        NAMES.put(uuid, profile.getName());

        Property textures = profile.getProperties().get("textures").stream().findFirst().orElse(null);
        if (textures == null || textures.getValue() == null || textures.getValue().isEmpty()) {
            return;
        }

        PlayerStats stats = getOrCreate(uuid);
        String signature = textures.hasSignature() ? textures.getSignature() : null;
        if (!textures.getValue().equals(stats.skinTextureValue)
                || (signature == null ? stats.skinTextureSignature != null
                                      : !signature.equals(stats.skinTextureSignature))) {
            stats.skinTextureValue = textures.getValue();
            stats.skinTextureSignature = signature;
            markDirty();
        }
    }

    public static List<Map.Entry<UUID, PlayerStats>> topDeaths(int limit) {
        return STATS.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().deaths, a.getValue().deaths))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public static List<Map.Entry<UUID, PlayerStats>> topAdvancements(int limit) {
        return STATS.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().advancements, a.getValue().advancements))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public static String nameOf(UUID uuid) {
        return NAMES.getOrDefault(uuid, uuid.toString().substring(0, 8));
    }

    private static class StatsData {
        List<StatsEntry> players;
    }

    private static class StatsEntry {
        String uuid;
        String name;
        int deaths;
        int advancements;
        String skinTextureValue;
        String skinTextureSignature;
    }
}
