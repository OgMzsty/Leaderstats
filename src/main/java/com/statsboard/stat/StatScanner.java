package com.statsboard.stat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.advancement.Advancement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Reads every player's stats and advancements off disk on a background thread
 * and publishes the result as an immutable {@link StatSnapshot}.
 *
 * <p><b>How this stays thread-safe.</b> Scans are kicked off from the server
 * tick, not from a timer, so everything the scan needs from live server state -
 * the set of online players, the countable advancement ids - is captured on the
 * server thread and handed to the job as immutable data. The job itself touches
 * nothing but the filesystem and its own caches. In particular it parses the
 * stats JSON directly rather than constructing vanilla ServerStatHandler
 * objects, because those call StatType#getOrCreateStat, which mutates a global
 * unsynchronized IdentityHashMap that the server thread writes to.
 *
 * <p>The cost of hand-parsing is losing vanilla's DataFixer pass, so a stats
 * file last written by a pre-1.13 client reads as zero. Accepted: this targets
 * 1.20.1 worlds, and vanilla repairs such a file the moment that player logs in.
 */
public final class StatScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("statsboard");
    private static final int SCAN_INTERVAL_TICKS = 20 * 60;

    private final MinecraftServer server;
    private final Path statsDir;
    private final Path advancementsDir;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "statsboard-scanner"));

    private volatile StatSnapshot snapshot = StatSnapshot.EMPTY;

    /** Rebuilt on the server thread; the two forms have different consumers. */
    private volatile Set<Identifier> countableIds = Set.of();
    private volatile List<Advancement> countableAdvancements = List.of();

    private final AtomicBoolean scanInFlight = new AtomicBoolean(false);
    private int tickCounter = 0;

    // Scanner-thread-only working state, carried across scans so an unchanged
    // file is never re-read.
    private final Map<UUID, CachedStats> statCache = new HashMap<>();
    private final Map<UUID, CachedCount> advancementCache = new HashMap<>();

    /** The scanner for the running server, or null between worlds. */
    private static volatile StatScanner instance;

    public static StatScanner instance() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        StatScanner scanner = new StatScanner(server);
        scanner.refreshCountableAdvancements();
        instance = scanner;
    }

    public static void stop() {
        StatScanner scanner = instance;
        instance = null;
        if (scanner != null) {
            scanner.shutdown();
        }
    }

    public StatScanner(MinecraftServer server) {
        this.server = server;
        this.statsDir = server.getSavePath(WorldSavePath.STATS);
        this.advancementsDir = server.getSavePath(WorldSavePath.ADVANCEMENTS);
    }

    public StatSnapshot snapshot() {
        return snapshot;
    }

    /** ~110 display-bearing advancements; the online counting path iterates these. */
    public List<Advancement> countableAdvancements() {
        return countableAdvancements;
    }

    /**
     * Server thread only. ServerAdvancementLoader swaps its internal manager
     * during /reload with no synchronization, so this must never be read from
     * the scanner thread.
     */
    public void refreshCountableAdvancements() {
        List<Advancement> all = new ArrayList<>();
        for (Advancement advancement : server.getAdvancementLoader().getAdvancements()) {
            // Recipe advancements have no display block - 1161 of them in 1.20.1
            // against 110 real ones - so this filter is what keeps counts sane.
            if (advancement.getDisplay() != null) {
                all.add(advancement);
            }
        }
        this.countableAdvancements = List.copyOf(all);
        this.countableIds = all.stream().map(Advancement::getId).collect(Collectors.toUnmodifiableSet());
    }

    /** Called from END_SERVER_TICK. Captures live state here, scans elsewhere. */
    public void tick() {
        if (++tickCounter < SCAN_INTERVAL_TICKS && snapshot != StatSnapshot.EMPTY) {
            return;
        }
        tickCounter = 0;

        if (!scanInFlight.compareAndSet(false, true)) {
            return;
        }

        Set<UUID> online = server.getPlayerManager().getPlayerList().stream()
                .map(player -> player.getUuid())
                .collect(Collectors.toUnmodifiableSet());
        Set<Identifier> countable = countableIds;

        executor.execute(() -> {
            try {
                snapshot = scan(online, countable);
            } catch (Exception e) {
                LOGGER.warn("[statsboard] stat scan failed", e);
            } finally {
                scanInFlight.set(false);
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[statsboard] scanner did not stop within 5s; abandoning it");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private StatSnapshot scan(Set<UUID> online, Set<Identifier> countable) {
        Map<UUID, Object2IntMap<StatKey>> values = new HashMap<>();
        Map<UUID, Integer> advancementCounts = new HashMap<>();
        Set<UUID> known = new HashSet<>();

        for (Map.Entry<UUID, Path> entry : listPlayerFiles(statsDir).entrySet()) {
            UUID uuid = entry.getKey();
            known.add(uuid);
            CachedStats cached = statCache.get(uuid);
            // An online player's file churns on every autosave and StatQuery
            // overrides it with live values, so re-reading it is waste - but the
            // previous parse still has to be republished. Dropping it would make
            // the player vanish from every board the instant they log off, which
            // is worst for whoever just took the top spot.
            if (online.contains(uuid) && cached != null) {
                values.put(uuid, cached.values);
                continue;
            }
            long modified = lastModified(entry.getValue());
            if (cached == null || cached.modified != modified) {
                Object2IntMap<StatKey> parsed = parseStats(entry.getValue());
                if (parsed == null) {
                    continue;
                }
                cached = new CachedStats(modified, parsed);
                statCache.put(uuid, cached);
            }
            values.put(uuid, cached.values);
        }

        for (Map.Entry<UUID, Path> entry : listPlayerFiles(advancementsDir).entrySet()) {
            UUID uuid = entry.getKey();
            known.add(uuid);
            CachedCount cached = advancementCache.get(uuid);
            if (online.contains(uuid) && cached != null) {
                advancementCounts.put(uuid, cached.count);
                continue;
            }
            long modified = lastModified(entry.getValue());
            // Keyed on the countable set's contents, not its size: a datapack
            // reload that swaps one advancement for another leaves the size
            // identical, and an offline player's mtime never changes again.
            if (cached == null || cached.modified != modified || cached.countableHash != countable.hashCode()) {
                int count = countAdvancements(entry.getValue(), countable);
                if (count < 0) {
                    continue;
                }
                cached = new CachedCount(modified, countable.hashCode(), count);
                advancementCache.put(uuid, cached);
            }
            advancementCounts.put(uuid, cached.count);
        }

        statCache.keySet().retainAll(known);
        advancementCache.keySet().retainAll(known);

        return new StatSnapshot(Map.copyOf(values), Map.copyOf(advancementCounts), Set.copyOf(known));
    }

    private Map<UUID, Path> listPlayerFiles(Path dir) {
        Map<UUID, Path> files = new HashMap<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (var stream = Files.list(dir)) {
            stream.forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.endsWith(".json")) {
                    return;
                }
                try {
                    files.put(UUID.fromString(name.substring(0, name.length() - 5)), path);
                } catch (IllegalArgumentException ignored) {
                    // Not a player file; vanilla puts nothing else here, but mods might.
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[statsboard] could not list {}", dir, e);
        }
        return files;
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /** Null on failure, so the caller keeps whatever it had rather than zeroing a player. */
    private Object2IntMap<StatKey> parseStats(Path path) {
        JsonObject root = readJson(path);
        if (root == null) {
            return null;
        }

        Object2IntMap<StatKey> values = new Object2IntOpenHashMap<>();
        values.defaultReturnValue(0);

        JsonElement statsElement = root.get("stats");
        if (statsElement == null || !statsElement.isJsonObject()) {
            // A brand new player file has no "stats" member; that is not an error.
            return values;
        }

        for (Map.Entry<String, JsonElement> typeEntry : statsElement.getAsJsonObject().entrySet()) {
            Identifier typeId = Identifier.tryParse(typeEntry.getKey());
            if (typeId == null || !typeEntry.getValue().isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> valueEntry : typeEntry.getValue().getAsJsonObject().entrySet()) {
                Identifier valueId = Identifier.tryParse(valueEntry.getKey());
                if (valueId == null || !valueEntry.getValue().isJsonPrimitive()
                        || !valueEntry.getValue().getAsJsonPrimitive().isNumber()) {
                    continue;
                }
                values.put(new StatKey(typeId, valueId), valueEntry.getValue().getAsInt());
            }
        }
        return values;
    }

    /** -1 on failure. Counts entries marked done whose id is a real, displayable advancement. */
    private int countAdvancements(Path path, Set<Identifier> countable) {
        JsonObject root = readJson(path);
        if (root == null) {
            return -1;
        }

        int count = 0;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            // Skips the top-level "DataVersion" integer for free.
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id == null || !countable.contains(id)) {
                continue;
            }
            JsonElement done = entry.getValue().getAsJsonObject().get("done");
            if (done != null && done.isJsonPrimitive() && done.getAsBoolean()) {
                count++;
            }
        }
        return count;
    }

    private JsonObject readJson(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            LOGGER.warn("[statsboard] skipping unreadable file {}: {}", path.getFileName(), e.toString());
            return null;
        }
    }

    private record CachedStats(long modified, Object2IntMap<StatKey> values) {
    }

    private record CachedCount(long modified, int countableHash, int count) {
    }
}
