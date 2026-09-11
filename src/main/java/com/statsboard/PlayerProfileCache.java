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
 * Remembers each player's name and Mojang skin texture so the leaderboard can
 * render them while they are offline. Persisted as "statsboard.json" in the
 * world root.
 *
 * <p>This used to also hold the mod's own deaths and advancements counters.
 * Those are gone - vanilla's minecraft:deaths and the advancement files are the
 * source of truth now - but the file name and shape are otherwise unchanged.
 * GSON ignores JSON members with no matching field, so an old file still loads
 * and its stale counters are simply dropped. No migration step is needed.
 */
public class PlayerProfileCache {
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

    public static boolean isDirty() {
        return dirty;
    }

    /** Saves the player's Mojang textures property so their skin renders while offline. */
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

        PlayerStats stats = STATS.computeIfAbsent(uuid, u -> new PlayerStats());
        String signature = textures.hasSignature() ? textures.getSignature() : null;
        if (!textures.getValue().equals(stats.skinTextureValue)
                || (signature == null ? stats.skinTextureSignature != null
                                      : !signature.equals(stats.skinTextureSignature))) {
            stats.skinTextureValue = textures.getValue();
            stats.skinTextureSignature = signature;
            dirty = true;
        }
    }

    /** Null when this player has never joined since the mod was installed. */
    public static PlayerStats skinOf(UUID uuid) {
        return STATS.get(uuid);
    }

    /** Null rather than a placeholder, so callers can fall through to other name sources. */
    public static String nameOf(UUID uuid) {
        return NAMES.get(uuid);
    }

    private static class StatsData {
        List<StatsEntry> players;
    }

    private static class StatsEntry {
        String uuid;
        String name;
        String skinTextureValue;
        String skinTextureSignature;
    }
}
