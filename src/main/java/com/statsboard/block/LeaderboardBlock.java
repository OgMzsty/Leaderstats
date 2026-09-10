package com.statsboard.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
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
