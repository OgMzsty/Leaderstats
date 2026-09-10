package com.statsboard.block;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlocks {
    public static final Identifier LEADERBOARD_BLOCK_ID = new Identifier("statsboard", "leaderboard_block");
    public static final Identifier LEADERBOARD_STICK_ID = new Identifier("statsboard", "leaderboard_stick");

    // No item form for the block itself - it's only ever placed by the stick,
    // never obtained directly from the inventory.
    public static final LeaderboardBlock LEADERBOARD_BLOCK = new LeaderboardBlock(
            FabricBlockSettings.create().noCollision().dropsNothing().strength(0.5f));

    public static final LeaderboardStickItem LEADERBOARD_STICK =
            new LeaderboardStickItem(new Item.Settings().maxCount(1));

    public static void register() {
        Registry.register(Registries.BLOCK, LEADERBOARD_BLOCK_ID, LEADERBOARD_BLOCK);
        Registry.register(Registries.ITEM, LEADERBOARD_STICK_ID, LEADERBOARD_STICK);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries ->
                entries.add(LEADERBOARD_STICK));
    }
}
