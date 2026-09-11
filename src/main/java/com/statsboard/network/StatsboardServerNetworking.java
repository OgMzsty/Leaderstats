package com.statsboard.network;

import com.statsboard.LeaderboardEntry;
import com.statsboard.block.LeaderboardBlockEntity;
import com.statsboard.stat.StatKey;
import com.statsboard.stat.StatQuery;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side receivers and senders. All real work runs on the server thread. */
public final class StatsboardServerNetworking {
    /** Beyond this the player is not plausibly interacting with the block. */
    private static final double MAX_INTERACT_DISTANCE_SQUARED = 8 * 8;

    /**
     * A board query is a full sort over every player who has ever played, so an
     * unthrottled client could pin the server thread just by walking the ~7700
     * stats the picker enumerates. The per-tick memo only collapses *identical*
     * keys, so it is no defence against distinct ones.
     */
    private static final long REQUEST_COOLDOWN_MS = 250;
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private StatsboardServerNetworking() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(StatsboardNetworking.REQUEST_BOARD,
                (server, player, handler, buf, sender) -> {
                    // Read before the thread hop, but defensively: this is the
                    // netty thread, where a throw becomes a disconnect.
                    Optional<StatKey> key = StatKey.tryRead(buf);
                    int limit;
                    try {
                        limit = buf.readInt();
                    } catch (RuntimeException e) {
                        return;
                    }
                    if (key.isEmpty()) {
                        return;
                    }
                    server.execute(() -> {
                        if (!key.get().isValid() || !allowRequest(player)) {
                            return;
                        }
                        sendBoard(player, key.get(), limit, false);
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(StatsboardNetworking.SET_BLOCK_STAT,
                (server, player, handler, buf, sender) -> {
                    BlockPos pos;
                    int columnIndex;
                    Optional<StatKey> key;
                    try {
                        pos = buf.readBlockPos();
                        columnIndex = buf.readInt();
                        key = StatKey.tryRead(buf);
                    } catch (RuntimeException e) {
                        return;
                    }
                    if (key.isEmpty()) {
                        return;
                    }
                    server.execute(() -> applyBlockStat(player, pos, columnIndex, key.get()));
                });

        ServerPlayNetworking.registerGlobalReceiver(StatsboardNetworking.SET_COLUMN_COUNT,
                (server, player, handler, buf, sender) -> {
                    BlockPos pos;
                    int count;
                    try {
                        pos = buf.readBlockPos();
                        count = buf.readInt();
                    } catch (RuntimeException e) {
                        return;
                    }
                    server.execute(() -> applyColumnCount(player, pos, count));
                });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                LAST_REQUEST.remove(handler.getPlayer().getUuid()));
    }

    private static boolean allowRequest(ServerPlayerEntity player) {
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.get(player.getUuid());
        if (last != null && now - last < REQUEST_COOLDOWN_MS) {
            return false;
        }
        LAST_REQUEST.put(player.getUuid(), now);
        return true;
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
     * Every field here is client-controlled, so all conditions are checked before
     * anything is written. Silently ignoring a bad packet is deliberate - a
     * modified client should learn nothing about what passed validation.
     */
    private static void applyBlockStat(ServerPlayerEntity player, BlockPos pos, int columnIndex, StatKey key) {
        LeaderboardBlockEntity leaderboard = reachable(player, pos);
        if (leaderboard == null || columnIndex < 0 || columnIndex >= leaderboard.getColumns().size()) {
            return;
        }
        // isValid rather than resolve: it uses getOrEmpty, so a removed mod's
        // stat id is rejected instead of silently becoming minecraft:air.
        if (!key.isValid()) {
            return;
        }
        leaderboard.setColumn(columnIndex, key);
    }

    private static void applyColumnCount(ServerPlayerEntity player, BlockPos pos, int count) {
        LeaderboardBlockEntity leaderboard = reachable(player, pos);
        if (leaderboard == null || count < 1 || count > LeaderboardBlockEntity.MAX_COLUMNS) {
            return;
        }
        leaderboard.setColumnCount(count);
    }

    /** The block the player is allowed to be editing, or null. */
    private static LeaderboardBlockEntity reachable(ServerPlayerEntity player, BlockPos pos) {
        if (player.getWorld() == null || !player.getWorld().isChunkLoaded(pos)) {
            return null;
        }
        if (player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > MAX_INTERACT_DISTANCE_SQUARED) {
            return null;
        }
        BlockEntity blockEntity = player.getWorld().getBlockEntity(pos);
        return blockEntity instanceof LeaderboardBlockEntity leaderboard ? leaderboard : null;
    }
}
