package com.statsboard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers:
 *   /leaderboard deaths [count]
 *   /leaderboard advancements [count]
 */
public class LeaderboardCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("leaderboard")
                .then(literal("deaths")
                        .executes(ctx -> sendLeaderboard(ctx.getSource(), "deaths", 10))
                        .then(argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(ctx -> sendLeaderboard(ctx.getSource(), "deaths",
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(literal("advancements")
                        .executes(ctx -> sendLeaderboard(ctx.getSource(), "advancements", 10))
                        .then(argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(ctx -> sendLeaderboard(ctx.getSource(), "advancements",
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(
                            "Usage: /leaderboard <deaths|advancements> [count]").formatted(Formatting.YELLOW), false);
                    return 1;
                })
        );
    }

    private static int sendLeaderboard(ServerCommandSource source, String type, int count) {
        List<Map.Entry<UUID, PlayerStats>> entries = type.equals("deaths")
                ? StatsManager.topDeaths(count)
                : StatsManager.topAdvancements(count);

        if (entries.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No data recorded yet.").formatted(Formatting.GRAY), false);
            return 1;
        }

        String title = type.equals("deaths") ? "Top Deaths" : "Top Advancements";
        source.sendFeedback(() -> Text.literal("===== " + title + " =====")
                .formatted(Formatting.GOLD, Formatting.BOLD), false);

        int rank = 1;
        for (Map.Entry<UUID, PlayerStats> entry : entries) {
            String name = StatsManager.nameOf(entry.getKey());
            int value = type.equals("deaths") ? entry.getValue().deaths : entry.getValue().advancements;

            Formatting color;
            if (rank == 1) color = Formatting.GOLD;
            else if (rank == 2) color = Formatting.WHITE;
            else if (rank == 3) color = Formatting.RED;
            else color = Formatting.GRAY;

            final int finalRank = rank;
            final Formatting finalColor = color;
            source.sendFeedback(() -> Text.literal(finalRank + ". " + name + " - " + value)
                    .formatted(finalColor), false);
            rank++;
        }
        return entries.size();
    }
}
