package com.statsboard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.statsboard.network.StatsboardServerNetworking;
import com.statsboard.stat.StatKey;
import com.statsboard.stat.StatQuery;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers /showleaderboard [stat_type] [stat], which sends the requesting
 * player one stat's board and opens the GUI client-side. Needs the mod on both
 * ends, unlike the plain-text /leaderboard.
 */
public class ShowLeaderboardCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("showleaderboard")
                .executes(ctx -> open(ctx.getSource(), StatKey.DEATHS))
                .then(argument("stat_type", IdentifierArgumentType.identifier())
                        .then(argument("stat", IdentifierArgumentType.identifier())
                                .executes(ShowLeaderboardCommand::openParsed))));
    }

    private static int openParsed(CommandContext<ServerCommandSource> ctx) {
        Identifier typeId = IdentifierArgumentType.getIdentifier(ctx, "stat_type");
        Identifier valueId = IdentifierArgumentType.getIdentifier(ctx, "stat");
        StatKey key = new StatKey(typeId, valueId);

        if (!key.isValid()) {
            ctx.getSource().sendError(Text.literal("Unknown statistic: " + typeId + " / " + valueId));
            return 0;
        }
        return open(ctx.getSource(), key);
    }

    private static int open(ServerCommandSource source, StatKey key) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only players can open the leaderboard GUI. Try /leaderboard instead."));
            return 0;
        }

        StatsboardServerNetworking.sendBoard(player, key, StatQuery.MAX_LIMIT, true);
        return 1;
    }
}
