package com.statsboard.block;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlockEntities {
    public static final BlockEntityType<LeaderboardBlockEntity> LEADERBOARD_BLOCK_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            new Identifier("statsboard", "leaderboard_block_entity"),
            FabricBlockEntityTypeBuilder.create(LeaderboardBlockEntity::new, ModBlocks.LEADERBOARD_BLOCK).build());

    /** Call once from the mod initializer to make sure this class (and its static registration above) loads. */
    public static void register() {
    }
}
