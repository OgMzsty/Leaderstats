package com.statsboard;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import com.statsboard.block.ModBlockEntities;
import com.statsboard.block.ModBlocks;

public class StatsboardMod implements ModInitializer {
    public static final String MOD_ID = "statsboard";

    // Autosave every 5 minutes (20 ticks/sec * 60 sec * 5 min) so we don't
    // hammer the disk on every single death/advancement.
    private static final int AUTOSAVE_INTERVAL_TICKS = 20 * 60 * 5;
    private int tickCounter = 0;

    @Override
    public void onInitialize() {
        ModBlocks.register();
        ModBlockEntities.register();

        // Load stats from the world save once the server has fully started.
        ServerLifecycleEvents.SERVER_STARTED.register(StatsManager::init);

        // Always flush to disk on shutdown so nothing is lost.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> StatsManager.save());

        // Periodic autosave as a safety net (e.g. against crashes).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter >= AUTOSAVE_INTERVAL_TICKS) {
                tickCounter = 0;
                if (StatsManager.isDirty()) {
                    StatsManager.save();
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LeaderboardCommand.register(dispatcher);
            ShowLeaderboardCommand.register(dispatcher);
        });

        // Capture the player's Mojang skin when they join. The texture data is
        // persisted with the stats so the leaderboard can render the same skin
        // even when the player is offline.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            StatsManager.recordPlayerProfile(handler.getPlayer().getGameProfile());
            if (StatsManager.isDirty()) {
                StatsManager.save();
            }
        });

        // Track deaths via Fabric API's documented death event, rather than a
        // custom mixin into internal Minecraft code - more reliable across
        // player/mob subclasses and mapping changes.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayerEntity player) {
                StatsManager.recordDeath(player.getUuid(), player.getGameProfile().getName());
            }
        });
    }
}
