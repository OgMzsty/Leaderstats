package com.statsboard.network;

import com.statsboard.LeaderboardEntry;
import com.statsboard.block.LeaderboardBlockEntity;
import com.statsboard.stat.StatKey;
import com.statsboard.stat.StatQuery;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/** Server-side receivers and senders. Everything here runs on the server thread. */
public final class StatsboardServerNetworking {
    /** Beyond this the player is not plausibly interacting with the block. */
    private static final double MAX_INTERACT_DISTANCE_SQUARED = 8 * 8;

    private StatsboardServerNetworking() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(StatsboardNetworking.REQUEST_BOARD,
                (server, player, handler, buf, sender) -> {
                    StatKey key = StatKey.read(buf);
                    int limit = buf.readInt();
                    server.execute(() -> {
                        if (!key.isValid()) {
                            return;
                        }
                        sendBoard(player, key, limit, false);
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(StatsboardNetworking.SET_BLOCK_STAT,
                (server, player, handler, buf, sender) -> {
                    BlockPos pos = buf.readBlockPos();
                    int columnIndex = buf.readInt();
                    StatKey key = StatKey.read(buf);
                    server.execute(() -> applyBlockStat(player, pos, columnIndex, key));
                });
    }

    public static void sendBoard(ServerPlayerEntity player, StatKey key, int limit, boolean openScreen) {
        // Clamped here rather than trusting the client's number.
        int capped = Math.max(1, Math.min(limit, StatQuery.MAX_LIMIT));
        List<LeaderboardEntry> entries = StatQuery.topFor(player.getServer(), key, capped);

        PacketByteBuf buf = PacketByteBufs.create();
        key.write(buf);
        buf.writeBoolean(openScreen);
        StatsboardNetworking.writeEntries(buf, entries);
        ServerPlayNetworking.send(player, StatsboardNetworking.BOARD_DATA, buf);
    }

    public static void sendOpenPicker(ServerPlayerEntity player, BlockPos pos, List<StatKey> columns) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        StatsboardNetworking.writeKeys(buf, columns);
        ServerPlayNetworking.send(player, StatsboardNetworking.OPEN_PICKER, buf);
    }

    /**
     * Every field here is client-controlled, so all four conditions are checked
     * before anything is written. Silently ignoring a bad packet is deliberate -
     * a modified client should get no feedback about what passed validation.
     */
    private static void applyBlockStat(ServerPlayerEntity player, BlockPos pos, int columnIndex, StatKey key) {
        if (player.getWorld() == null || !player.getWorld().isChunkLoaded(pos)) {
            return;
        }
        if (player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > MAX_INTERACT_DISTANCE_SQUARED) {
            return;
        }

        BlockEntity blockEntity = player.getWorld().getBlockEntity(pos);
        if (!(blockEntity instanceof LeaderboardBlockEntity leaderboard)) {
            return;
        }
        if (columnIndex < 0 || columnIndex >= leaderboard.getColumns().size()) {
            return;
        }
        // isValid rather than resolve: it uses getOrEmpty, so a removed mod's
        // stat id is rejected instead of silently becoming minecraft:air.
        if (!key.isValid()) {
            return;
        }

        leaderboard.setColumn(columnIndex, key);
    }
}
