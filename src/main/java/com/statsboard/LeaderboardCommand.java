package com.statsboard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.statsboard.stat.StatKey;
import com.statsboard.stat.StatQuery;
import com.statsboard.stat.StatValueFormatter;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.stat.StatType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers:
 *   /leaderboard deaths [count]
 *   /leaderboard advancements [count]
 *   /leaderboard stat &lt;stat_type&gt; &lt;stat&gt; [count]
 *
 * <p>The two literal forms predate vanilla-stat support and are kept so command
 * blocks and muscle memory keep working, and so the common cases stay short.
 */
public class LeaderboardCommand {
    private static final SuggestionProvider<ServerCommandSource> TYPE_SUGGESTIONS = (ctx, builder) -> {
        List<Identifier> ids = new ArrayList<>(Registries.STAT_TYPE.getIds());
        ids.add(StatKey.SPECIAL_TYPE);
        return CommandSource.suggestIdentifiers(ids, builder);
    };

    private static final SuggestionProvider<ServerCommandSource> VALUE_SUGGESTIONS = (ctx, builder) -> {
        Identifier typeId = IdentifierArgumentType.getIdentifier(ctx, "stat_type");
        if (StatKey.SPECIAL_TYPE.equals(typeId)) {
            return CommandSource.suggestIdentifiers(List.of(StatKey.ADVANCEMENTS.valueId()), builder);
        }
        StatType<?> type = Registries.STAT_TYPE.getOrEmpty(typeId).orElse(null);
        if (type == null) {
            return builder.buildFuture();
        }
        return CommandSource.suggestIdentifiers(type.getRegistry().getIds(), builder);
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("leaderboard")
                .then(literal("deaths")
                        .executes(ctx -> send(ctx.getSource(), StatKey.DEATHS, 10))
                        .then(argument("count", IntegerArgumentType.integer(1, StatQuery.MAX_LIMIT))
                                .executes(ctx -> send(ctx.getSource(), StatKey.DEATHS,
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(literal("advancements")
                        .executes(ctx -> send(ctx.getSource(), StatKey.ADVANCEMENTS, 10))
                        .then(argument("count", IntegerArgumentType.integer(1, StatQuery.MAX_LIMIT))
                                .executes(ctx -> send(ctx.getSource(), StatKey.ADVANCEMENTS,
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(literal("stat")
                        .then(argument("stat_type", IdentifierArgumentType.identifier())
                                .suggests(TYPE_SUGGESTIONS)
                                .then(argument("stat", IdentifierArgumentType.identifier())
                                        .suggests(VALUE_SUGGESTIONS)
                                        .executes(ctx -> sendParsed(ctx, 10))
                                        .then(argument("count", IntegerArgumentType.integer(1, StatQuery.MAX_LIMIT))
                                                .executes(ctx -> sendParsed(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "count")))))))
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(
                            "Usage: /leaderboard <deaths|advancements|stat <type> <stat>> [count]")
                            .formatted(Formatting.YELLOW), false);
                    return 1;
                })
        );
    }

    private static int sendParsed(CommandContext<ServerCommandSource> ctx, int count) {
        Identifier typeId = IdentifierArgumentType.getIdentifier(ctx, "stat_type");
        Identifier valueId = IdentifierArgumentType.getIdentifier(ctx, "stat");
        StatKey key = new StatKey(typeId, valueId);

        // A clear error rather than silently ranking an empty board - or, for a
        // defaulted registry, quietly ranking minecraft:air.
        if (!key.isValid()) {
            ctx.getSource().sendError(Text.literal("Unknown statistic: " + typeId + " / " + valueId));
            return 0;
        }
        return send(ctx.getSource(), key, count);
    }

    private static int send(ServerCommandSource source, StatKey key, int count) {
        List<LeaderboardEntry> entries = StatQuery.topFor(source.getServer(), key, count);

        if (entries.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No data recorded yet.").formatted(Formatting.GRAY), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal("===== Top ")
                .append(key.displayName())
                .append(" =====")
                .formatted(Formatting.GOLD, Formatting.BOLD), false);

        int rank = 1;
        for (LeaderboardEntry entry : entries) {
            Formatting color;
            if (rank == 1) color = Formatting.GOLD;
            else if (rank == 2) color = Formatting.WHITE;
            else if (rank == 3) color = Formatting.RED;
            else color = Formatting.GRAY;

            final int finalRank = rank;
            final Formatting finalColor = color;
            source.sendFeedback(() -> Text.literal(
                    finalRank + ". " + entry.name() + " - " + StatValueFormatter.format(key, entry.count()))
                    .formatted(finalColor), false);
            rank++;
        }
        return entries.size();
    }
}
