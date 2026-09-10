package com.statsboard.block;

import com.statsboard.PlayerStats;
import com.statsboard.StatsManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LeaderboardBlockEntity extends BlockEntity {
    private static final int REFRESH_INTERVAL_TICKS = 100; // ~5 seconds

    private String topDeathName = "-";
    private int topDeathCount = 0;
    private java.util.UUID topDeathUuid;
    private String topDeathSkinValue;
    private String topDeathSkinSignature;
    private String topAdvName = "-";
    private int topAdvCount = 0;
    private java.util.UUID topAdvUuid;
    private String topAdvSkinValue;
    private String topAdvSkinSignature;
    private int ticksUntilRefresh = 0;

    public LeaderboardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LEADERBOARD_BLOCK_ENTITY, pos, state);
    }

    public static void serverTick(World world, BlockPos pos, BlockState state, LeaderboardBlockEntity blockEntity) {
        if (blockEntity.ticksUntilRefresh > 0) {
            blockEntity.ticksUntilRefresh--;
            return;
        }
        blockEntity.ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
        blockEntity.refresh();
    }

    private void refresh() {
        List<Map.Entry<UUID, PlayerStats>> deaths = StatsManager.topDeaths(1);
        List<Map.Entry<UUID, PlayerStats>> advancements = StatsManager.topAdvancements(1);

        String newDeathName = deaths.isEmpty() ? "-" : StatsManager.nameOf(deaths.get(0).getKey());
        int newDeathCount = deaths.isEmpty() ? 0 : deaths.get(0).getValue().deaths;
        UUID newDeathUuid = deaths.isEmpty() ? null : deaths.get(0).getKey();
        String newDeathSkinValue = deaths.isEmpty() ? null : deaths.get(0).getValue().skinTextureValue;
        String newDeathSkinSignature = deaths.isEmpty() ? null : deaths.get(0).getValue().skinTextureSignature;
        String newAdvName = advancements.isEmpty() ? "-" : StatsManager.nameOf(advancements.get(0).getKey());
        int newAdvCount = advancements.isEmpty() ? 0 : advancements.get(0).getValue().advancements;
        UUID newAdvUuid = advancements.isEmpty() ? null : advancements.get(0).getKey();
        String newAdvSkinValue = advancements.isEmpty() ? null : advancements.get(0).getValue().skinTextureValue;
        String newAdvSkinSignature = advancements.isEmpty() ? null : advancements.get(0).getValue().skinTextureSignature;

        boolean changed = !newDeathName.equals(topDeathName) || newDeathCount != topDeathCount
                || !newAdvName.equals(topAdvName) || newAdvCount != topAdvCount
                || !java.util.Objects.equals(newDeathUuid, topDeathUuid)
                || !java.util.Objects.equals(newDeathSkinValue, topDeathSkinValue)
                || !java.util.Objects.equals(newDeathSkinSignature, topDeathSkinSignature)
                || !java.util.Objects.equals(newAdvUuid, topAdvUuid)
                || !java.util.Objects.equals(newAdvSkinValue, topAdvSkinValue)
                || !java.util.Objects.equals(newAdvSkinSignature, topAdvSkinSignature);

        if (changed) {
            topDeathName = newDeathName;
            topDeathCount = newDeathCount;
            topDeathUuid = newDeathUuid;
            topDeathSkinValue = newDeathSkinValue;
            topDeathSkinSignature = newDeathSkinSignature;
            topAdvName = newAdvName;
            topAdvCount = newAdvCount;
            topAdvUuid = newAdvUuid;
            topAdvSkinValue = newAdvSkinValue;
            topAdvSkinSignature = newAdvSkinSignature;
            markDirty();
        }
    }

    public String getTopDeathName() {
        return topDeathName;
    }

    public int getTopDeathCount() {
        return topDeathCount;
    }

    public java.util.UUID getTopDeathUuid() {
        return topDeathUuid;
    }

    public String getTopDeathSkinValue() {
        return topDeathSkinValue;
    }

    public String getTopDeathSkinSignature() {
        return topDeathSkinSignature;
    }

    public String getTopAdvName() {
        return topAdvName;
    }

    public int getTopAdvCount() {
        return topAdvCount;
    }

    public java.util.UUID getTopAdvUuid() {
        return topAdvUuid;
    }

    public String getTopAdvSkinValue() {
        return topAdvSkinValue;
    }

    public String getTopAdvSkinSignature() {
        return topAdvSkinSignature;
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString("TopDeathName", topDeathName);
        nbt.putInt("TopDeathCount", topDeathCount);
        if (topDeathUuid != null) {
            nbt.putUuid("TopDeathUuid", topDeathUuid);
        }
        if (topDeathSkinValue != null) {
            nbt.putString("TopDeathSkinValue", topDeathSkinValue);
        }
        if (topDeathSkinSignature != null) {
            nbt.putString("TopDeathSkinSignature", topDeathSkinSignature);
        }
        nbt.putString("TopAdvName", topAdvName);
        nbt.putInt("TopAdvCount", topAdvCount);
        if (topAdvUuid != null) {
            nbt.putUuid("TopAdvUuid", topAdvUuid);
        }
        if (topAdvSkinValue != null) {
            nbt.putString("TopAdvSkinValue", topAdvSkinValue);
        }
        if (topAdvSkinSignature != null) {
            nbt.putString("TopAdvSkinSignature", topAdvSkinSignature);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        topDeathName = nbt.getString("TopDeathName");
        topDeathCount = nbt.getInt("TopDeathCount");
        topDeathUuid = nbt.containsUuid("TopDeathUuid") ? nbt.getUuid("TopDeathUuid") : null;
        topAdvName = nbt.getString("TopAdvName");
        topAdvCount = nbt.getInt("TopAdvCount");
        topAdvUuid = nbt.containsUuid("TopAdvUuid") ? nbt.getUuid("TopAdvUuid") : null;
        topDeathSkinValue = nbt.contains("TopDeathSkinValue") ? nbt.getString("TopDeathSkinValue") : null;
        topDeathSkinSignature = nbt.contains("TopDeathSkinSignature") ? nbt.getString("TopDeathSkinSignature") : null;
        topAdvSkinValue = nbt.contains("TopAdvSkinValue") ? nbt.getString("TopAdvSkinValue") : null;
        topAdvSkinSignature = nbt.contains("TopAdvSkinSignature") ? nbt.getString("TopAdvSkinSignature") : null;
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
}
