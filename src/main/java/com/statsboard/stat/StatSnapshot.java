package com.statsboard.stat;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable view of every player's on-disk stats, published by
 * {@link StatScanner} and read by {@link StatQuery}.
 *
 * <p>Values are plain ints keyed by {@link StatKey} rather than vanilla
 * ServerStatHandler or Stat objects. That is deliberate: building those would
 * mean calling StatType#getOrCreateStat from the scanner thread, which races
 * the server thread on an unsynchronized map. It is also far smaller - this is
 * just the file's contents as primitives.
 */
public final class StatSnapshot {
    public static final StatSnapshot EMPTY = new StatSnapshot(Map.of(), Map.of(), Set.of());

    private final Map<UUID, Object2IntMap<StatKey>> values;
    private final Map<UUID, Integer> advancementCounts;
    private final Set<UUID> knownPlayers;

    public StatSnapshot(Map<UUID, Object2IntMap<StatKey>> values,
                        Map<UUID, Integer> advancementCounts,
                        Set<UUID> knownPlayers) {
        this.values = values;
        this.advancementCounts = advancementCounts;
        this.knownPlayers = knownPlayers;
    }

    /**
     * Everyone who has ever played on this world: the union of the stats and
     * advancements directories, not just the former. An imported or hand-edited
     * world can have an advancements file with no stats file, and such a player
     * still belongs on the advancements board.
     */
    public Set<UUID> knownPlayers() {
        return knownPlayers;
    }

    /** Every stat this player has on disk; empty rather than null. */
    public Object2IntMap<StatKey> valuesOf(UUID uuid) {
        return values.getOrDefault(uuid, Object2IntMaps.emptyMap());
    }

    public int valueOf(UUID uuid, StatKey key) {
        return values.getOrDefault(uuid, Object2IntMaps.emptyMap()).getInt(key);
    }

    public int advancementsOf(UUID uuid) {
        return advancementCounts.getOrDefault(uuid, 0);
    }
}
