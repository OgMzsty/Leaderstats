package com.statsboard;

import java.util.UUID;

/**
 * One row on a leaderboard, including the saved Mojang skin texture data.
 */
public record LeaderboardEntry(
        UUID uuid,
        String name,
        int count,
        String skinTextureValue,
        String skinTextureSignature
) {
}
