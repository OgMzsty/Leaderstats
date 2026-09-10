package com.statsboard.block;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Right-click a block face to plant an invisible marker there showing the
 * live leaderboard hologram. Always has an enchant glint even though it
 * carries no real enchantment - purely cosmetic, via hasGlint().
 */
public class LeaderboardStickItem extends Item {
    public LeaderboardStickItem(Settings settings) {
        super(settings);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos().offset(context.getSide());

        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (!world.getBlockState(pos).isReplaceable()) {
            return ActionResult.FAIL;
        }

        world.setBlockState(pos, ModBlocks.LEADERBOARD_BLOCK.getDefaultState());
        return ActionResult.CONSUME;
    }
}
