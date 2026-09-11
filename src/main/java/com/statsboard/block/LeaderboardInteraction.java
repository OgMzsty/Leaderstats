package com.statsboard.block;

import com.statsboard.network.StatsboardNetworking;
import com.statsboard.network.StatsboardServerNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared logic for the two ways a player opens a leaderboard's stat picker. */
public final class LeaderboardInteraction {
    /** How near the wand looks for an existing board before it decides to edit rather than place. */
    public static final double WAND_EDIT_RADIUS = 3.0;

    /** The wand aimed at open air searches a little wider, since there is no target block. */
    public static final double WAND_REACH_RADIUS = 5.0;

    private static final long HINT_COOLDOWN_MS = 5000;
    private static final Map<UUID, Long> LAST_HINT = new HashMap<>();

    private LeaderboardInteraction() {
    }

    /**
     * Opens the picker, or tells a vanilla client how to do this without the mod.
     * A client that lacks the mod silently drops unknown channels, so without
     * the fallback the right-click would just appear to do nothing.
     */
    public static void openPicker(ServerPlayerEntity player, BlockPos pos, LeaderboardBlockEntity blockEntity) {
        if (ServerPlayNetworking.canSend(player, StatsboardNetworking.OPEN_PICKER)) {
            StatsboardServerNetworking.sendOpenPicker(player, pos, blockEntity.getColumns());
            return;
        }

        // Action bar rather than chat, and rate limited, so repeatedly poking a
        // board does not fill the log.
        long now = System.currentTimeMillis();
        Long last = LAST_HINT.get(player.getUuid());
        if (last != null && now - last < HINT_COOLDOWN_MS) {
            return;
        }
        // Pruned on write rather than tracked per-connection: only vanilla
        // clients ever reach this, so the map stays tiny either way.
        LAST_HINT.values().removeIf(stamp -> now - stamp > HINT_COOLDOWN_MS);
        LAST_HINT.put(player.getUuid(), now);
        player.sendMessage(Text.translatable("statsboard.hint.client_required"), true);
    }

    /**
     * The nearest leaderboard block within {@code radius} of {@code center}, or
     * null. Box-scanned rather than indexed: this only runs on a right-click,
     * and the radius is small.
     */
    public static BlockPos findNearest(World world, Vec3d center, double radius) {
        BlockPos origin = BlockPos.ofFloored(center);
        int r = (int) Math.ceil(radius);

        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -r, -r), origin.add(r, r, r))) {
            double distance = center.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distance > radius * radius || distance >= nearestDistance) {
                continue;
            }
            // getBlockState first: getBlockEntity goes through a full chunk
            // fetch, and this runs on every air right-click with the wand.
            if (!world.getBlockState(pos).isOf(ModBlocks.LEADERBOARD_BLOCK)) {
                continue;
            }
            if (world.getBlockEntity(pos) instanceof LeaderboardBlockEntity) {
                nearest = pos.toImmutable();
                nearestDistance = distance;
            }
        }
        return nearest;
    }
}
