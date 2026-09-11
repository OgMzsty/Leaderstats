package com.statsboard;

import com.statsboard.block.ModBlockEntities;
import com.statsboard.block.ModBlocks;
import com.statsboard.network.StatsboardServerNetworking;
import com.statsboard.stat.StatQuery;
import com.statsboard.stat.StatScanner;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class StatsboardMod implements ModInitializer {
    public static final String MOD_ID = "statsboard";

    @Override
    public void onInitialize() {
        ModBlocks.register();
        ModBlockEntities.register();
        StatsboardServerNetworking.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            PlayerProfileCache.init(server);
            StatScanner.start(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PlayerProfileCache.save();
            StatScanner.stop();
        });

        // The countable advancement set is derived from the advancement loader,
        // which swaps its internal manager on reload, so it has to be rebuilt
        // here rather than cached once.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            StatScanner scanner = StatScanner.instance();
            if (scanner != null && success) {
                scanner.refreshCountableAdvancements();
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            StatScanner scanner = StatScanner.instance();
            if (scanner != null) {
                scanner.tick();
            }
            // Leaderboard results are memoised for a single tick so several
            // boards showing the same stat cost one pass between them.
            StatQuery.clearMemo();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LeaderboardCommand.register(dispatcher);
            ShowLeaderboardCommand.register(dispatcher);
        });

        // Capture the player's Mojang skin when they join, so the leaderboard can
        // render them after they log off.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerProfileCache.recordPlayerProfile(handler.getPlayer().getGameProfile());
            if (PlayerProfileCache.isDirty()) {
                PlayerProfileCache.save();
            }
        });
    }
}
