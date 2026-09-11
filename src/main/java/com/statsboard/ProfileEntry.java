package com.statsboard;

import com.statsboard.stat.StatKey;

/** One statistic on a player's profile: which stat, and their raw value for it. */
public record ProfileEntry(StatKey key, int value) {
}
