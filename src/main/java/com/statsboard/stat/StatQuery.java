package com.statsboard.stat;

import com.mojang.authlib.GameProfile;
import com.statsboard.LeaderboardEntry;
import com.statsboard.ProfileEntry;
import com.statsboard.mixin.StatHandlerAccessor;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import com.statsboard.PlayerProfileCache;
import com.statsboard.PlayerStats;
import net.minecraft.advancement.Advancement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.stat.Stat;
import net.minecraft.stat.StatType;
import net.minecraft.util.Identifier;

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
     * A profile is one packet and the S2C payload cap is 1 MiB. Each entry is
     * two identifiers plus an int, so this leaves an order of magnitude of room
     * while still covering any realistic player.
     */
    public static final int MAX_PROFILE_ENTRIES = 2000;

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

    /**
     * Every statistic one player has a nonzero value for. Server thread only.
     *
     * <p>Online players are read from their live handler rather than the
     * snapshot: the scanner deliberately skips re-reading their save file, so
     * the cached values date from before they logged in and a profile opened
     * mid-session would be stale by the length of that session.
     */
    public static List<ProfileEntry> profileOf(MinecraftServer server, UUID uuid) {
        StatScanner scanner = StatScanner.instance();
        if (scanner == null) {
            return List.of();
        }

        List<ProfileEntry> entries = new ArrayList<>();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);

        if (player != null) {
            Object2IntMap<Stat<?>> live = ((StatHandlerAccessor) player.getStatHandler()).getStatMap();
            for (Object2IntMap.Entry<Stat<?>> entry : live.object2IntEntrySet()) {
                if (entry.getIntValue() <= 0) {
                    continue;
                }
                StatKey key = keyOf(entry.getKey());
                if (key != null) {
                    entries.add(new ProfileEntry(key, entry.getIntValue()));
                }
            }
            entries.add(new ProfileEntry(StatKey.ADVANCEMENTS,
                    countDone(player, scanner.countableAdvancements())));
        } else {
            StatSnapshot snapshot = scanner.snapshot();
            for (Object2IntMap.Entry<StatKey> entry : snapshot.valuesOf(uuid).object2IntEntrySet()) {
                if (entry.getIntValue() > 0) {
                    entries.add(new ProfileEntry(entry.getKey(), entry.getIntValue()));
                }
            }
            entries.add(new ProfileEntry(StatKey.ADVANCEMENTS, snapshot.advancementsOf(uuid)));
        }

        entries.removeIf(entry -> entry.value() <= 0);
        entries.sort((a, b) -> Integer.compare(b.value(), a.value()));
        return entries.size() > MAX_PROFILE_ENTRIES
                ? List.copyOf(entries.subList(0, MAX_PROFILE_ENTRIES))
                : List.copyOf(entries);
    }

    /**
     * Turns a live Stat back into its identifier pair. Null when either side has
     * no registered id, which a mod can cause by handing out a Stat for a value
     * it never registered.
     */
    private static StatKey keyOf(Stat<?> stat) {
        Identifier typeId = Registries.STAT_TYPE.getId(stat.getType());
        if (typeId == null) {
            return null;
        }
        Identifier valueId = valueIdOf(stat.getType(), stat.getValue());
        return valueId == null ? null : new StatKey(typeId, valueId);
    }

    @SuppressWarnings("unchecked")
    private static <T> Identifier valueIdOf(StatType<T> type, Object value) {
        return type.getRegistry().getId((T) value);
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
    public static String nameFor(MinecraftServer server, UUID uuid) {
        return nameOf(server, uuid);
    }

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
