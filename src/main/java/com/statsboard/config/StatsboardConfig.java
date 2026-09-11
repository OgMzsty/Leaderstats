package com.statsboard.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side settings, stored as "config/statsboard.json". Written with its
 * defaults on first run so the file is discoverable rather than something the
 * player has to know to create.
 */
public class StatsboardConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("statsboard");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * How often the open leaderboard screen re-asks the server for the stat it
     * is showing. Zero or less disables refreshing, leaving the board frozen at
     * whatever it showed when opened.
     */
    public int guiRefreshSeconds = 10;

    private static StatsboardConfig instance;

    public static StatsboardConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** Milliseconds between refreshes, or 0 when refreshing is disabled. */
    public long refreshIntervalMs() {
        return guiRefreshSeconds <= 0 ? 0 : guiRefreshSeconds * 1000L;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("statsboard.json");
    }

    private static StatsboardConfig load() {
        Path path = path();
        if (!Files.exists(path)) {
            StatsboardConfig fresh = new StatsboardConfig();
            fresh.save();
            return fresh;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StatsboardConfig loaded = GSON.fromJson(reader, StatsboardConfig.class);
            return loaded == null ? new StatsboardConfig() : loaded;
        } catch (Exception e) {
            // A hand-edited file with a syntax error should not stop the GUI
            // from opening; fall back to defaults and say so once.
            LOGGER.warn("[statsboard] could not read {}, using defaults: {}", path, e.toString());
            return new StatsboardConfig();
        }
    }

    public void save() {
        Path path = path();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            LOGGER.warn("[statsboard] could not write {}: {}", path, e.toString());
        }
    }
}
