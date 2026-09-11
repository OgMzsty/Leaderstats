package com.statsboard.network;

import com.statsboard.LeaderboardEntry;
import com.statsboard.ProfileEntry;
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

    /** C2S: grow or shrink a leaderboard block's column count. */
    public static final Identifier SET_COLUMN_COUNT = new Identifier("statsboard", "set_column_count");

    /** C2S: client asks for one player's full stat profile. */
    public static final Identifier REQUEST_PROFILE = new Identifier("statsboard", "request_profile");

    /** S2C: that player's profile. */
    public static final Identifier PROFILE_DATA = new Identifier("statsboard", "profile_data");

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

    public static void writeProfile(PacketByteBuf buf, List<ProfileEntry> entries) {
        buf.writeInt(entries.size());
        for (ProfileEntry entry : entries) {
            entry.key().write(buf);
            buf.writeInt(entry.value());
        }
    }

    public static List<ProfileEntry> readProfile(PacketByteBuf buf) {
        int size = buf.readInt();
        List<ProfileEntry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new ProfileEntry(StatKey.read(buf), buf.readInt()));
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
