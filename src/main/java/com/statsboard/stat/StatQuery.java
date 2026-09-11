package com.statsboard.stat;

import com.mojang.authlib.GameProfile;
import com.statsboard.LeaderboardEntry;
import com.statsboard.PlayerProfileCache;
import com.statsboard.PlayerStats;
import net.minecraft.advancement.Advancement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds a leaderboard for one {@link StatKey}. Server thread only - it calls
 * {@link StatKey#resolve()}, which mutates a global StatType map.
 */
public final class StatQuery {
    /** Nothing reads past this, so there is no point sorting or naming further. */
    public static final int MAX_LIMIT = 50;

    /**
     * Sorted values per stat, memoised for the duration of one server tick.
     * A single query is O(players), but it is invoked once per column per
     * leaderboard block, so a handful of boards showing the same stat would
     * otherwise repeat the same full sort several times on the same tick.
     * Names are deliberately not cached here - see topFor.
     */
    private static final Map<StatKey, List<Ranked>> MEMO = new HashMap<>();

    private StatQuery() {
    }

    /** Called at the end of every server tick; nothing is cached across ticks. */
    public static void clearMemo() {
        MEMO.clear();
    }

    public static List<LeaderboardEntry> topFor(MinecraftServer server, StatKey key, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Ranked> ranked = MEMO.computeIfAbsent(key, k -> rank(server, k));

        List<LeaderboardEntry> entries = new ArrayList<>(Math.min(capped, ranked.size()));
        for (int i = 0; i < ranked.size() && i < capped; i++) {
            Ranked row = ranked.get(i);
            PlayerStats skin = PlayerProfileCache.skinOf(row.uuid);
            entries.add(new LeaderboardEntry(
                    row.uuid,
                    nameOf(server, row.uuid),
                    row.value,
                    skin == null ? null : skin.skinTextureValue,
                    skin == null ? null : skin.skinTextureSignature));
        }
        return entries;
    }

    private static List<Ranked> rank(MinecraftServer server, StatKey key) {
        StatScanner scanner = StatScanner.instance();
        if (scanner == null) {
            return List.of();
        }
        StatSnapshot snapshot = scanner.snapshot();

        // Resolved once, not per player. Empty for the advancement pseudo-stat
        // and for a stat whose mod has been removed.
        Optional<Stat<?>> resolved = key.resolve();
        if (resolved.isEmpty() && !key.isAdvancements()) {
            return List.of();
        }
        Stat<?> stat = resolved.orElse(null);

        List<Advancement> countable = scanner.countableAdvancements();

        // Online players' on-disk values are stale by construction - vanilla
        // only flushes every few minutes - so they are read live instead.
        Map<UUID, ServerPlayerEntity> online = new HashMap<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            online.put(player.getUuid(), player);
        }

        List<Ranked> ranked = new ArrayList<>();
        for (UUID uuid : union(snapshot.knownPlayers(), online.keySet())) {
            ServerPlayerEntity player = online.get(uuid);
            int value;
            if (key.isAdvancements()) {
                value = player != null
                        ? countDone(player, countable)
                        : snapshot.advancementsOf(uuid);
            } else {
                value = player != null
                        ? player.getStatHandler().getStat(stat)
                        : snapshot.valueOf(uuid, key);
            }
            if (value > 0) {
                ranked.add(new Ranked(uuid, value));
            }
        }

        ranked.sort((a, b) -> Integer.compare(b.value, a.value));
        return ranked.size() > MAX_LIMIT ? List.copyOf(ranked.subList(0, MAX_LIMIT)) : List.copyOf(ranked);
    }

    /**
     * Iterates only the ~110 display-bearing advancements, never all 1271.
     * PlayerAdvancementTracker#getProgress is not a pure read - it inserts a
     * fresh AdvancementProgress for anything missing - so walking the full set
     * every refresh would permanently balloon each player's progress map.
     */
    private static int countDone(ServerPlayerEntity player, List<Advancement> countable) {
        int count = 0;
        for (Advancement advancement : countable) {
            if (player.getAdvancementTracker().getProgress(advancement).isDone()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Resolved only for the truncated list, never for every historical player.
     * UserCache#getByUuid bumps the entry's lastAccessed, and vanilla's
     * UserCache#save keeps only the 1000 most recently accessed - so naming
     * every player on every query would reorder the server's own usercache.json
     * around whoever happened to be on a leaderboard.
     */
    private static String nameOf(MinecraftServer server, UUID uuid) {
        GameProfile profile = server.getUserCache() == null
                ? null
                : server.getUserCache().getByUuid(uuid).orElse(null);
        if (profile != null && profile.getName() != null) {
            return profile.getName();
        }
        String cached = PlayerProfileCache.nameOf(uuid);
        return cached != null ? cached : uuid.toString().substring(0, 8);
    }

    private static List<UUID> union(java.util.Set<UUID> a, java.util.Set<UUID> b) {
        java.util.Set<UUID> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return new ArrayList<>(all);
    }

    private record Ranked(UUID uuid, int value) {
    }
}
