package com.statsboard.block;

import com.statsboard.LeaderboardEntry;
import com.statsboard.stat.StatKey;
import com.statsboard.stat.StatQuery;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Hosts the floating leaderboard. Each column tracks one {@link StatKey} and
 * caches its current top player, which is what the renderer draws and what gets
 * synced to clients.
 */
public class LeaderboardBlockEntity extends BlockEntity {
    private static final int REFRESH_INTERVAL_TICKS = 100; // ~5 seconds
    public static final int MAX_COLUMNS = 3;

    /** A fresh block looks exactly like the pre-vanilla-stats version did. */
    private static final List<StatKey> DEFAULT_COLUMNS = List.of(StatKey.DEATHS, StatKey.ADVANCEMENTS);

    private List<StatKey> columns = new ArrayList<>(DEFAULT_COLUMNS);
    private List<Column> cache = new ArrayList<>();
    private int ticksUntilRefresh;

    public LeaderboardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LEADERBOARD_BLOCK_ENTITY, pos, state);
        resizeCache();
        // Staggered from the position, so a chunk-load burst does not make every
        // board on the server refresh on the same tick.
        this.ticksUntilRefresh = Math.floorMod(pos.hashCode(), REFRESH_INTERVAL_TICKS);
    }

    public static void serverTick(World world, BlockPos pos, BlockState state, LeaderboardBlockEntity blockEntity) {
        if (blockEntity.ticksUntilRefresh > 0) {
            blockEntity.ticksUntilRefresh--;
            return;
        }
        blockEntity.ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
        blockEntity.refresh(world.getServer());
    }

    public List<StatKey> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    public Column getColumn(int index) {
        return index >= 0 && index < cache.size() ? cache.get(index) : Column.EMPTY;
    }

    public int getColumnCount() {
        return columns.size();
    }

    public void setColumn(int index, StatKey key) {
        if (index < 0 || index >= columns.size()) {
            return;
        }
        columns.set(index, key);
        cache.set(index, Column.EMPTY);
        ticksUntilRefresh = 0;
        markDirty();
    }

    /** Grows with a copy of the last column's stat, shrinks from the right. */
    public void setColumnCount(int count) {
        int clamped = Math.max(1, Math.min(count, MAX_COLUMNS));
        if (clamped == columns.size()) {
            return;
        }
        while (columns.size() > clamped) {
            columns.remove(columns.size() - 1);
        }
        while (columns.size() < clamped) {
            columns.add(columns.get(columns.size() - 1));
        }
        resizeCache();
        ticksUntilRefresh = 0;
        markDirty();
    }

    private void refresh(MinecraftServer server) {
        if (server == null) {
            return;
        }

        boolean changed = false;
        for (int i = 0; i < columns.size(); i++) {
            List<LeaderboardEntry> top = StatQuery.topFor(server, columns.get(i), 1);
            Column next = top.isEmpty() ? Column.EMPTY : Column.of(top.get(0));
            if (!next.equals(cache.get(i))) {
                cache.set(i, next);
                changed = true;
            }
        }

        if (changed) {
            markDirty();
        }
    }

    private void resizeCache() {
        List<Column> resized = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            resized.add(i < cache.size() ? cache.get(i) : Column.EMPTY);
        }
        cache = resized;
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);

        NbtList columnList = new NbtList();
        for (StatKey key : columns) {
            columnList.add(key.toNbt());
        }
        nbt.put("Columns", columnList);

        NbtList cacheList = new NbtList();
        for (Column column : cache) {
            cacheList.add(column.toNbt());
        }
        nbt.put("Tops", cacheList);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);

        List<StatKey> loaded = new ArrayList<>();
        if (nbt.contains("Columns", NbtElement.LIST_TYPE)) {
            NbtList columnList = nbt.getList("Columns", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < columnList.size() && loaded.size() < MAX_COLUMNS; i++) {
                StatKey.fromNbt(columnList.getCompound(i)).ifPresent(loaded::add);
            }
        }
        // Covers both a block saved by the pre-vanilla-stats version and one
        // whose every column failed to parse.
        columns = loaded.isEmpty() ? new ArrayList<>(DEFAULT_COLUMNS) : loaded;

        cache = new ArrayList<>();
        NbtList cacheList = nbt.getList("Tops", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < columns.size(); i++) {
            cache.add(i < cacheList.size() ? Column.fromNbt(cacheList.getCompound(i)) : Column.EMPTY);
        }
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    @Override
    public BlockEntityUpdateS2CPacket toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public void markDirty() {
        super.markDirty();
        if (world != null && !world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }

    /**
     * One column's current leader. Initialised to a visible placeholder rather
     * than an empty string, so a block renders sanely in the window before its
     * first refresh.
     */
    public record Column(String name, int value, UUID uuid, String skinValue, String skinSignature) {
        public static final Column EMPTY = new Column("-", 0, null, null, null);

        static Column of(LeaderboardEntry entry) {
            return new Column(entry.name(), entry.count(), entry.uuid(),
                    entry.skinTextureValue(), entry.skinTextureSignature());
        }

        NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putString("Name", name);
            nbt.putInt("Value", value);
            if (uuid != null) {
                nbt.putUuid("Uuid", uuid);
            }
            if (skinValue != null) {
                nbt.putString("SkinValue", skinValue);
            }
            if (skinSignature != null) {
                nbt.putString("SkinSignature", skinSignature);
            }
            return nbt;
        }

        static Column fromNbt(NbtCompound nbt) {
            String name = nbt.contains("Name") ? nbt.getString("Name") : "-";
            return new Column(
                    name.isEmpty() ? "-" : name,
                    nbt.getInt("Value"),
                    nbt.containsUuid("Uuid") ? nbt.getUuid("Uuid") : null,
                    nbt.contains("SkinValue") ? nbt.getString("SkinValue") : null,
                    nbt.contains("SkinSignature") ? nbt.getString("SkinSignature") : null);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Column column)) {
                return false;
            }
            return value == column.value
                    && name.equals(column.name)
                    && Objects.equals(uuid, column.uuid)
                    && Objects.equals(skinValue, column.skinValue)
                    && Objects.equals(skinSignature, column.skinSignature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value, uuid, skinValue, skinSignature);
        }
    }
}
