package com.statsboard.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * An invisible marker block - it has no visible model of its own, it just
 * hosts the LeaderboardBlockEntity + renderer that draws the floating
 * hologram. Shape is a small slab at the base (rather than empty/full cube)
 * so the player can still see the selection outline and break it later.
 */
public class LeaderboardBlock extends BlockWithEntity {
    private static final VoxelShape SHAPE = Block.createCuboidShape(6, 0, 6, 10, 1, 10);

    public LeaderboardBlock(Settings settings) {
        super(settings);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    /**
     * Fires on both logical sides. The client side only returns SUCCESS so the
     * arm swings; everything real is behind the isClient guard.
     *
     * <p>Note this only catches clicks on the block's own 4x1x4 nub. Players
     * aiming at the hologram, which stands metres away, hit nothing at all -
     * that gesture is handled by the wand instead.
     */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (player instanceof ServerPlayerEntity serverPlayer
                && world.getBlockEntity(pos) instanceof LeaderboardBlockEntity blockEntity) {
            LeaderboardInteraction.openPicker(serverPlayer, pos, blockEntity);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new LeaderboardBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null
                : checkType(type, ModBlockEntities.LEADERBOARD_BLOCK_ENTITY, LeaderboardBlockEntity::serverTick);
    }
}
