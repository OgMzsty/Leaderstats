package com.statsboard.network;

import com.statsboard.LeaderboardEntry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class StatsboardNetworking {
    /** S2C channel: server -> requesting client, carries both leaderboards at once. */
    public static final Identifier LEADERBOARD_CHANNEL = new Identifier("statsboard", "leaderboard_data");

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
}
