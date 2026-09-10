package com.statsboard;

import com.mojang.brigadier.CommandDispatcher;
import com.statsboard.network.StatsboardNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers /showleaderboard, which sends the requesting player a packet with
 * the current top 50 for both deaths and advancements. The client mod then
 * opens the LeaderboardScreen GUI. This requires the mod to be installed on
 * both the server AND the client (unlike the plain-text /leaderboard command).
 */
public class ShowLeaderboardCommand {
    private static final int GUI_LIST_SIZE = 50;

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("showleaderboard").executes(ctx -> open(ctx.getSource())));
    }

    private static int open(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only players can open the leaderboard GUI. Try /leaderboard instead."));
            return 0;
        }

        List<LeaderboardEntry> deaths = toEntries(StatsManager.topDeaths(GUI_LIST_SIZE), true);
        List<LeaderboardEntry> advancements = toEntries(StatsManager.topAdvancements(GUI_LIST_SIZE), false);

        PacketByteBuf buf = PacketByteBufs.create();
        StatsboardNetworking.writeEntries(buf, deaths);
        StatsboardNetworking.writeEntries(buf, advancements);

        ServerPlayNetworking.send(player, StatsboardNetworking.LEADERBOARD_CHANNEL, buf);
        return 1;
    }

    private static List<LeaderboardEntry> toEntries(List<Map.Entry<UUID, PlayerStats>> source, boolean deaths) {
        return source.stream()
                .map(e -> new LeaderboardEntry(
                        e.getKey(),
                        StatsManager.nameOf(e.getKey()),
                        deaths ? e.getValue().deaths : e.getValue().advancements,
                        e.getValue().skinTextureValue,
                        e.getValue().skinTextureSignature))
                .collect(Collectors.toList());
    }
}
