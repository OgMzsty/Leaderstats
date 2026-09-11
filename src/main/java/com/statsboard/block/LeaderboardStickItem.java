package com.statsboard.block;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Places a leaderboard marker, and doubles as the way to reconfigure one.
 *
 * <p>Two right-click paths matter, and both are needed. Aiming at a block goes
 * to {@link #useOnBlock}; aiming at a hologram figure standing over open ground
 * hits nothing at all, so the raycast misses and the click arrives at
 * {@link #use} instead. Implementing only the former would leave the natural
 * gesture - point at the floating player, right-click - doing nothing.
 *
 * <p>Always has an enchant glint despite carrying no enchantment, via hasGlint().
 */
public class LeaderboardStickItem extends Item {
    public LeaderboardStickItem(Settings settings) {
        super(settings);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    /** Aimed at open air: the only way to reach a hologram, so edit, never place. */
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (world.isClient || player.isSneaking()) {
            return TypedActionResult.success(stack, world.isClient);
        }

        BlockPos nearest = LeaderboardInteraction.findNearest(
                world, player.getEyePos(), LeaderboardInteraction.WAND_REACH_RADIUS);
        if (nearest != null && player instanceof ServerPlayerEntity serverPlayer
                && world.getBlockEntity(nearest) instanceof LeaderboardBlockEntity blockEntity) {
            LeaderboardInteraction.openPicker(serverPlayer, nearest, blockEntity);
            return TypedActionResult.consume(stack);
        }
        return TypedActionResult.pass(stack);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        PlayerEntity player = context.getPlayer();
        BlockPos pos = context.getBlockPos().offset(context.getSide());

        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        // Sneaking always places. Without this escape hatch a three-column board
        // spans 9 blocks while reach is under 5, so a second board could never
        // be put where a builder wanted it.
        if (player != null && !player.isSneaking()) {
            Vec3d placement = Vec3d.ofCenter(pos);
            BlockPos nearest = LeaderboardInteraction.findNearest(
                    world, placement, LeaderboardInteraction.WAND_EDIT_RADIUS);
            if (nearest != null && player instanceof ServerPlayerEntity serverPlayer
                    && world.getBlockEntity(nearest) instanceof LeaderboardBlockEntity blockEntity) {
                LeaderboardInteraction.openPicker(serverPlayer, nearest, blockEntity);
                return ActionResult.CONSUME;
            }
        }

        if (!world.getBlockState(pos).isReplaceable()) {
            return ActionResult.FAIL;
        }

        world.setBlockState(pos, ModBlocks.LEADERBOARD_BLOCK.getDefaultState());
        return ActionResult.CONSUME;
    }
}
