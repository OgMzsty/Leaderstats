package com.statsboard.network;

import com.statsboard.LeaderboardEntry;
import com.statsboard.stat.StatKey;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class StatsboardNetworking {
    /** C2S: client asks for one stat's board. */
    public static final Identifier REQUEST_BOARD = new Identifier("statsboard", "request_board");

    /** S2C: one stat's board, either opening the screen or updating an open one. */
    public static final Identifier BOARD_DATA = new Identifier("statsboard", "board_data");

    /** C2S: retarget one column of a leaderboard block. */
    public static final Identifier SET_BLOCK_STAT = new Identifier("statsboard", "set_block_stat");

    /** S2C: open the picker for a leaderboard block the player just poked. */
    public static final Identifier OPEN_PICKER = new Identifier("statsboard", "open_picker");

    public static void writeEntries(PacketByteBuf buf, List<LeaderboardEntry> entries) {
        buf.writeInt(entries.size());
        for (LeaderboardEntry entry : entries) {
            buf.writeUuid(entry.uuid());
            buf.writeString(entry.name());
            buf.writeInt(entry.count());
            buf.writeBoolean(entry.skinTextureValue() != null);
            if (entry.skinTextureValue() != null) {
                buf.writeString(entry.skinTextureValue());
                buf.writeBoolean(entry.skinTextureSignature() != null);
                if (entry.skinTextureSignature() != null) {
                    buf.writeString(entry.skinTextureSignature());
                }
            }
        }
    }

    public static List<LeaderboardEntry> readEntries(PacketByteBuf buf) {
        int size = buf.readInt();
        List<LeaderboardEntry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UUID uuid = buf.readUuid();
            String name = buf.readString();
            int count = buf.readInt();
            String skinValue = null;
            String skinSignature = null;
            if (buf.readBoolean()) {
                skinValue = buf.readString();
                if (buf.readBoolean()) {
                    skinSignature = buf.readString();
                }
            }
            list.add(new LeaderboardEntry(uuid, name, count, skinValue, skinSignature));
        }
        return list;
    }

    public static void writeKeys(PacketByteBuf buf, List<StatKey> keys) {
        buf.writeInt(keys.size());
        for (StatKey key : keys) {
            key.write(buf);
        }
    }

    public static List<StatKey> readKeys(PacketByteBuf buf) {
        int size = buf.readInt();
        List<StatKey> keys = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            keys.add(StatKey.read(buf));
        }
        return keys;
    }
}
